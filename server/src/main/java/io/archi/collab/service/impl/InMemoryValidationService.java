package io.archi.collab.service.impl;

import io.archi.collab.service.ValidationService;
import io.archi.collab.wire.inbound.SubmitOpsMessage;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class InMemoryValidationService implements ValidationService {

    @Override
    public void validateSubmitOps(String modelId, SubmitOpsMessage message) {
        if(modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("modelId must not be empty");
        }
        if(message.opBatchId() == null || message.opBatchId().isBlank()) {
            throw new IllegalArgumentException("opBatchId must not be empty");
        }
        if(message.baseRevision() < 0) {
            throw new IllegalArgumentException("baseRevision must be >= 0");
        }
        if(message.ops() == null || !message.ops().isArray() || message.ops().isEmpty()) {
            throw new IllegalArgumentException("ops must be a non-empty array");
        }
    }
}
