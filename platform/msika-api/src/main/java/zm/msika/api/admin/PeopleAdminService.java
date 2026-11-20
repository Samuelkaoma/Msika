package zm.msika.api.admin;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zm.msika.api.security.CurrentUser;
import zm.msika.api.tenancy.TenantContext;
import zm.msika.core.agent.Agent;
import zm.msika.core.agent.AgentRepository;
import zm.msika.core.agent.RegisterAgent;
import zm.msika.core.audit.AuditLog;
import zm.msika.core.common.NotFoundException;
import zm.msika.core.council.CouncilRepository;
import zm.msika.core.plate.GenerateMarketPlates;
import zm.msika.core.trader.RegisterTrader;
import zm.msika.core.trader.Trader;
import zm.msika.core.trader.TraderRepository;

/**
 * Registers traders and agents, lists them, and makes a market's plates. Each change is in one
 * transaction with its audit row.
 *
 * <p>Phone numbers are personal data. Log lines here print a {@code PhoneNumber} through its
 * {@code toString()}, which is masked, and the audit log records ids, never numbers.
 */
@Service
@Transactional
class PeopleAdminService {

    private static final Logger LOG = LoggerFactory.getLogger(PeopleAdminService.class);

    private final RegisterTrader registerTrader;
    private final RegisterAgent registerAgent;
    private final GenerateMarketPlates generatePlates;
    private final TraderRepository traders;
    private final AgentRepository agents;
    private final CouncilRepository councils;
    private final TenantContext tenant;
    private final AuditLog audit;

    PeopleAdminService(
            RegisterTrader registerTrader,
            RegisterAgent registerAgent,
            GenerateMarketPlates generatePlates,
            TraderRepository traders,
            AgentRepository agents,
            CouncilRepository councils,
            TenantContext tenant,
            AuditLog audit) {
        this.registerTrader = registerTrader;
        this.registerAgent = registerAgent;
        this.generatePlates = generatePlates;
        this.traders = traders;
        this.agents = agents;
        this.councils = councils;
        this.tenant = tenant;
        this.audit = audit;
    }

    Trader registerTrader(UUID councilId, String phone, String displayName, String language) {
        tenant.use(councilId);
        Trader trader = registerTrader.execute(councilId, phone, displayName, language);
        audit.record(councilId, CurrentUser.actor(), "trader.register", trader.id().toString());
        LOG.info("registered trader {} with phone {}", trader.id(), trader.phone());
        return trader;
    }

    @Transactional(readOnly = true)
    List<Trader> listTraders(UUID councilId) {
        tenant.use(councilId);
        councils.find(councilId).orElseThrow(() -> new NotFoundException("council not found"));
        return traders.list(councilId);
    }

    Agent registerAgent(UUID councilId, String phone, String displayName) {
        tenant.use(councilId);
        Agent agent = registerAgent.execute(councilId, phone, displayName);
        audit.record(councilId, CurrentUser.actor(), "agent.register", agent.id().toString());
        LOG.info("registered agent {} with phone {}", agent.id(), agent.phone());
        return agent;
    }

    @Transactional(readOnly = true)
    List<Agent> listAgents(UUID councilId) {
        tenant.use(councilId);
        councils.find(councilId).orElseThrow(() -> new NotFoundException("council not found"));
        return agents.list(councilId);
    }

    @Transactional(readOnly = true)
    byte[] plates(UUID councilId, UUID marketId) {
        tenant.use(councilId);
        return generatePlates.execute(councilId, marketId);
    }
}
