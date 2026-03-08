package io.archi.collab.model;

import java.util.Set;

public record ModelAccessControlUpdateRequest(
        Set<String> adminUsers,
        Set<String> writerUsers,
        Set<String> readerUsers) {
}
