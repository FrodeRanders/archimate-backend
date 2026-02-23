package io.archi.collab.model;

public record ModelCatalogEntry(
        String modelId,
        String modelName,
        long headRevision) {
}
