package io.archi.collab.endpoint;

import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpointConfig;

import java.util.List;
import java.util.Map;

public class CollaborationEndpointConfigurator extends ServerEndpointConfig.Configurator {
    public static final String HANDSHAKE_HEADERS_KEY = "collab.handshake.headers";

    @Override
    public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response) {
        Map<String, List<String>> headers = request == null ? Map.of() : request.getHeaders();
        sec.getUserProperties().put(HANDSHAKE_HEADERS_KEY, headers == null ? Map.of() : Map.copyOf(headers));
    }
}
