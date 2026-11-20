package zm.msika.api.seal;

import java.security.PublicKey;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import zm.msika.api.ledger.LedgerRows;
import zm.msika.api.ledger.SealRows;
import zm.msika.core.ledger.SealedDay;
import zm.msika.ledger.DailySeal;
import zm.msika.ledger.LedgerEntry;
import zm.msika.ledger.SignatureScheme;

/**
 * The independent check on the ledger (layer 3 of migration V6). It reads the raw rows, rebuilds every
 * sealed day's Merkle tree, and checks each seal's signature, its link to the day before, and that no day
 * has an entry the seal does not cover. If anyone changed, removed, added or reordered an entry, or
 * rewrote or removed a seal, even by switching off the database triggers, this finds it.
 *
 * <p>It needs a connection that can read every council, which row-level security otherwise forbids: use
 * the owner role, not the application role. Run it from {@code scripts/verify-seal}.
 */
public final class SealVerifier {

    /** What the check found. {@link #ok()} is true only if there are no problems. */
    public record Report(int councils, int seals, int entries, List<String> problems) {
        public boolean ok() {
            return problems.isEmpty();
        }
    }

    private final JdbcClient jdbc;
    private final SignatureScheme scheme;

    public SealVerifier(JdbcClient jdbc, SignatureScheme scheme) {
        this.jdbc = jdbc;
        this.scheme = scheme;
    }

    /**
     * @param onlyCouncil check just this council, or {@code null} for every council that has a seal
     */
    public Report verify(PublicKey publicKey, UUID onlyCouncil) {
        List<UUID> councils = onlyCouncil != null
                ? List.of(onlyCouncil)
                : jdbc.sql("select distinct council_id from daily_seal order by council_id").query(UUID.class).list();

        List<String> problems = new ArrayList<>();
        int sealCount = 0;
        int entryCount = 0;
        for (UUID council : councils) {
            List<SealedDay> sealed = jdbc.sql("select " + SealRows.COLUMNS
                            + " from daily_seal where council_id = :council order by business_date")
                    .param("council", council)
                    .query((rs, row) -> SealRows.map(rs))
                    .list();
            sealCount += sealed.size();
            entryCount += checkCouncil(council, sealed, publicKey, problems);
        }
        return new Report(councils.size(), sealCount, entryCount, problems);
    }

    /** Returns how many entries it checked. */
    private int checkCouncil(UUID council, List<SealedDay> sealed, PublicKey publicKey, List<String> problems) {
        if (sealed.isEmpty()) {
            return 0;
        }
        String who = "council " + council + ": ";

        DailySeal.Verification chain = DailySeal.verifyChain(
                sealed.stream().map(SealedDay::seal).toList(), publicKey, scheme);
        if (!chain.valid()) {
            problems.add(who + chain.problem());
        }

        LocalDate expectedDay = null;
        for (SealedDay day : sealed) {
            LocalDate date = day.seal().businessDate();
            if (expectedDay != null && !date.equals(expectedDay)) {
                problems.add(who + "there is no seal for " + expectedDay + " (the next seal is " + date + ")");
            }
            expectedDay = date.plusDays(1);
        }

        LocalDate firstSealed = sealed.get(0).seal().businessDate();
        Integer beforeFirst = jdbc.sql("select count(*) from ledger_entry where council_id = :council and business_date < :first")
                .param("council", council).param("first", firstSealed).query(Integer.class).single();
        if (beforeFirst > 0) {
            problems.add(who + beforeFirst + " entries are dated before the first seal (" + firstSealed + ")");
        }

        int checked = 0;
        byte[] previousHash = DailySeal.GENESIS_HASH;
        for (SealedDay day : sealed) {
            DailySeal seal = day.seal();
            List<LedgerEntry> entries = jdbc.sql("select " + LedgerRows.COLUMNS + " from ledger_entry "
                            + "where council_id = :council and business_date = :date order by id")
                    .param("council", council)
                    .param("date", seal.businessDate())
                    .query((rs, row) -> LedgerRows.map(rs))
                    .list();
            checked += entries.size();
            DailySeal.Verification result = seal.verify(entries, previousHash, publicKey, scheme);
            if (!result.valid()) {
                problems.add(who + seal.businessDate() + ": " + result.problem());
            }
            previousHash = seal.sealHash();
        }
        return checked;
    }
}
