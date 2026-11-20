package zm.msika.api.deposit;

import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import zm.msika.core.agent.AgentRepository;
import zm.msika.core.council.CouncilRepository;
import zm.msika.core.deposit.ChangeDeposit;
import zm.msika.core.deposit.DepositOutcomes;
import zm.msika.core.deposit.DepositRepository;
import zm.msika.core.deposit.RequestDeposit;
import zm.msika.core.payments.PaymentOutcomes;
import zm.msika.core.payments.PaymentRequestRepository;
import zm.msika.core.stall.StallAllocationRepository;
import zm.msika.core.stall.StallRepository;
import zm.msika.core.trader.TraderRepository;
import zm.msika.core.wallet.RegisterWallet;
import zm.msika.core.wallet.VerifyWalletOwnership;
import zm.msika.core.wallet.WalletRegistry;

/** Creates the deposit and wallet use cases from msika-core. */
@Configuration
class DepositConfiguration {

    @Bean
    RequestDeposit requestDeposit(CouncilRepository councils, StallRepository stalls, AgentRepository agents,
            WalletRegistry wallets, DepositRepository deposits, PaymentRequestRepository requests, Clock clock,
            @Value("${msika.deposit.max-ngwee:5000000}") long maxNgwee) {
        return new RequestDeposit(councils, stalls, agents, wallets, deposits, requests, clock, new SecureRandom(), maxNgwee);
    }

    @Bean
    ChangeDeposit changeDeposit(DepositRepository deposits, Clock clock) {
        return new ChangeDeposit(deposits, clock);
    }

    /** What the payment callbacks tell: the deposit lifecycle follows the payment request. */
    @Bean
    PaymentOutcomes paymentOutcomes(DepositRepository deposits, Clock clock) {
        return new DepositOutcomes(deposits, clock);
    }

    @Bean
    RegisterWallet registerWallet(WalletRegistry wallets, StallRepository stalls, AgentRepository agents) {
        return new RegisterWallet(wallets, stalls, agents);
    }

    @Bean
    VerifyWalletOwnership verifyWalletOwnership(StallAllocationRepository allocations, TraderRepository traders,
            AgentRepository agents) {
        return new VerifyWalletOwnership(allocations, traders, agents);
    }
}
