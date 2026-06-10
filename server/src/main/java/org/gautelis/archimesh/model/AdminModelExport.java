package org.gautelis.archimesh.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public record AdminModelExport(
        String format,
        String exportedAt,
        ModelCatalogEntry model,
        ModelAccessControl accessControl,
        JsonNode snapshot,
        JsonNode opBatches,
        List<ModelTagExportEntry> tags) {
}
