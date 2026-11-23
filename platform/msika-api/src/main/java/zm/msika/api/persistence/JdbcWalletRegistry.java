package zm.msika.api.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import zm.msika.core.wallet.WalletOwner;
import zm.msika.core.wallet.WalletRegistry;

/** Keeps the registry of payments-system wallets in Postgres. */
@Repository
class JdbcWalletRegistry implements WalletRegistry {

    private final JdbcClient jdbc;

    JdbcWalletRegistry(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean register(WalletOwner o) {
        return jdbc.sql("insert into payments_wallet (wallet_id, council_id, role, stall_id, agent_id) "
                        + "values (:walletId, :councilId, :role, :stallId, :agentId) on conflict do nothing")
                .param("walletId", o.walletId())
                .param("councilId", o.councilId())
                .param("role", o.role().name())
                .param("stallId", o.stallId())
                .param("agentId", o.agentId())
                .update() == 1;
    }

    /** Looks across councils, through the narrow database function (see migration V8), not through the table. */
    @Override
    public Optional<WalletOwner> find(String walletId) {
        return jdbc.sql("select wallet_id, council_id, role, stall_id, agent_id from payments_find_wallet(:walletId)")
                .param("walletId", walletId)
                .query((rs, row) -> map(rs)).optional();
    }

    @Override
    public Optional<WalletOwner> ofStall(UUID councilId, UUID stallId, WalletOwner.Role role) {
        return jdbc.sql("select wallet_id, council_id, role, stall_id, agent_id from payments_wallet "
                        + "where council_id = :councilId and stall_id = :stallId and role = :role")
                .param("councilId", councilId).param("stallId", stallId).param("role", role.name())
                .query((rs, row) -> map(rs)).optional();
    }

    @Override
    public Optional<WalletOwner> ofAgent(UUID councilId, UUID agentId) {
        return jdbc.sql("select wallet_id, council_id, role, stall_id, agent_id from payments_wallet "
                        + "where council_id = :councilId and agent_id = :agentId")
                .param("councilId", councilId).param("agentId", agentId)
                .query((rs, row) -> map(rs)).optional();
    }

    @Override
    public Optional<WalletOwner> ofCouncil(UUID councilId) {
        return jdbc.sql("select wallet_id, council_id, role, stall_id, agent_id from payments_wallet "
                        + "where council_id = :councilId and role = 'COUNCIL'")
                .param("councilId", councilId)
                .query((rs, row) -> map(rs)).optional();
    }

    @Override
    public List<WalletOwner> list(UUID councilId) {
        return jdbc.sql("select wallet_id, council_id, role, stall_id, agent_id from payments_wallet "
                        + "where council_id = :councilId order by role, wallet_id")
                .param("councilId", councilId)
                .query((rs, row) -> map(rs)).list();
    }

    private static WalletOwner map(ResultSet rs) throws SQLException {
        return new WalletOwner(
                rs.getString("wallet_id"),
                rs.getObject("council_id", UUID.class),
                WalletOwner.Role.valueOf(rs.getString("role")),
                rs.getObject("stall_id", UUID.class),
                rs.getObject("agent_id", UUID.class));
    }
}
