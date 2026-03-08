package io.archi.collab.endpoint;

import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpointConfig;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Set;

class CollaborationEndpointConfiguratorTest {

    @Test
    void capturesPrincipalAndConfiguredRolesFromHandshake() {
        CollaborationEndpointConfigurator configurator = new CollaborationEndpointConfigurator();
        ServerEndpointConfig config = ServerEndpointConfig.Builder
                .create(Object.class, "/models/{modelId}/stream")
                .build();
        HandshakeRequest request = (HandshakeRequest) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{HandshakeRequest.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getHeaders" -> Map.of("X-Forwarded-User", List.of("proxy-user"));
                    case "getUserPrincipal" -> (Principal) () -> "oidc-ws-user";
                    case "isUserInRole" -> "admin".equals(args[0]) || "model_writer".equals(args[0]);
                    case "getParameterMap" -> Map.of();
                    case "getQueryString" -> "";
                    case "getRequestURI" -> java.net.URI.create("ws://localhost/models/demo/stream");
                    case "getHttpSession" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        configurator.modifyHandshake(config, request, null);

        Assertions.assertEquals("oidc-ws-user",
                config.getUserProperties().get(CollaborationEndpointConfigurator.HANDSHAKE_PRINCIPAL_NAME_KEY));
        Assertions.assertEquals(Set.of("admin", "model_writer"),
                config.getUserProperties().get(CollaborationEndpointConfigurator.HANDSHAKE_ROLES_KEY));
    }
}
