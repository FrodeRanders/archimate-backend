package org.gautelis.archimesh.service;

import org.gautelis.archimesh.wire.inbound.SubmitOpsMessage;

public interface ValidationService {
    void validateSubmitOps(String modelId, SubmitOpsMessage message);
}
