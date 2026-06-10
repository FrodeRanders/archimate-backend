package org.gautelis.archimesh.service;

import org.gautelis.archimesh.model.RevisionRange;

import java.util.Optional;

public interface IdempotencyService {
    Optional<RevisionRange> findRange(String modelId, String opBatchId);

    void remember(String modelId, String opBatchId, RevisionRange range);

    void clearModel(String modelId);
}
