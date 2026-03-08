package io.archi.collab.auth;

import io.archi.collab.model.ModelAccessControl;
import io.archi.collab.endpoint.CollaborationEndpointConfigurator;
import io.archi.collab.service.CollaborationService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.websocket.Session;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class AuthorizationService {
    public static final String USER_HEADER = "X-Collab-User";
    public static final String ROLES_HEADER = "X-Collab-Roles";
    public static final String PROXY_USER_HEADER_DEFAULT = "X-Forwarded-User";
    public static final String PROXY_ROLES_HEADER_DEFAULT = "X-Forwarded-Roles";
    public static final String WS_USER_PARAM = "user";
    public static final String WS_ROLES_PARAM = "roles";

    @Inject
    PolicyDecisionPoint policyDecisionPoint;

    @Inject
    CollaborationService collaborationService;

    @ConfigProperty(name = "app.authz.enabled", defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = "app.identity.mode", defaultValue = "bootstrap")
    String identityModeRaw;

    @ConfigProperty(name = "app.identity.proxy.user-header", defaultValue = PROXY_USER_HEADER_DEFAULT)
    String proxyUserHeader;

    @ConfigProperty(name = "app.identity.proxy.roles-header", defaultValue = PROXY_ROLES_HEADER_DEFAULT)
    String proxyRolesHeader;

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
        return switch (identityMode()) {
            case BOOTSTRAP -> subjectFromHeaderNames(headers, USER_HEADER, ROLES_HEADER);
            case PROXY -> subjectFromHeaderNames(headers, proxyUserHeader, proxyRolesHeader);
        };
    }

    public AuthorizationSubject currentWebSocketSubject(Session session) {
        return switch (identityMode()) {
            case BOOTSTRAP -> subjectFromBootstrapWebSocket(session);
            case PROXY -> subjectFromProxyWebSocket(session);
        };
    }

    private ModelAccessControl lookupAccessControl(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return null;
        }
        return collaborationService.findModelAccessControl(modelId).orElse(null);
    }

    private IdentityMode identityMode() {
        return IdentityMode.fromConfig(identityModeRaw);
    }

    private AuthorizationSubject subjectFromHeaderNames(HttpHeaders headers, String userHeader, String rolesHeader) {
        if (headers == null) {
            return new AuthorizationSubject("", Set.of());
        }
        return new AuthorizationSubject(
                trim(headers.getHeaderString(userHeader)),
                parseRoles(headers.getHeaderString(rolesHeader)));
    }

    private AuthorizationSubject subjectFromBootstrapWebSocket(Session session) {
        if (session == null) {
            return new AuthorizationSubject("", Set.of());
        }
        Map<String, java.util.List<String>> params = session.getRequestParameterMap();
        return new AuthorizationSubject(
                trim(first(params, WS_USER_PARAM)),
                parseRoles(first(params, WS_ROLES_PARAM)));
    }

    private AuthorizationSubject subjectFromProxyWebSocket(Session session) {
        if (session == null) {
            return new AuthorizationSubject("", Set.of());
        }
        Map<String, java.util.List<String>> headers = handshakeHeaders(session);
        return new AuthorizationSubject(
                trim(first(headers, proxyUserHeader)),
                parseRoles(first(headers, proxyRolesHeader)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, java.util.List<String>> handshakeHeaders(Session session) {
        Object raw = session.getUserProperties().get(CollaborationEndpointConfigurator.HANDSHAKE_HEADERS_KEY);
        if (raw instanceof Map<?, ?> map) {
            Map<String, java.util.List<String>> headers = new HashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null || !(entry.getValue() instanceof java.util.List<?> values)) {
                    continue;
                }
                java.util.List<String> strings = values.stream()
                        .filter(v -> v != null)
                        .map(Object::toString)
                        .toList();
                headers.put(entry.getKey().toString(), strings);
            }
            return headers;
        }
        return Map.of();
    }

    private String first(Map<String, java.util.List<String>> params, String key) {
        if (params == null) {
            return "";
        }
        java.util.List<String> values = params.get(key);
        if ((values == null || values.isEmpty()) && key != null) {
            values = params.get(key.toLowerCase(Locale.ROOT));
        }
        if ((values == null || values.isEmpty()) && key != null) {
            values = params.get(key.toUpperCase(Locale.ROOT));
        }
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
