package org.gautelis.archimesh.service;

import org.gautelis.archimesh.model.AdminActiveSession;
import org.gautelis.archimesh.wire.ServerEnvelope;
import jakarta.websocket.Session;

import java.util.List;
import java.util.Set;

public interface SessionRegistry {
    void register(String modelId, Session session);

    void unregister(String modelId, Session session);

    void send(Session session, ServerEnvelope message);

    void broadcast(String modelId, ServerEnvelope message);

    int sessionCount(String modelId);

    Set<String> activeModelIds();

    List<AdminActiveSession> activeSessions(String modelId);
}
