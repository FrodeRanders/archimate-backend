package org.gautelis.archimesh.model;

public record AdminUiConfig(
        String identityMode,
        boolean authorizationEnabled) {
}
