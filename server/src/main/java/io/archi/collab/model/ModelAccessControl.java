package io.archi.collab.model;

import java.util.LinkedHashSet;
import java.util.Set;

public record ModelAccessControl(
        String modelId,
        Set<String> adminUsers,
        Set<String> writerUsers,
        Set<String> readerUsers) {

    public ModelAccessControl {
        adminUsers = normalizeUsers(adminUsers);
        writerUsers = normalizeUsers(writerUsers);
        readerUsers = normalizeUsers(readerUsers);
    }

    public boolean configured() {
        return !adminUsers.isEmpty() || !writerUsers.isEmpty() || !readerUsers.isEmpty();
    }

    private static Set<String> normalizeUsers(Set<String> users) {
        if (users == null || users.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String user : users) {
            if (user == null) {
                continue;
            }
            String trimmed = user.trim();
            if (!trimmed.isBlank()) {
                normalized.add(trimmed);
            }
        }
        return Set.copyOf(normalized);
    }
}
