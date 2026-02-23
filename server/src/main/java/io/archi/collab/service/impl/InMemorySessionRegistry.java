package io.archi.collab.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.archi.collab.service.SessionRegistry;
import io.archi.collab.wire.ServerEnvelope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.websocket.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class InMemorySessionRegistry implements SessionRegistry {
    private static final Logger LOG = LoggerFactory.getLogger(InMemorySessionRegistry.class);

    private final ConcurrentHashMap<String, Set<Session>> sessionsByModel = new ConcurrentHashMap<>();

    @Inject
    ObjectMapper objectMapper;

    @Override
    public void register(String modelId, Session session) {
        Set<Session> sessions = sessionsByModel.computeIfAbsent(modelId, key -> ConcurrentHashMap.newKeySet());
        sessions.add(session);
        LOG.debug("Session registered: modelId={} sessionId={} totalSessions={}",
                modelId, session == null ? "n/a" : session.getId(), sessions.size());
    }

    @Override
    public void unregister(String modelId, Session session) {
        Set<Session> sessions = sessionsByModel.get(modelId);
        if (sessions == null) {
            return;
        }
        sessions.remove(session);
        LOG.debug("Session unregistered: modelId={} sessionId={} remainingSessions={}",
                modelId, session == null ? "n/a" : session.getId(), sessions.size());
        if (sessions.isEmpty()) {
            sessionsByModel.remove(modelId);
        }
    }

    @Override
    public void send(Session session, ServerEnvelope message) {
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(message);
            if ("OpsBroadcast".equals(message.type())) {
                LOG.debug("WS send OpsBroadcast: sessionId={} json={}", session.getId(), json);
            }
            session.getAsyncRemote().sendText(json);
        } catch (JsonProcessingException e) {
            LOG.error("Failed to serialize websocket payload: sessionId={} type={}",
                    session.getId(), message.type(), e);
        }
    }

    @Override
    public void broadcast(String modelId, ServerEnvelope message) {
        Set<Session> sessions = sessionsByModel.get(modelId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        LOG.debug("Broadcasting: modelId={} type={} recipients={}",
                modelId, message.type(), sessions.size());
        sessions.forEach(session -> send(session, message));
    }

    @Override
    public int sessionCount(String modelId) {
        Set<Session> sessions = sessionsByModel.get(modelId);
        return sessions == null ? 0 : sessions.size();
    }

    @Override
    public Set<String> activeModelIds() {
        return new HashSet<>(sessionsByModel.keySet());
    }
}
