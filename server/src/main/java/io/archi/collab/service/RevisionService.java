package io.archi.collab.service;

import io.archi.collab.model.RevisionRange;

public interface RevisionService {
    RevisionRange assignRange(String modelId, int opCount);

    long headRevision(String modelId);

    void clearModel(String modelId);
}
