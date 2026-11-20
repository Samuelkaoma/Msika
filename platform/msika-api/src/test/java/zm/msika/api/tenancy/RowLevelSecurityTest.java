package zm.msika.api.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import zm.msika.api.PostgresIntegrationTest;

/**
 * AGENTS.md rule 4: a council sees only its own rows, enforced by Postgres, so a forgotten WHERE
 * cannot leak data. Every query here is written WITHOUT a council filter on purpose.
 */
class RowLevelSecurityTest extends PostgresIntegrationTest {

    /** Every table that holds council data. The coverage test below proves the list is complete. */
    private static final List<String> COUNCIL_TABLES = List.of("market", "trader", "stall", "stall_allocation");

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TenantContext tenant;

    @Autowired
    TransactionTemplate transaction;

    private UUID councilA;
    private UUID councilB;

    @BeforeEach
    void twoCouncilsWithData() {
        councilA = seedCouncil("A");
        councilB = seedCouncil("B");
    }

    /** A council with one market, two traders, three stalls and one allocation. */
    private UUID seedCouncil(String label) {
        UUID council = UUID.randomUUID();
        jdbc.sql("insert into council (id, name, timezone, created_at) values (:id, :name, 'Africa/Lusaka', now())")
                .param("id", council)
                .param("name", "Council " + label + " " + council)
                .update();
        tenant.inCouncil(council, () -> {
            UUID market = UUID.randomUUID();
            jdbc.sql("insert into market (id, council_id, code, name, day_close_time, created_at) "
                            + "values (:id, :council, 'TST', 'Test Market', '20:00', now())")
                    .param("id", market).param("council", council).update();
            UUID firstTrader = null;
            for (int i = 1; i <= 2; i++) {
                UUID trader = UUID.randomUUID();
                firstTrader = firstTrader == null ? trader : firstTrader;
                jdbc.sql("insert into trader (id, council_id, msisdn, display_name) "
                                + "values (:id, :council, :msisdn, 'Test Trader')")
                        .param("id", trader).param("council", council)
                        .param("msisdn", "+260977" + String.format("%06d", Math.abs(trader.hashCode() % 1_000_000)))
                        .update();
            }
            UUID firstStall = null;
            for (int i = 1; i <= 3; i++) {
                UUID stall = UUID.randomUUID();
                firstStall = firstStall == null ? stall : firstStall;
                jdbc.sql("insert into stall (id, council_id, market_id, code, stall_class, status, qr_token) "
                                + "values (:id, :council, :market, :code, 'STANDARD', 'VACANT', :token)")
                        .param("id", stall).param("council", council).param("market", market)
                        .param("code", "TST-A-" + i)
                        .param("token", UUID.randomUUID().toString().replace("-", ""))
                        .update();
            }
            jdbc.sql("insert into stall_allocation (id, council_id, stall_id, trader_id, valid_from) "
                            + "values (:id, :council, :stall, :trader, now())")
                    .param("id", UUID.randomUUID()).param("council", council)
                    .param("stall", firstStall).param("trader", firstTrader).update();
        });
        return council;
    }

    /** The distinct council_id values a query with NO WHERE clause can see in a table. */
    private Set<UUID> councilsVisibleIn(String table) {
        return new HashSet<>(jdbc.sql("select council_id from " + table).query(UUID.class).list());
    }

    private int countIn(String table) {
        return jdbc.sql("select count(*) from " + table).query(Integer.class).single();
    }

    @Test
    @DisplayName("a council A user running a query with no WHERE sees none of council B's rows")
    void userOfCouncilASeesOnlyCouncilA() {
        tenant.inCouncil(councilA, () -> {
            for (String table : COUNCIL_TABLES) {
                assertThat(councilsVisibleIn(table)).as(table).containsExactly(councilA);
            }
            assertThat(countIn("market")).isEqualTo(1);
            assertThat(countIn("trader")).isEqualTo(2);
            assertThat(countIn("stall")).isEqualTo(3);
            assertThat(countIn("stall_allocation")).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("a join with no WHERE still cannot reach another council's rows")
    void joinsAreFilteredToo() {
        tenant.inCouncil(councilA, () -> {
            List<UUID> councils = jdbc.sql("select s.council_id from stall s join market m on m.id = s.market_id")
                    .query(UUID.class).list();

            assertThat(councils).hasSize(3).containsOnly(councilA);
        });
    }

    @Test
    @DisplayName("a job for council A sees only council A, and the job for council B only council B")
    void jobsSeeOneCouncilAtATime() {
        Map<UUID, Set<UUID>> seenByJob = new HashMap<>();
        List<UUID> visited = new ArrayList<>();

        tenant.forEachCouncil(council -> {
            visited.add(council);
            seenByJob.put(council, councilsVisibleIn("stall"));
        });

        assertThat(visited).contains(councilA, councilB);
        assertThat(seenByJob.get(councilA)).containsExactly(councilA);
        assertThat(seenByJob.get(councilB)).containsExactly(councilB);
        seenByJob.forEach((council, seen) -> assertThat(seen).as("job for " + council).isSubsetOf(council));
    }

    @Test
    @DisplayName("with no council set, a query sees nothing at all")
    void noContextSeesNothing() {
        Integer inTransaction = transaction.execute(status -> countIn("stall"));
        assertThat(inTransaction).isZero();
        assertThat(countIn("stall")).as("not even in a plain autocommit query").isZero();
    }

    @Test
    @DisplayName("the council does not leak to the next request that reuses the same connection")
    void contextDoesNotLeakBetweenTransactions() {
        for (int i = 0; i < 20; i++) {
            tenant.inCouncil(i % 2 == 0 ? councilA : councilB, () -> assertThat(countIn("stall")).isEqualTo(3));
            Integer later = transaction.execute(status -> countIn("stall"));
            assertThat(later)
                    .as("a later request with no council set (round %d)", i).isZero();
        }
    }

    @Test
    @DisplayName("council A cannot write a row into council B")
    void cannotInsertIntoAnotherCouncil() {
        assertThatThrownBy(() -> tenant.inCouncil(councilA, () -> jdbc
                .sql("insert into market (id, council_id, code, name, day_close_time, created_at) "
                        + "values (:id, :council, 'EVL', 'Evil Market', '20:00', now())")
                .param("id", UUID.randomUUID()).param("council", councilB).update()))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("new row violates row-level security policy for table \"market\"");
    }

    @Test
    @DisplayName("council A cannot move one of its rows into council B")
    void cannotUpdateARowIntoAnotherCouncil() {
        assertThatThrownBy(() -> tenant.inCouncil(councilA, () -> jdbc
                .sql("update stall set council_id = :other").param("other", councilB).update()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("an UPDATE or DELETE with no WHERE touches only the caller's own council")
    void writesAreConfinedToTheCouncil() {
        int suspended = tenant.inCouncil(councilA, () -> jdbc.sql("update stall set status = 'SUSPENDED'").update());
        int removed = tenant.inCouncil(councilA, () -> jdbc.sql("delete from stall_allocation").update());

        assertThat(suspended).isEqualTo(3);
        assertThat(removed).isEqualTo(1);
        tenant.inCouncil(councilB, () -> {
            assertThat(jdbc.sql("select count(*) from stall where status = 'VACANT'")
                    .query(Integer.class).single()).as("council B stalls are untouched").isEqualTo(3);
            assertThat(countIn("stall_allocation")).as("council B allocation is untouched").isEqualTo(1);
        });
    }

    @Test
    @DisplayName("setting the council needs a transaction, so it cannot silently last for one statement")
    void contextNeedsATransaction() {
        assertThatIllegalStateException().isThrownBy(() -> tenant.use(councilA));
    }

    @Test
    @DisplayName("EVERY table with a council_id column has row-level security switched on and a policy")
    void everyCouncilTableHasAPolicy() {
        List<String> tablesWithCouncilId = jdbc.sql("""
                select c.relname
                from pg_class c
                join pg_namespace n on n.oid = c.relnamespace
                join information_schema.columns col
                  on col.table_schema = n.nspname and col.table_name = c.relname and col.column_name = 'council_id'
                where n.nspname = 'public' and c.relkind = 'r'
                order by c.relname
                """).query(String.class).list();

        List<String> withoutProtection = jdbc.sql("""
                select c.relname
                from pg_class c
                join pg_namespace n on n.oid = c.relnamespace
                join information_schema.columns col
                  on col.table_schema = n.nspname and col.table_name = c.relname and col.column_name = 'council_id'
                where n.nspname = 'public' and c.relkind = 'r'
                  and (not c.relrowsecurity or not exists (select 1 from pg_policy p where p.polrelid = c.oid))
                order by c.relname
                """).query(String.class).list();

        assertThat(tablesWithCouncilId).as("the check is not vacuous").containsAll(COUNCIL_TABLES);
        assertThat(withoutProtection)
                .as("tables with a council_id but no row-level security policy. Add a policy in a new "
                        + "migration (see V3__row_level_security.sql)")
                .isEmpty();
    }
}
