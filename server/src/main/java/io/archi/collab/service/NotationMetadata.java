package io.archi.collab.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Set;

public final class NotationMetadata {
    // Validation, persistence, and admin style telemetry all consume this shared metadata so new notation fields
    // only need to be described once.
    public static final Set<String> VIEW_OBJECT_FIELDS = Set.of(
            "x", "y", "width", "height",
            "type", "alpha", "lineAlpha", "lineWidth", "lineStyle",
            "textAlignment", "textPosition", "gradient", "iconVisibleState",
            "deriveElementLineColor",
            "fillColor", "lineColor", "font", "fontColor", "iconColor",
            "imagePath", "imagePosition",
            "name", "documentation");

    public static final Set<String> CONNECTION_FIELDS = Set.of(
            "type", "nameVisible", "textAlignment", "textPosition", "lineWidth",
            "name", "lineColor", "font", "fontColor", "documentation",
            "bendpoints");

    public static final Set<String> STYLE_ACTIVITY_FIELDS = Set.of(
            "alpha", "lineAlpha", "lineWidth", "lineStyle",
            "textAlignment", "textPosition", "gradient", "iconVisibleState",
            "deriveElementLineColor",
            "fillColor", "lineColor", "font", "fontColor", "iconColor",
            "imagePath", "imagePosition");

    public static final List<PersistedNotationField> VIEW_OBJECT_PERSISTED_FIELDS = List.of(
            new PersistedNotationField("x", "geom_x", true),
            new PersistedNotationField("y", "geom_y", true),
            new PersistedNotationField("width", "geom_width", true),
            new PersistedNotationField("height", "geom_height", true),
            new PersistedNotationField("type", "vo_type", false),
            new PersistedNotationField("alpha", "vo_alpha", false),
            new PersistedNotationField("lineAlpha", "vo_lineAlpha", false),
            new PersistedNotationField("lineWidth", "vo_lineWidth", false),
            new PersistedNotationField("lineStyle", "vo_lineStyle", false),
            new PersistedNotationField("textAlignment", "vo_textAlignment", false),
            new PersistedNotationField("textPosition", "vo_textPosition", false),
            new PersistedNotationField("gradient", "vo_gradient", false),
            new PersistedNotationField("iconVisibleState", "vo_iconVisibleState", false),
            new PersistedNotationField("deriveElementLineColor", "vo_deriveElementLineColor", false),
            new PersistedNotationField("fillColor", "vo_fillColor", false),
            new PersistedNotationField("lineColor", "vo_lineColor", false),
            new PersistedNotationField("font", "vo_font", false),
            new PersistedNotationField("fontColor", "vo_fontColor", false),
            new PersistedNotationField("iconColor", "vo_iconColor", false),
            new PersistedNotationField("imagePath", "vo_imagePath", false),
            new PersistedNotationField("imagePosition", "vo_imagePosition", false),
            new PersistedNotationField("name", "vo_name", false),
            new PersistedNotationField("documentation", "vo_documentation", false));

    public static final List<PersistedNotationField> CONNECTION_PERSISTED_FIELDS = List.of(
            new PersistedNotationField("type", "conn_type", false),
            new PersistedNotationField("nameVisible", "conn_nameVisible", false),
            new PersistedNotationField("textAlignment", "conn_textAlignment", false),
            new PersistedNotationField("textPosition", "conn_textPosition", false),
            new PersistedNotationField("lineWidth", "conn_lineWidth", false),
            new PersistedNotationField("name", "conn_name", false),
            new PersistedNotationField("lineColor", "conn_lineColor", false),
            new PersistedNotationField("font", "conn_font", false),
            new PersistedNotationField("fontColor", "conn_fontColor", false),
            new PersistedNotationField("documentation", "conn_documentation", false),
            new PersistedNotationField("bendpoints", "conn_bendpoints", false));

    private NotationMetadata() {
    }

    public static boolean containsStyleActivityField(JsonNode notation) {
        if (notation == null || !notation.isObject()) {
            return false;
        }
        // Style telemetry intentionally ignores geometry-only changes so admin activity counters reflect visual
        // styling churn rather than ordinary layout movement.
        for (String field : STYLE_ACTIVITY_FIELDS) {
            if (notation.has(field)) {
                return true;
            }
        }
        return false;
    }

    public record PersistedNotationField(String fieldName, String propertyPrefix, boolean geometry) {
        public String lamportAlias() {
            return fieldName + "Lamport";
        }

        public String clientAlias() {
            return fieldName + "ClientId";
        }
    }
}
