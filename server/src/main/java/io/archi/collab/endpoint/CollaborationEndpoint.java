package io.archi.collab.endpoint;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.archi.collab.auth.AuthorizationAction;
import io.archi.collab.auth.AuthorizationDeniedException;
import io.archi.collab.auth.AuthorizationService;
import io.archi.collab.service.CollaborationService;
import io.archi.collab.service.SessionRegistry;
import io.archi.collab.wire.ClientEnvelope;
import io.archi.collab.wire.ServerEnvelope;
import io.archi.collab.wire.inbound.*;
import io.archi.collab.wire.outbound.ErrorMessage;
import jakarta.inject.Inject;
import jakarta.websocket.*;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ServerEndpoint(value = "/models/{modelId}/stream", configurator = CollaborationEndpointConfigurator.class)
public class CollaborationEndpoint {
    private static final Logger LOG = LoggerFactory.getLogger(CollaborationEndpoint.class);
    public static final String AUTH_SUBJECT_USER_ID_KEY = "collab.auth.subject.userId";
    public static final String AUTH_SUBJECT_ROLES_KEY = "collab.auth.subject.roles";
    public static final String AUTH_SUBJECT_REF_KEY = "collab.auth.subject.ref";
    public static final String AUTH_SUBJECT_WRITABLE_KEY = "collab.auth.subject.writable";

    @Inject
    ObjectMapper objectMapper;

    @Inject
    CollaborationService collaborationService;

    @Inject
    SessionRegistry sessionRegistry;

    @Inject
    AuthorizationService authorizationService;

    @OnOpen
    public void onOpen(Session session, @PathParam("modelId") String modelId) {
        try {
            authorizationService.requireWebSocketAllowed(session, AuthorizationAction.MODEL_JOIN, modelId, "HEAD");
            rememberAuthorizedSubject(session, "HEAD", true);
        } catch (AuthorizationDeniedException ex) {
            sessionRegistry.send(session, new ServerEnvelope("Error",
                    new ErrorMessage(ex.code(), ex.getMessage())));
            closeUnauthorized(session, ex.getMessage());
            return;
        }
        LOG.debug("WebSocket opened: sessionId={} modelId={}", sessionId(session), modelId);
    }

    @OnMessage
    public void onMessage(Session session, @PathParam("modelId") String modelId, String message) {
        try {
            ClientEnvelope envelope = objectMapper.readValue(message, ClientEnvelope.class);
            if (envelope.type() == null || envelope.type().isBlank()) {
                throw new IllegalArgumentException("Missing message type");
            }

            // Keep endpoint thin: decode envelope and delegate business rules to CollaborationService
            switch (envelope.type()) {
                case "Join" -> {
                    JoinMessage join = objectMapper.treeToValue(envelope.payload(), JoinMessage.class);
                    authorizationService.requireWebSocketAllowed(session, AuthorizationAction.MODEL_JOIN, modelId,
                            join == null ? "HEAD" : join.ref());
                    rememberAuthorizedSubject(session, join == null ? "HEAD" : join.ref(),
                            join == null || join.ref() == null || join.ref().isBlank() || "HEAD".equalsIgnoreCase(join.ref()));
                    collaborationService.onJoin(modelId, session, join);
                }
                case "SubmitOps" -> {
                    authorizationService.requireWebSocketAllowed(session, AuthorizationAction.MODEL_SUBMIT_OPS, modelId, null);
                    collaborationService.onSubmitOps(modelId, session,
                            objectMapper.treeToValue(envelope.payload(), SubmitOpsMessage.class));
                }
                case "AcquireLock" -> {
                    authorizationService.requireWebSocketAllowed(session, AuthorizationAction.MODEL_ACQUIRE_LOCK, modelId, null);
                    collaborationService.onAcquireLock(modelId, session,
                            objectMapper.treeToValue(envelope.payload(), AcquireLockMessage.class));
                }
                case "ReleaseLock" -> {
                    authorizationService.requireWebSocketAllowed(session, AuthorizationAction.MODEL_RELEASE_LOCK, modelId, null);
                    collaborationService.onReleaseLock(modelId, session,
                            objectMapper.treeToValue(envelope.payload(), ReleaseLockMessage.class));
                }
                case "Presence" -> {
                    authorizationService.requireWebSocketAllowed(session, AuthorizationAction.MODEL_PRESENCE, modelId, null);
                    collaborationService.onPresence(modelId, session,
                            objectMapper.treeToValue(envelope.payload(), PresenceMessage.class));
                }
                default -> {
                    LOG.warn("Unsupported message type: sessionId={} modelId={} type={}",
                            sessionId(session), modelId, envelope.type());
                    sessionRegistry.send(session, new ServerEnvelope("Error",
                            new ErrorMessage("UNSUPPORTED_MESSAGE_TYPE", envelope.type())));
                }
            }
        } catch (JsonProcessingException e) {
            // Protocol-level JSON errors are returned as structured Error messages over the same session
            LOG.warn("Invalid json payload: sessionId={} modelId={} error={}",
                    sessionId(session), modelId, e.getOriginalMessage());
            sessionRegistry.send(session, new ServerEnvelope("Error",
                    new ErrorMessage("INVALID_JSON", e.getOriginalMessage())));
        } catch (AuthorizationDeniedException e) {
            LOG.warn("Authorization denied: sessionId={} modelId={} code={}",
                    sessionId(session), modelId, e.code());
            sessionRegistry.send(session, new ServerEnvelope("Error",
                    new ErrorMessage(e.code(), e.getMessage())));
        } catch (Exception e) {
            LOG.error("Request handling failed: sessionId={} modelId={}",
                    sessionId(session), modelId, e);
            sessionRegistry.send(session, new ServerEnvelope("Error",
                    new ErrorMessage("REQUEST_FAILED", e.getMessage())));
        }
    }

    @OnClose
    public void onClose(Session session, @PathParam("modelId") String modelId) {
        LOG.info("WebSocket closed: sessionId={} modelId={}", sessionId(session), modelId);
        collaborationService.onDisconnect(modelId, session);
    }

    @OnError
    public void onError(Session session, @PathParam("modelId") String modelId, Throwable throwable) {
        LOG.warn("WebSocket error: sessionId={} modelId={}", sessionId(session), modelId, throwable);
        collaborationService.onDisconnect(modelId, session);
    }

    private String sessionId(Session session) {
        return session == null ? "n/a" : session.getId();
    }

    private void closeUnauthorized(Session session, String reason) {
        if (session == null) {
            return;
        }
        try {
            session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, reason));
        } catch (Exception ignored) {
        }
    }

    private void rememberAuthorizedSubject(Session session, String ref, boolean writable) {
        if (session == null) {
            return;
        }
        var subject = authorizationService.currentWebSocketSubject(session);
        session.getUserProperties().put(AUTH_SUBJECT_USER_ID_KEY, subject.userId());
        session.getUserProperties().put(AUTH_SUBJECT_ROLES_KEY, subject.roles());
        session.getUserProperties().put(AUTH_SUBJECT_REF_KEY, ref == null || ref.isBlank() ? "HEAD" : ref);
        session.getUserProperties().put(AUTH_SUBJECT_WRITABLE_KEY, Boolean.toString(writable));
    }
}
