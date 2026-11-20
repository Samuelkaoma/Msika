package zm.msika.api.security;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A test-only endpoint that nobody wrote a security rule for. If default-deny works, nobody can call
 * it, whoever they are. It exists only in test sources and is never part of the real app.
 */
@RestController
class NoRuleTestEndpoint {

    @GetMapping("/api/v1/internal/no-rule")
    String reachable() {
        return "you should never see this";
    }
}
