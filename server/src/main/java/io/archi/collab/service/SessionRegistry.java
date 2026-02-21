package io.archi.collab.service;

import io.archi.collab.wire.ServerEnvelope;
import jakarta.websocket.Session;

public interface SessionRegistry {
    void register(String modelId, Session session);

    void unregister(String modelId, Session session);

    void send(Session session, ServerEnvelope message);

    void broadcast(String modelId, ServerEnvelope message);
}
