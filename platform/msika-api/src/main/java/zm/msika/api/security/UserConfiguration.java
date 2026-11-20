package zm.msika.api.security;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import zm.msika.core.council.CouncilRepository;
import zm.msika.core.user.Authenticate;
import zm.msika.core.user.CreateUser;
import zm.msika.core.user.PasswordHasher;
import zm.msika.core.user.SetUserActive;
import zm.msika.core.user.UserRepository;

/** Creates the user use cases from msika-core, which knows nothing about Spring. */
@Configuration
class UserConfiguration {

    @Bean
    Authenticate authenticate(UserRepository users, PasswordHasher hasher) {
        return new Authenticate(users, hasher);
    }

    @Bean
    CreateUser createUser(CouncilRepository councils, UserRepository users, PasswordHasher hasher, Clock clock) {
        return new CreateUser(councils, users, hasher, clock);
    }

    @Bean
    SetUserActive setUserActive(UserRepository users) {
        return new SetUserActive(users);
    }
}
