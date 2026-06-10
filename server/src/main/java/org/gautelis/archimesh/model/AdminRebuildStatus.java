package org.gautelis.archimesh.model;

public record AdminRebuildStatus(
        RebuildStatus rebuild,
        AdminStatus status) {
}
