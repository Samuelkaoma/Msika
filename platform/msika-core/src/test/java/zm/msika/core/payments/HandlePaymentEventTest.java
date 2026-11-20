package zm.msika.core.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import zm.msika.core.Fakes;
import zm.msika.core.ledger.LedgerFakes;
import zm.msika.core.ledger.NewEntry;
import zm.msika.core.ledger.RecordEntry;
import zm.msika.core.council.Council;
import zm.msika.core.payments.HandlePaymentEvent.Outcome;
import zm.msika.core.payments.PaymentRequest.Purpose;
import zm.msika.core.payments.PaymentRequest.Status;
import zm.msika.core.stall.Stall;
import zm.msika.core.stall.StallStatus;
import zm.msika.ledger.Currency;
import zm.msika.ledger.EntryKind;
import zm.msika.ledger.LedgerEntry;
import zm.msika.ledger.Money;
import zm.msika.ledger.Provenance;

/** Specification for what happens when the payments system calls back, including every way it can misbehave. */
class HandlePaymentEventTest {

    private static final Instant DONE = Instant.parse("2026-11-24T10:00:00Z");

    /** In-memory payment requests. */
    static final class Requests implements PaymentRequestRepository {
        final Map<UUID, PaymentRequest> byId = new HashMap<>();
        final List<String> locked = new ArrayList<>();

        @Override
        public void add(PaymentRequest request) {
            byId.put(request.id(), request);
        }

        @Override
        public Optional<PaymentRequest> find(UUID councilId, UUID id) {
            return Optional.ofNullable(byId.get(id)).filter(r -> r.councilId().equals(councilId));
        }

        @Override
        public Optional<PaymentRequest> findForUpdate(UUID councilId, UUID id) {
            locked.add(id.toString());
            return find(councilId, id);
        }

        @Override
        public void attachTransaction(UUID councilId, UUID id, String txn) {
            PaymentRequest r = byId.get(id);
            if (r.paymentsTransactionId() == null) {
                byId.put(id, with(r, r.status(), txn, r.failureReason(), r.completedAt()));
            }
        }

        @Override
        public void markSucceeded(UUID councilId, UUID id, String txn, Instant at) {
            PaymentRequest r = byId.get(id);
            byId.put(id, with(r, Status.SUCCEEDED, txn, null, at));
        }

        @Override
        public void markFailed(UUID councilId, UUID id, String txn, String reason, Instant at) {
            PaymentRequest r = byId.get(id);
            byId.put(id, with(r, Status.FAILED, txn, reason, at));
        }

        @Override
        public List<PaymentRequest> listSent(UUID councilId) {
            return byId.values().stream().filter(r -> r.status() == Status.SENT).toList();
        }

        private static PaymentRequest with(PaymentRequest r, Status status, String txn, String reason, Instant at) {
            return new PaymentRequest(r.id(), r.councilId(), r.purpose(), r.stallId(), r.amount(), status, txn, reason,
                    r.createdAt(), at);
        }
    }

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-24T10:05:00Z"), ZoneOffset.UTC);
    private final Fakes.Councils councils = new Fakes.Councils();
    private final Fakes.Stalls stalls = new Fakes.Stalls();
    private final LedgerFakes.Ledger ledger = new LedgerFakes.Ledger();
    private final LedgerFakes.Seals seals = new LedgerFakes.Seals();
    private final Requests requests = new Requests();

    private Council council;
    private UUID stallId;
    private PaymentRequest request;
    private HandlePaymentEvent handler;

    @BeforeEach
    void setUp() {
        council = new Council(UUID.randomUUID(), "Lusaka City Council", java.time.ZoneId.of("Africa/Lusaka"),
                Instant.parse("2026-11-20T08:00:00Z"));
        councils.add(council);
        stallId = UUID.randomUUID();
        stalls.addAll(List.of(new Stall(stallId, council.id(), UUID.randomUUID(), "SOW-B-114", "STANDARD",
                StallStatus.ALLOCATED, "t".repeat(22))));
        request = new PaymentRequest(UUID.randomUUID(), council.id(), Purpose.DEPOSIT, stallId,
                Money.ofMinor(45_000, Currency.ZMW), Status.SENT, null, null, Instant.parse("2026-11-24T09:59:00Z"), null);
        requests.add(request);
        RecordEntry recordEntry = new RecordEntry(councils, stalls, ledger, seals, new LedgerFakes.Lock());
        handler = new HandlePaymentEvent(requests, recordEntry, clock);
    }

    private PaymentEvent succeeded(String txn, long amount) {
        return new PaymentEvent("cb-" + txn, txn, PaymentEvent.Status.SUCCEEDED, amount, request.reference(), null, DONE);
    }

    private PaymentEvent failed(String txn, String reason) {
        return new PaymentEvent("cb-" + txn, txn, PaymentEvent.Status.FAILED, 45_000, request.reference(), reason, DONE);
    }

    private PaymentRequest current() {
        return requests.byId.get(request.id());
    }

    @Nested
    @DisplayName("a confirmed payment")
    class Confirmed {

        @Test
        @DisplayName("is recorded as one DEPOSIT entry, provenance DEPOSITED, keyed by the payments transaction id")
        void recorded() {
            Outcome outcome = handler.execute(succeeded("tx-1", 45_000));

            assertThat(outcome).isEqualTo(Outcome.RECORDED);
            assertThat(ledger.entries).hasSize(1);
            LedgerEntry entry = ledger.entries.get(0);
            assertThat(entry.kind()).isEqualTo(EntryKind.DEPOSIT);
            assertThat(entry.provenance()).isEqualTo(Provenance.DEPOSITED);
            assertThat(entry.amount().minorUnits()).isEqualTo(45_000);
            assertThat(entry.stallId()).isEqualTo(stallId);
            assertThat(entry.idempotencyKey()).isEqualTo("zamsim:tx-1").isEqualTo(HandlePaymentEvent.ledgerKey("tx-1"));
            assertThat(entry.occurredAt()).as("the time the payments system settled it").isEqualTo(DONE);
            assertThat(current().status()).isEqualTo(Status.SUCCEEDED);
            assertThat(current().paymentsTransactionId()).isEqualTo("tx-1");
        }

        @Test
        @DisplayName("the request's row is locked first, so two copies of one callback are handled one after the other")
        void locksTheRequest() {
            handler.execute(succeeded("tx-1", 45_000));

            assertThat(requests.locked).containsExactly(request.id().toString());
        }

        @Test
        @DisplayName("the same callback delivered twice, or ten times, records exactly one entry")
        void duplicates() {
            Outcome first = handler.execute(succeeded("tx-1", 45_000));
            List<Outcome> repeats = new ArrayList<>();
            for (int i = 0; i < 9; i++) {
                repeats.add(handler.execute(succeeded("tx-1", 45_000)));
            }

            assertThat(first).isEqualTo(Outcome.RECORDED);
            assertThat(repeats).containsOnly(Outcome.DUPLICATE);
            assertThat(ledger.entries).hasSize(1);
        }

        @Test
        @DisplayName("a callback for an amount that was not asked for is refused, records nothing, and leaves the request waiting")
        void amountMismatch() {
            assertThatThrownBy(() -> handler.execute(succeeded("tx-1", 45_001)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("45001");

            assertThat(ledger.entries).isEmpty();
            assertThat(current().status()).isEqualTo(Status.SENT);
        }

        @Test
        @DisplayName("recovers if a crash left the ledger entry written but the request not marked: still exactly one entry")
        void crashRecovery() {
            // The entry was recorded before the crash, but the request still says SENT.
            new RecordEntry(councils, stalls, ledger, seals, new LedgerFakes.Lock()).execute(new NewEntry(
                    council.id(), stallId, EntryKind.DEPOSIT, Provenance.DEPOSITED, Money.ofMinor(45_000, Currency.ZMW),
                    DONE, "zamsim:tx-1", null));

            Outcome outcome = handler.execute(succeeded("tx-1", 45_000));

            assertThat(outcome).isEqualTo(Outcome.RECORDED);
            assertThat(ledger.entries).as("not recorded a second time").hasSize(1);
            assertThat(current().status()).isEqualTo(Status.SUCCEEDED);
        }
    }

    @Nested
    @DisplayName("a failed payment")
    class Failed {

        @Test
        @DisplayName("is noted on the request with its reason, and puts nothing on the ledger")
        void failureNoted() {
            Outcome outcome = handler.execute(failed("tx-1", "INSUFFICIENT_FUNDS"));

            assertThat(outcome).isEqualTo(Outcome.FAILURE_NOTED);
            assertThat(current().status()).isEqualTo(Status.FAILED);
            assertThat(current().failureReason()).isEqualTo("INSUFFICIENT_FUNDS");
            assertThat(ledger.entries).isEmpty();
        }

        @Test
        @DisplayName("the first outcome wins: a later contradictory callback cannot change a failed request")
        void firstOutcomeWins() {
            handler.execute(failed("tx-1", "SIMULATED_FAILURE"));

            Outcome later = handler.execute(succeeded("tx-1", 45_000));

            assertThat(later).isEqualTo(Outcome.DUPLICATE);
            assertThat(current().status()).isEqualTo(Status.FAILED);
            assertThat(ledger.entries).isEmpty();
        }

        @Test
        @DisplayName("and a confirmed payment cannot later be turned into a failure")
        void successCannotBecomeFailure() {
            handler.execute(succeeded("tx-1", 45_000));

            assertThat(handler.execute(failed("tx-1", "SIMULATED_FAILURE"))).isEqualTo(Outcome.DUPLICATE);
            assertThat(current().status()).isEqualTo(Status.SUCCEEDED);
            assertThat(ledger.entries).hasSize(1);
        }

        @Test
        @DisplayName("a failure with no reason is still recorded as a failure")
        void noReason() {
            handler.execute(failed("tx-1", null));

            assertThat(current().failureReason()).isEqualTo("FAILED");
        }
    }

    @Nested
    @DisplayName("a callback that is not about one of our requests")
    class Unmatched {

        @Test
        @DisplayName("is reported UNMATCHED and changes nothing: no reference, a foreign one, an unknown id, another council's id")
        void unmatched() {
            PaymentEvent none = new PaymentEvent("cb", "tx-9", PaymentEvent.Status.SUCCEEDED, 45_000, null, null, DONE);
            PaymentEvent foreign = new PaymentEvent("cb", "tx-9", PaymentEvent.Status.SUCCEEDED, 45_000, "SOMEONE-ELSE-7", null, DONE);
            PaymentEvent unknown = new PaymentEvent("cb", "tx-9", PaymentEvent.Status.SUCCEEDED, 45_000,
                    PaymentReference.of(council.id(), UUID.randomUUID()), null, DONE);
            PaymentEvent otherCouncil = new PaymentEvent("cb", "tx-9", PaymentEvent.Status.SUCCEEDED, 45_000,
                    PaymentReference.of(UUID.randomUUID(), request.id()), null, DONE);

            for (PaymentEvent event : List.of(none, foreign, unknown, otherCouncil)) {
                assertThat(handler.execute(event)).isEqualTo(Outcome.UNMATCHED);
            }
            assertThat(ledger.entries).isEmpty();
            assertThat(current().status()).isEqualTo(Status.SENT);
        }
    }

    @Nested
    @DisplayName("the reference")
    class References {

        @Test
        @DisplayName("round-trips the council and request ids")
        void roundTrip() {
            UUID c = UUID.randomUUID();
            UUID r = UUID.randomUUID();

            assertThat(PaymentReference.parse(PaymentReference.of(c, r))).contains(new PaymentReference.Parts(c, r));
        }

        @Test
        @DisplayName("is not fooled by anything that is not exactly ours")
        void junk() {
            for (String junk : new String[] {null, "", "msika:", "msika:abc:def", "msika:" + UUID.randomUUID(),
                "msika:" + UUID.randomUUID() + ":" + UUID.randomUUID() + ":extra", "other:" + UUID.randomUUID() + ":" + UUID.randomUUID()}) {
                assertThat(PaymentReference.parse(junk)).as(String.valueOf(junk)).isEmpty();
            }
        }
    }
}
