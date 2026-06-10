package org.gautelis.archimesh.model;

public record ModelCatalogEntry(
        String modelId,
        String modelName,
        long headRevision) {
}
