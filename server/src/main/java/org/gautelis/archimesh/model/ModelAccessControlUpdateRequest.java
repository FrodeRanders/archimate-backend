package org.gautelis.archimesh.model;

import java.util.Set;

public record ModelAccessControlUpdateRequest(
        Set<String> adminUsers,
        Set<String> writerUsers,
        Set<String> readerUsers) {
}
