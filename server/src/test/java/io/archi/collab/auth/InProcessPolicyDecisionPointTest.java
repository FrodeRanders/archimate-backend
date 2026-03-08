package io.archi.collab.auth;

import io.archi.collab.model.ModelAccessControl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Set;

class InProcessPolicyDecisionPointTest {

    @Test
    void adminRoleAllowsAdminActions() {
        InProcessPolicyDecisionPoint pdp = new InProcessPolicyDecisionPoint();
        pdp.adminRole = "admin";

        AuthorizationDecision decision = pdp.decide(new AuthorizationRequest(
                new AuthorizationSubject("alice", Set.of("admin")),
                AuthorizationAction.ADMIN_MODEL_DELETE,
                "demo",
                null,
                null,
                AuthorizationTransport.REST));

        Assertions.assertTrue(decision.allowed());
    }

    @Test
    void nonAdminRoleIsDeniedForAdminActions() {
        InProcessPolicyDecisionPoint pdp = new InProcessPolicyDecisionPoint();
        pdp.adminRole = "admin";

        AuthorizationDecision decision = pdp.decide(new AuthorizationRequest(
                new AuthorizationSubject("bob", Set.of("model_writer")),
                AuthorizationAction.ADMIN_MODEL_DELETE,
                "demo",
                null,
                null,
                AuthorizationTransport.REST));

        Assertions.assertFalse(decision.allowed());
        Assertions.assertEquals("MODEL_ADMIN_REQUIRED", decision.code());
    }

    @Test
    void authenticatedNonAdminIsAllowedForNonAdminActions() {
        InProcessPolicyDecisionPoint pdp = new InProcessPolicyDecisionPoint();
        pdp.adminRole = "admin";
        pdp.readerRole = "model_reader";
        pdp.writerRole = "model_writer";

        AuthorizationDecision decision = pdp.decide(new AuthorizationRequest(
                new AuthorizationSubject("bob", Set.of("model_writer")),
                AuthorizationAction.MODEL_SUBMIT_OPS,
                "demo",
                "HEAD",
                null,
                AuthorizationTransport.WEBSOCKET));

        Assertions.assertTrue(decision.allowed());
    }

    @Test
    void readerMayReadButNotWrite() {
        InProcessPolicyDecisionPoint pdp = new InProcessPolicyDecisionPoint();
        pdp.adminRole = "admin";
        pdp.readerRole = "model_reader";
        pdp.writerRole = "model_writer";

        AuthorizationDecision readDecision = pdp.decide(new AuthorizationRequest(
                new AuthorizationSubject("eve", Set.of("model_reader")),
                AuthorizationAction.MODEL_SNAPSHOT_READ,
                "demo",
                "HEAD",
                null,
                AuthorizationTransport.REST));
        AuthorizationDecision writeDecision = pdp.decide(new AuthorizationRequest(
                new AuthorizationSubject("eve", Set.of("model_reader")),
                AuthorizationAction.MODEL_SUBMIT_OPS,
                "demo",
                "HEAD",
                null,
                AuthorizationTransport.WEBSOCKET));

        Assertions.assertTrue(readDecision.allowed());
        Assertions.assertFalse(writeDecision.allowed());
        Assertions.assertEquals("MODEL_WRITE_ROLE_REQUIRED", writeDecision.code());
    }

    @Test
    void configuredModelAclOverridesGlobalReaderWriterRoles() {
        InProcessPolicyDecisionPoint pdp = new InProcessPolicyDecisionPoint();
        pdp.adminRole = "admin";
        pdp.readerRole = "model_reader";
        pdp.writerRole = "model_writer";

        ModelAccessControl acl = new ModelAccessControl("demo", Set.of("owner"), Set.of("writer-user"), Set.of("reader-user"));

        AuthorizationDecision denied = pdp.decide(new AuthorizationRequest(
                new AuthorizationSubject("eve", Set.of("model_writer")),
                AuthorizationAction.MODEL_SUBMIT_OPS,
                "demo",
                "HEAD",
                acl,
                AuthorizationTransport.WEBSOCKET));
        AuthorizationDecision allowed = pdp.decide(new AuthorizationRequest(
                new AuthorizationSubject("writer-user", Set.of()),
                AuthorizationAction.MODEL_SUBMIT_OPS,
                "demo",
                "HEAD",
                acl,
                AuthorizationTransport.WEBSOCKET));

        Assertions.assertFalse(denied.allowed());
        Assertions.assertEquals("MODEL_ACCESS_DENIED", denied.code());
        Assertions.assertTrue(allowed.allowed());
    }
}
