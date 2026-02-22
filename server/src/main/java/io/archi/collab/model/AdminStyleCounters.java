package io.archi.collab.model;

public record AdminStyleCounters(
        long received,
        long accepted,
        long rejected,
        long applied) {
}
