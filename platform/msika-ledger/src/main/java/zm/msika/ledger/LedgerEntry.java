package zm.msika.ledger;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * One immutable line of a council's ledger.
 *
 * <p>An entry is never updated or deleted (AGENTS.md rule 2). A mistake is fixed by a new
 * {@link EntryKind#REVERSAL} entry that points at the one it cancels, with the opposite amount.
 *
 * <p>The constructor checks the rules, so an invalid entry cannot exist:
 *
 * <ul>
 *   <li>amounts are in ZMW, as whole ngwee (rule 1), and never zero
 *   <li>every entry except a reversal has a positive amount and reverses nothing
 *   <li>a reversal has a negative amount and names the entry it reverses
 *   <li>the provenance matches the kind, so a {@code DECLARED_SALE} can never be {@code VERIFIED}
 *       (rule 3)
 * </ul>
 *
 * <p>Whether an entry's idempotency key has been used before, and whether a reversal really cancels
 * the entry it names, depend on other entries. They are enforced by the {@code RecordEntry} use
 * case, the only code that writes the ledger.
 *
 * @param id the entry number within the ledger, assigned in order when it is recorded
 * @param councilId the council whose ledger this is
 * @param stallId the stall the entry is recorded against
 * @param kind what the entry records
 * @param provenance how sure we are about it
 * @param amount a positive amount, or a negative one for a reversal; always ZMW
 * @param businessDate the Africa/Lusaka trading date the entry belongs to
 * @param occurredAt when the underlying event happened
 * @param idempotencyKey a key unique within the council, so processing the same input twice records
 *     one entry (rule 5)
 * @param reversesEntryId for a reversal, the id of the entry it cancels; otherwise {@code null}
 */
public record LedgerEntry(
        long id,
        UUID councilId,
        UUID stallId,
        EntryKind kind,
        Provenance provenance,
        Money amount,
        LocalDate businessDate,
        Instant occurredAt,
        String idempotencyKey,
        Long reversesEntryId) {

    /** The longest idempotency key accepted. */
    public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 200;

    /** Validates on construction, so an invalid entry cannot exist. */
    public LedgerEntry {
        Objects.requireNonNull(councilId, "councilId must not be null");
        Objects.requireNonNull(stallId, "stallId must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(provenance, "provenance must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(businessDate, "businessDate must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");

        if (id <= 0) {
            throw new IllegalArgumentException("id must be positive, but was " + id);
        }
        if (amount.currency() != Currency.ZMW) {
            throw new IllegalArgumentException(
                    "amount must be in ZMW, but was " + amount.currency().alphabeticCode());
        }
        if (idempotencyKey.isBlank() || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "idempotencyKey must be 1 to " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
        }

        if (kind == EntryKind.REVERSAL) {
            if (reversesEntryId == null) {
                throw new IllegalArgumentException("a reversal must name the entry it reverses");
            }
            if (reversesEntryId <= 0 || reversesEntryId == id) {
                throw new IllegalArgumentException(
                        "a reversal must name a different, positive entry id, but named "
                                + reversesEntryId);
            }
            if (!amount.isNegative()) {
                throw new IllegalArgumentException("a reversal must have a negative amount");
            }
        } else {
            if (reversesEntryId != null) {
                throw new IllegalArgumentException("only a reversal may name an entry it reverses");
            }
            if (!amount.isPositive()) {
                throw new IllegalArgumentException(kind + " must have a positive amount");
            }
        }

        kind.requiredProvenance().ifPresent(required -> {
            if (provenance != required) {
                throw new IllegalArgumentException(
                        kind + " must have provenance " + required + ", but was " + provenance);
            }
        });
    }

    /**
     * Builds the reversal of {@code original}: the opposite amount, the same council, stall and
     * provenance, pointing at the original. The original is not touched.
     *
     * @param id the new entry's id, which must differ from the original's
     * @param businessDate the trading date the reversal belongs to (the day it is made, not the
     *     original's day, because a sealed day is never reopened)
     */
    public static LedgerEntry reversalOf(
            LedgerEntry original,
            long id,
            LocalDate businessDate,
            Instant occurredAt,
            String idempotencyKey) {
        Objects.requireNonNull(original, "original must not be null");
        if (original.kind() == EntryKind.REVERSAL) {
            throw new IllegalArgumentException("a reversal cannot itself be reversed");
        }
        return new LedgerEntry(
                id,
                original.councilId(),
                original.stallId(),
                EntryKind.REVERSAL,
                original.provenance(),
                original.amount().negate(),
                businessDate,
                occurredAt,
                idempotencyKey,
                original.id());
    }

    /** The id of the entry this one reverses, if it is a reversal. */
    public OptionalLong reverses() {
        return reversesEntryId == null ? OptionalLong.empty() : OptionalLong.of(reversesEntryId);
    }

    /**
     * The exact bytes that go into the daily seal for this entry.
     *
     * <p>Every field is encoded in a fixed order. Text is length-prefixed so two different entries
     * can never encode to the same bytes. Kind and provenance are encoded by name, not by position,
     * so reordering an enum later cannot change the bytes of an entry that was sealed years ago.
     */
    public byte[] canonicalBytes() {
        byte[] kindName = kind.name().getBytes(StandardCharsets.UTF_8);
        byte[] provenanceName = provenance.name().getBytes(StandardCharsets.UTF_8);
        byte[] currencyCode = amount.currency().alphabeticCode().getBytes(StandardCharsets.UTF_8);
        byte[] key = idempotencyKey.getBytes(StandardCharsets.UTF_8);

        ByteBuffer buffer = ByteBuffer.allocate(
                Long.BYTES
                        + 2 * Long.BYTES
                        + 2 * Long.BYTES
                        + Integer.BYTES + kindName.length
                        + Integer.BYTES + provenanceName.length
                        + Long.BYTES
                        + Integer.BYTES + currencyCode.length
                        + Long.BYTES
                        + Long.BYTES + Integer.BYTES
                        + Integer.BYTES + key.length
                        + 1 + Long.BYTES);
        buffer.putLong(id);
        buffer.putLong(councilId.getMostSignificantBits()).putLong(councilId.getLeastSignificantBits());
        buffer.putLong(stallId.getMostSignificantBits()).putLong(stallId.getLeastSignificantBits());
        buffer.putInt(kindName.length).put(kindName);
        buffer.putInt(provenanceName.length).put(provenanceName);
        buffer.putLong(amount.minorUnits());
        buffer.putInt(currencyCode.length).put(currencyCode);
        buffer.putLong(businessDate.toEpochDay());
        buffer.putLong(occurredAt.getEpochSecond()).putInt(occurredAt.getNano());
        buffer.putInt(key.length).put(key);
        buffer.put((byte) (reversesEntryId == null ? 0 : 1));
        buffer.putLong(reversesEntryId == null ? 0L : reversesEntryId);
        return buffer.array();
    }
}
