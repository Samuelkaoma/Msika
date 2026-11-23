package zm.msika.core.deposit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import zm.msika.core.agent.Agent;
import zm.msika.core.agent.AgentRepository;
import zm.msika.core.phone.PhoneNumber;
import zm.msika.core.wallet.WalletOwner;
import zm.msika.core.wallet.WalletRegistry;

/** In-memory stand-ins for the deposit, wallet and agent ports. */
public final class DepositFakes {

    private DepositFakes() {}

    public static final class Deposits implements DepositRepository {
        public final Map<UUID, Deposit> byId = new LinkedHashMap<>();

        @Override
        public boolean add(Deposit d) {
            boolean clash = byId.values().stream().anyMatch(o -> o.councilId().equals(d.councilId())
                    && ((o.businessDate().equals(d.businessDate()) && o.reference().equals(d.reference()))
                            || (o.agentId().equals(d.agentId()) && o.idempotencyKey().equals(d.idempotencyKey()))));
            if (clash) {
                return false;
            }
            byId.put(d.id(), d);
            return true;
        }

        @Override
        public Optional<Deposit> find(UUID councilId, UUID id) {
            return Optional.ofNullable(byId.get(id)).filter(d -> d.councilId().equals(councilId));
        }

        @Override
        public Optional<Deposit> findByPaymentRequest(UUID councilId, UUID requestId) {
            return byId.values().stream()
                    .filter(d -> d.councilId().equals(councilId) && d.paymentRequestId().equals(requestId)).findFirst();
        }

        @Override
        public Optional<Deposit> findByKey(UUID councilId, UUID agentId, String key) {
            return byId.values().stream().filter(d -> d.councilId().equals(councilId) && d.agentId().equals(agentId)
                    && d.idempotencyKey().equals(key)).findFirst();
        }

        @Override
        public List<Deposit> findByReference(UUID councilId, String reference, java.time.LocalDate since) {
            return byId.values().stream()
                    .filter(d -> d.councilId().equals(councilId) && d.reference().equals(reference)
                            && !d.businessDate().isBefore(since))
                    .sorted(Comparator.comparing(Deposit::createdAt).reversed()).toList();
        }

        @Override
        public List<Deposit> list(UUID councilId, DepositStatus status, int limit) {
            return byId.values().stream()
                    .filter(d -> d.councilId().equals(councilId) && (status == null || d.status() == status))
                    .sorted(Comparator.comparing(Deposit::createdAt).reversed()).limit(limit).toList();
        }

        @Override
        public boolean transition(UUID councilId, UUID id, DepositStatus from, DepositStatus to, String reason,
                Long ledgerEntryId, Instant at) {
            Deposit d = byId.get(id);
            if (d == null || !d.councilId().equals(councilId) || d.status() != from) {
                return false;
            }
            byId.put(id, new Deposit(d.id(), d.councilId(), d.stallId(), d.agentId(), d.amount(), to, d.reference(),
                    d.businessDate(), d.paymentRequestId(), ledgerEntryId == null ? d.ledgerEntryId() : ledgerEntryId,
                    reason, d.idempotencyKey(), d.createdAt(), at));
            return true;
        }
    }

    public static final class Wallets implements WalletRegistry {
        public final List<WalletOwner> all = new ArrayList<>();

        @Override
        public boolean register(WalletOwner o) {
            boolean clash = all.stream().anyMatch(w -> w.walletId().equals(o.walletId())
                    || (w.councilId().equals(o.councilId()) && w.role() == o.role()
                            && (o.stallId() != null && o.stallId().equals(w.stallId())
                                    || o.agentId() != null && o.agentId().equals(w.agentId()))));
            if (clash) {
                return false;
            }
            all.add(o);
            return true;
        }

        @Override
        public Optional<WalletOwner> find(String walletId) {
            return all.stream().filter(w -> w.walletId().equals(walletId)).findFirst();
        }

        @Override
        public Optional<WalletOwner> ofStall(UUID councilId, UUID stallId, WalletOwner.Role role) {
            return all.stream().filter(w -> w.councilId().equals(councilId) && w.role() == role
                    && stallId.equals(w.stallId())).findFirst();
        }

        @Override
        public Optional<WalletOwner> ofAgent(UUID councilId, UUID agentId) {
            return all.stream().filter(w -> w.councilId().equals(councilId) && agentId.equals(w.agentId())).findFirst();
        }

        @Override
        public Optional<WalletOwner> ofCouncil(UUID councilId) {
            return all.stream().filter(w -> w.councilId().equals(councilId) && w.role() == WalletOwner.Role.COUNCIL)
                    .findFirst();
        }

        @Override
        public List<WalletOwner> list(UUID councilId) {
            return all.stream().filter(w -> w.councilId().equals(councilId)).toList();
        }
    }

    public static final class Agents implements AgentRepository {
        public final List<Agent> all = new ArrayList<>();

        @Override
        public void add(Agent agent) {
            all.add(agent);
        }

        @Override
        public List<Agent> list(UUID councilId) {
            return all.stream().filter(a -> a.councilId().equals(councilId)).toList();
        }

        @Override
        public Optional<Agent> find(UUID councilId, UUID agentId) {
            return all.stream().filter(a -> a.councilId().equals(councilId) && a.id().equals(agentId)).findFirst();
        }

        @Override
        public boolean existsByPhone(UUID councilId, PhoneNumber phone) {
            return all.stream().anyMatch(a -> a.councilId().equals(councilId) && a.phone().equals(phone));
        }
    }
}
