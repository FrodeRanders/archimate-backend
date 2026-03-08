package io.archi.collab.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.archi.collab.endpoint.CollaborationEndpoint;
import io.archi.collab.model.AdminActiveSession;
import io.archi.collab.service.SessionRegistry;
import io.archi.collab.wire.ServerEnvelope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.websocket.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
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

    @Override
    public List<AdminActiveSession> activeSessions(String modelId) {
        Set<Session> sessions = sessionsByModel.get(modelId);
        if (sessions == null || sessions.isEmpty()) {
            return List.of();
        }
        return sessions.stream()
                .map(this::toAdminActiveSession)
                .sorted(java.util.Comparator.comparing(AdminActiveSession::websocketSessionId))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private AdminActiveSession toAdminActiveSession(Session session) {
        if (session == null) {
            return new AdminActiveSession("", "", Set.of(), "HEAD", true);
        }
        Object rawRoles = session.getUserProperties().get(CollaborationEndpoint.AUTH_SUBJECT_ROLES_KEY);
        Set<String> roles = rawRoles instanceof Set<?> values
                ? values.stream().filter(v -> v != null).map(Object::toString).collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new))
                : Set.of();
        String userId = stringProp(session, CollaborationEndpoint.AUTH_SUBJECT_USER_ID_KEY);
        String ref = stringProp(session, CollaborationEndpoint.AUTH_SUBJECT_REF_KEY);
        boolean writable = Boolean.parseBoolean(stringProp(session, CollaborationEndpoint.AUTH_SUBJECT_WRITABLE_KEY));
        return new AdminActiveSession(
                session.getId(),
                userId,
                Set.copyOf(roles),
                ref.isBlank() ? "HEAD" : ref,
                writable);
    }

    private String stringProp(Session session, String key) {
        Object value = session.getUserProperties().get(key);
        return value == null ? "" : value.toString().trim();
    }
}
