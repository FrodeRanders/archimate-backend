package org.gautelis.archimesh.model;

public record AdminStyleCounters(
        long received,
        long accepted,
        long rejected,
        long applied) {
}
