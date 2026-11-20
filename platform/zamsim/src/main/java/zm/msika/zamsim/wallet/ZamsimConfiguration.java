package zm.msika.zamsim.wallet;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ZamsimConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
