package org.gautelis.archimesh.service;

import org.gautelis.archimesh.model.RevisionRange;

public interface RevisionService {
    RevisionRange assignRange(String modelId, int opCount);

    long headRevision(String modelId);

    void clearModel(String modelId);
}
