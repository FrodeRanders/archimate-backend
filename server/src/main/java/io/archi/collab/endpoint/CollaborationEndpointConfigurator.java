package io.archi.collab.endpoint;

import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpointConfig;
import org.eclipse.microprofile.config.ConfigProvider;

import java.security.Principal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CollaborationEndpointConfigurator extends ServerEndpointConfig.Configurator {
    public static final String HANDSHAKE_HEADERS_KEY = "collab.handshake.headers";
    public static final String HANDSHAKE_PRINCIPAL_NAME_KEY = "collab.handshake.principalName";
    public static final String HANDSHAKE_ROLES_KEY = "collab.handshake.roles";

    @Override
    public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response) {
        Map<String, List<String>> headers = request == null ? Map.of() : request.getHeaders();
        sec.getUserProperties().put(HANDSHAKE_HEADERS_KEY, headers == null ? Map.of() : Map.copyOf(headers));
        sec.getUserProperties().put(HANDSHAKE_PRINCIPAL_NAME_KEY, principalName(request));
        sec.getUserProperties().put(HANDSHAKE_ROLES_KEY, resolveConfiguredRoles(request));
    }

    private String principalName(HandshakeRequest request) {
        if (request == null) {
            return "";
        }
        Principal principal = request.getUserPrincipal();
        return principal == null || principal.getName() == null ? "" : principal.getName().trim();
    }

    private Set<String> resolveConfiguredRoles(HandshakeRequest request) {
        if (request == null) {
            return Set.of();
        }
        LinkedHashSet<String> roles = new LinkedHashSet<>();
        addIfGranted(roles, request, "app.authz.admin-role", "admin");
        addIfGranted(roles, request, "app.authz.reader-role", "model_reader");
        addIfGranted(roles, request, "app.authz.writer-role", "model_writer");
        return Set.copyOf(roles);
    }

    private void addIfGranted(Set<String> roles, HandshakeRequest request, String configKey, String defaultValue) {
        String role = ConfigProvider.getConfig().getOptionalValue(configKey, String.class).orElse(defaultValue);
        if (role != null && !role.isBlank() && request.isUserInRole(role)) {
            roles.add(role);
        }
    }
}
