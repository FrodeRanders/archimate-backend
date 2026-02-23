package io.archi.collab.endpoint;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

@ServerEndpoint("/models/{modelId}/stream")
public class CollaborationEndpoint {
    private static final Logger LOG = LoggerFactory.getLogger(CollaborationEndpoint.class);

    @Inject
    ObjectMapper objectMapper;

    @Inject
    CollaborationService collaborationService;

    @Inject
    SessionRegistry sessionRegistry;

    @OnOpen
    public void onOpen(Session session, @PathParam("modelId") String modelId) {
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
                case "Join" -> collaborationService.onJoin(modelId, session,
                        objectMapper.treeToValue(envelope.payload(), JoinMessage.class));
                case "SubmitOps" -> collaborationService.onSubmitOps(modelId,
                        objectMapper.treeToValue(envelope.payload(), SubmitOpsMessage.class));
                case "AcquireLock" -> collaborationService.onAcquireLock(modelId,
                        objectMapper.treeToValue(envelope.payload(), AcquireLockMessage.class));
                case "ReleaseLock" -> collaborationService.onReleaseLock(modelId,
                        objectMapper.treeToValue(envelope.payload(), ReleaseLockMessage.class));
                case "Presence" -> collaborationService.onPresence(modelId,
                        objectMapper.treeToValue(envelope.payload(), PresenceMessage.class));
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
}
