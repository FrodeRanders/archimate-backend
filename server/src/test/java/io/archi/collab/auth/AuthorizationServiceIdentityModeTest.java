package io.archi.collab.auth;

import io.archi.collab.endpoint.CollaborationEndpointConfigurator;
import jakarta.ws.rs.core.SecurityContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.security.Principal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

class AuthorizationServiceIdentityModeTest {

    @Test
    void oidcRestSubjectUsesSecurityContextPrincipalAndConfiguredRoles() {
        AuthorizationService service = new AuthorizationService();
        service.identityModeRaw = "oidc";
        service.adminRole = "admin";
        service.readerRole = "model_reader";
        service.writerRole = "model_writer";

        SecurityContext securityContext = new SecurityContext() {
            @Override
            public Principal getUserPrincipal() {
                return () -> "oidc-user";
            }

            @Override
            public boolean isUserInRole(String role) {
                return "admin".equals(role) || "model_writer".equals(role);
            }

            @Override
            public boolean isSecure() {
                return true;
            }

            @Override
            public String getAuthenticationScheme() {
                return "OIDC";
            }
        };

        AuthorizationSubject subject = service.currentRestSubject(null, securityContext);
        Assertions.assertEquals("oidc-user", subject.userId());
        Assertions.assertEquals(Set.of("admin", "model_writer"), subject.roles());
    }

    @Test
    void oidcWebSocketSubjectUsesHandshakePrincipalAndRoles() {
        AuthorizationService service = new AuthorizationService();
        service.identityModeRaw = "oidc";

        Map<String, Object> userProperties = new HashMap<>();
        userProperties.put(CollaborationEndpointConfigurator.HANDSHAKE_PRINCIPAL_NAME_KEY, "ws-user");
        userProperties.put(CollaborationEndpointConfigurator.HANDSHAKE_ROLES_KEY, Set.of("admin", "model_reader"));

        jakarta.websocket.Session session = (jakarta.websocket.Session) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{jakarta.websocket.Session.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUserPrincipal" -> null;
                    case "getUserProperties" -> userProperties;
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        AuthorizationSubject subject = service.currentWebSocketSubject(session);
        Assertions.assertEquals("ws-user", subject.userId());
        Assertions.assertEquals(Set.of("admin", "model_reader"), subject.roles());
    }
}
