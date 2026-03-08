package io.archi.collab.auth;

import io.archi.collab.model.ModelAccessControl;
import io.archi.collab.service.CollaborationService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.websocket.Session;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class AuthorizationService {
    public static final String USER_HEADER = "X-Collab-User";
    public static final String ROLES_HEADER = "X-Collab-Roles";
    public static final String WS_USER_PARAM = "user";
    public static final String WS_ROLES_PARAM = "roles";

    @Inject
    PolicyDecisionPoint policyDecisionPoint;

    @Inject
    CollaborationService collaborationService;

    @ConfigProperty(name = "app.authz.enabled", defaultValue = "false")
    boolean enabled;

    public void requireRestAllowed(HttpHeaders headers, AuthorizationAction action, String modelId, String ref) {
        if (!enabled) {
            return;
        }
        AuthorizationDecision decision = policyDecisionPoint.decide(new AuthorizationRequest(
                currentRestSubject(headers),
                action,
                modelId,
                ref,
                lookupAccessControl(modelId),
                AuthorizationTransport.REST));
        if (!decision.allowed()) {
            throw new WebApplicationException(decision.reason(), Response.Status.FORBIDDEN);
        }
    }

    public void requireWebSocketAllowed(Session session, AuthorizationAction action, String modelId, String ref) {
        if (!enabled) {
            return;
        }
        AuthorizationDecision decision = policyDecisionPoint.decide(new AuthorizationRequest(
                currentWebSocketSubject(session),
                action,
                modelId,
                ref,
                lookupAccessControl(modelId),
                AuthorizationTransport.WEBSOCKET));
        if (!decision.allowed()) {
            throw new AuthorizationDeniedException(decision.code(), decision.reason());
        }
    }

    public AuthorizationSubject currentRestSubject(HttpHeaders headers) {
        if (headers == null) {
            return new AuthorizationSubject("", Set.of());
        }
        return new AuthorizationSubject(
                trim(headers.getHeaderString(USER_HEADER)),
                parseRoles(headers.getHeaderString(ROLES_HEADER)));
    }

    public AuthorizationSubject currentWebSocketSubject(Session session) {
        if (session == null) {
            return new AuthorizationSubject("", Set.of());
        }
        Map<String, java.util.List<String>> params = session.getRequestParameterMap();
        return new AuthorizationSubject(
                trim(first(params, WS_USER_PARAM)),
                parseRoles(first(params, WS_ROLES_PARAM)));
    }

    private ModelAccessControl lookupAccessControl(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return null;
        }
        return collaborationService.findModelAccessControl(modelId).orElse(null);
    }

    private String first(Map<String, java.util.List<String>> params, String key) {
        if (params == null) {
            return "";
        }
        java.util.List<String> values = params.get(key);
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.getFirst();
    }

    private Set<String> parseRoles(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<String> roles = new LinkedHashSet<>();
        Arrays.stream(raw.split(","))
                .map(this::trim)
                .filter(v -> !v.isBlank())
                .map(v -> v.toLowerCase(Locale.ROOT))
                .forEach(roles::add);
        return Set.copyOf(roles);
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
