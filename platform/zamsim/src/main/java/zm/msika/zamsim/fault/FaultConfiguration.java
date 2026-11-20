package zm.msika.zamsim.fault;

import java.util.random.RandomGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class FaultConfiguration {

    @Bean
    RandomGenerator randomGenerator() {
        return RandomGenerator.getDefault();
    }
}
