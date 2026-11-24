package zm.msika.api.security;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import zm.msika.api.tenancy.TenantContext;
import zm.msika.core.user.Permissions;

/**
 * Checks {@link EndpointPermissions} before every controller method of ours (card M-61). The security filter has
 * already checked who the user is and that the council in the path is theirs; this checks what they may do, and in
 * which market.
 */
@Configuration
class PermissionInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    private final TenantContext tenant;
    private final JdbcClient jdbc;

    PermissionInterceptor(TenantContext tenant, JdbcClient jdbc) {
        this.tenant = tenant;
        this.jdbc = jdbc;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method) || request.getDispatcherType() != DispatcherType.REQUEST
                || !method.getBeanType().getName().startsWith("zm.msika.")) {
            return true;
        }
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String path = pattern == null ? "" : pattern.toString();
        if (EndpointPermissions.isOpen(request.getMethod(), path)) {
            return true;
        }
        EndpointPermissions.Rule rule = EndpointPermissions.find(request.getMethod(), path)
                .orElseThrow(() -> new AccessDeniedException("no permission is mapped for this endpoint"));
        MsikaPrincipal user = CurrentUser.require();
        if (!Permissions.allows(user.grants(), rule.permission(), market(rule, request))) {
            throw new AccessDeniedException("not allowed");
        }
        return true;
    }

    /** The market the action is in, or null if it has none or the thing named does not exist in the council. */
    @SuppressWarnings("unchecked")
    private UUID market(EndpointPermissions.Rule rule, HttpServletRequest request) {
        if (rule.market() == EndpointPermissions.MarketFrom.NONE) {
            return null;
        }
        Map<String, String> vars = (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        try {
            UUID council = UUID.fromString(vars.get("councilId"));
            return switch (rule.market()) {
                case NONE -> null;
                case PATH -> UUID.fromString(vars.get("marketId"));
                case STALL -> find(council, "select market_id from stall where council_id = :c and id = :id", vars.get("stallId"));
                case PLAN -> find(council, "select market_id from market_plan where council_id = :c and id = :id", vars.get("planId"));
                case DEPOSIT -> find(council, "select s.market_id from deposit d join stall s on s.id = d.stall_id "
                        + "where d.council_id = :c and d.id = :id", vars.get("id"));
                case REGISTRATION -> find(council, "select s.market_id from registration_request r join stall s on s.id = r.stall_id "
                        + "where r.council_id = :c and r.id = :id", vars.get("id"));
            };
        } catch (IllegalArgumentException e) {
            return null; // not an id: the controller answers 400 to anyone allowed this far
        }
    }

    private UUID find(UUID council, String sql, String id) {
        UUID wanted = UUID.fromString(id);
        return tenant.inCouncil(council, () -> jdbc.sql(sql).param("c", council).param("id", wanted).query(UUID.class)
                .optional()).orElse(null);
    }
}
