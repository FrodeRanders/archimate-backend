package io.archi.collab.client;

import com.archimatetool.collab.util.CollabAuthHints;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CollabAuthHintsTest {

    @Test
    void tokenExpiryRecognizesExpiredJwt() {
        String token = "eyJhbGciOiJub25lIn0.eyJleHAiOjF9.";
        String message = CollabAuthHints.describeTokenExpiry(token);
        Assertions.assertTrue(message.contains("expired at"), message);
    }

    @Test
    void tokenExpiryRecognizesMissingExpClaim() {
        String token = "eyJhbGciOiJub25lIn0.eyJzdWIiOiJhbGljZSJ9.";
        String message = CollabAuthHints.describeTokenExpiry(token);
        Assertions.assertTrue(message.contains("no exp"), message);
    }

    @Test
    void tokenIdentityRecognizesSubjectAndRoles() {
        String token = "eyJhbGciOiJub25lIn0.eyJzdWIiOiJhbGljZSIsImdyb3VwcyI6WyJhZG1pbiIsIm1vZGVsX3dyaXRlciJdfQ.";
        String message = CollabAuthHints.describeTokenIdentity(token);
        Assertions.assertTrue(message.contains("alice"), message);
        Assertions.assertTrue(message.contains("admin"), message);
        Assertions.assertTrue(message.contains("model_writer"), message);
    }

    @Test
    void tokenIdentityHandlesMissingClaims() {
        String token = "eyJhbGciOiJub25lIn0.eyJpc3MiOiJ0ZXN0In0.";
        String message = CollabAuthHints.describeTokenIdentity(token);
        Assertions.assertTrue(message.contains("not found"), message);
    }

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
