package io.archi.collab.client;

import com.archimatetool.collab.util.CollabAuthHints;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CollabAuthHintsTest {

    @Test
    void preflightBearerHintMentionsExpiryAndRoles() {
        String message = CollabAuthHints.describePreflightAuthHint(true);
        Assertions.assertTrue(message.contains("unexpired"), message);
        Assertions.assertTrue(message.contains("roles"), message);
    }

    @Test
    void http401WithBearerMentionsExpiredOrInvalidToken() {
        String message = CollabAuthHints.describeHttpFailure("Loading model catalog", 401, true);
        Assertions.assertTrue(message.contains("invalid, or expired"), message);
    }

    @Test
    void authRequiredServerErrorMentionsBearerTokenWhenPresent() {
        String message = CollabAuthHints.describeServerError("AUTH_REQUIRED", "Authenticated subject is required.", true, false);
        Assertions.assertTrue(message.contains("bearer token"), message);
    }

    @Test
    void readOnlyServerErrorMentionsHead() {
        String message = CollabAuthHints.describeServerError("MODEL_REFERENCE_READ_ONLY", "read only", false, true);
        Assertions.assertTrue(message.contains("HEAD"), message);
    }
}
