package io.archi.collab.service;

import io.archi.collab.wire.inbound.SubmitOpsMessage;

public interface ValidationService {
    void validateSubmitOps(String modelId, SubmitOpsMessage message);
}
