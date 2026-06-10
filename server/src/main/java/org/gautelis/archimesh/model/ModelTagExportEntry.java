package org.gautelis.archimesh.model;

import com.fasterxml.jackson.databind.JsonNode;

public record ModelTagExportEntry(
        String modelId,
        String tagName,
        String description,
        long revision,
        String createdAt,
        JsonNode snapshot) {
}
