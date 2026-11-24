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

    @Bean
    zm.msika.core.user.ChangeRoles changeRoles(zm.msika.core.user.UserRepository users) {
        return new zm.msika.core.user.ChangeRoles(users);
    }

    /** Copies the permission matrix into the database on every start: the code is the source of truth. */
    @Bean
    org.springframework.boot.ApplicationRunner seedRolePermissions(org.springframework.jdbc.core.simple.JdbcClient jdbc,
            org.springframework.transaction.support.TransactionTemplate tx) {
        return args -> tx.executeWithoutResult(status -> {
            // One server at a time: two starting together must not both insert.
            jdbc.sql("select pg_advisory_xact_lock(6100)").query((rs, n) -> 1).list();
            jdbc.sql("delete from role_permission").update();
            for (zm.msika.core.user.Role role : zm.msika.core.user.Role.values()) {
                for (zm.msika.core.user.Permission p : zm.msika.core.user.Permissions.of(role)) {
                    jdbc.sql("insert into role_permission (role, permission) values (:r, :p)")
                            .param("r", role.name()).param("p", p.name()).update();
                }
            }
        });
    }
}
