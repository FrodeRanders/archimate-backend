package io.archi.collab.model;

public record AdminRebuildStatus(
        RebuildStatus rebuild,
        AdminStatus status) {
}
