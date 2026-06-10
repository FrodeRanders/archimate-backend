package org.gautelis.archimesh.model;

public record AdminModelImportResult(
        String modelId,
        boolean overwritten,
        long headRevision,
        int importedOpBatchCount,
        int importedTagCount,
        String message) {
}
