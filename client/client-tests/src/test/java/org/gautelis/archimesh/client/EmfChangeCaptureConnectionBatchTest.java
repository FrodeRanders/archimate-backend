package org.gautelis.archimesh.client;

import org.gautelis.archimesh.plugin.emf.EmfChangeCapture;
import org.gautelis.archimesh.plugin.util.SimpleJson;
import org.gautelis.archimesh.plugin.ws.ArchimeshSessionManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IBusinessObject;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;

class EmfChangeCaptureConnectionBatchTest {

    @Test
    void mapConnectionCreateSubmitOpsAlwaysReturnsPairedBatch() throws Exception {
        ArchimeshSessionManager sessionManager = new ArchimeshSessionManager();
        EmfChangeCapture capture = new EmfChangeCapture(sessionManager);

        IArchimateDiagramModel view = IArchimateFactory.eINSTANCE.createArchimateDiagramModel();
        view.setId("view-1");

        IBusinessObject sourceElement = IArchimateFactory.eINSTANCE.createBusinessObject();
        sourceElement.setId("elem-1");
        IBusinessObject targetElement = IArchimateFactory.eINSTANCE.createBusinessObject();
        targetElement.setId("elem-2");

        IArchimateRelationship relationship = IArchimateFactory.eINSTANCE.createAssociationRelationship();
        relationship.setId("rel-1");
        relationship.setSource(sourceElement);
        relationship.setTarget(targetElement);

        IDiagramModelArchimateObject sourceVo = IArchimateFactory.eINSTANCE.createDiagramModelArchimateObject();
        sourceVo.setId("vo-1");
        sourceVo.setArchimateElement(sourceElement);
        view.getChildren().add(sourceVo);

        IDiagramModelArchimateObject targetVo = IArchimateFactory.eINSTANCE.createDiagramModelArchimateObject();
        targetVo.setId("vo-2");
        targetVo.setArchimateElement(targetElement);
        view.getChildren().add(targetVo);

        IDiagramModelArchimateConnection connection = IArchimateFactory.eINSTANCE.createDiagramModelArchimateConnection();
        connection.setId("conn-1");
        connection.setArchimateRelationship(relationship);
        connection.setSource(sourceVo);
        connection.setTarget(targetVo);

        setField(sessionManager, "currentModelId", "demo");
        setField(sessionManager, "lastKnownRevision", 7L);
        setField(sessionManager, "userId", "user");
        setField(sessionManager, "sessionId", "session");

        // Mark relationship as already submitted — the paired batch should still be used
        @SuppressWarnings("unchecked")
        Set<String> submittedRelationshipIds = (Set<String>) getField(capture, "submittedRelationshipIds");
        submittedRelationshipIds.add("rel-1");

        Method method = EmfChangeCapture.class.getDeclaredMethod(
                "mapConnectionCreateSubmitOps", IDiagramModelArchimateConnection.class);
        method.setAccessible(true);
        String submit = (String) method.invoke(capture, connection);

        Assertions.assertNotNull(submit, "should produce a non-null submit payload");

        String payload = SimpleJson.asJsonObject(SimpleJson.readRawField(submit, "payload"));
        Assertions.assertNotNull(payload);
        List<String> ops = SimpleJson.splitArrayObjects(SimpleJson.readRawField(payload, "ops"));
        Assertions.assertEquals(2, ops.size(),
                "should emit paired batch (CreateRelationship + CreateConnection) even when relationship was already submitted");
        Assertions.assertEquals("CreateRelationship", SimpleJson.readStringField(ops.get(0), "type"));
        Assertions.assertEquals("CreateConnection", SimpleJson.readStringField(ops.get(1), "type"));
    }

    @Test
    void pendingConnectionRelationshipIdsSuppressesStandaloneSubmit() throws Exception {
        ArchimeshSessionManager sessionManager = new ArchimeshSessionManager();
        EmfChangeCapture capture = new EmfChangeCapture(sessionManager);

        setField(sessionManager, "currentModelId", "demo");
        setField(sessionManager, "lastKnownRevision", 0L);
        setField(sessionManager, "userId", "user");
        setField(sessionManager, "sessionId", "session");

        IArchimateRelationship relationship = IArchimateFactory.eINSTANCE.createAssociationRelationship();
        relationship.setId("rel-1");
        relationship.setSource(IArchimateFactory.eINSTANCE.createBusinessObject());
        relationship.getSource().setId("elem-1");
        relationship.setTarget(IArchimateFactory.eINSTANCE.createBusinessObject());
        relationship.getTarget().setId("elem-2");

        // Register the relationship as pending — simulating a connection retry that
        // registered it before the relationship ADD event fired
        @SuppressWarnings("unchecked")
        Set<String> pendingSet = (Set<String>) getField(capture, "pendingConnectionRelationshipIds");
        pendingSet.add("rel-1");

        // Simulate handleAdd for relationship via reflection.
        // The handleAdd method calls hasSubmittedRelationship first. Since the
        // relationship ID is NOT in submittedRelationshipIds, it won't return early.
        // It then checks pendingConnectionRelationshipIds — which contains "rel-1" — and defers.
        // We verify by checking that no send happened (the outbox is empty).

        org.eclipse.emf.common.notify.impl.NotificationImpl notification =
                new org.eclipse.emf.common.notify.impl.NotificationImpl(
                        org.eclipse.emf.common.notify.Notification.ADD, null, relationship) {
                    public String getFeatureName() {
                        return "relationships";
                    }
                    public Object getNotifier() {
                        return relationship;
                    }
                };

        Method handleAdd = EmfChangeCapture.class.getDeclaredMethod(
                "handleAdd", org.eclipse.emf.common.notify.Notification.class, Object.class);
        handleAdd.setAccessible(true);
        handleAdd.invoke(capture, notification, relationship);

        // The outbox should be empty — the standalone relationship submit was suppressed
        @SuppressWarnings("unchecked")
        Deque<Object> outbox = (Deque<Object>) getField(sessionManager, "offlineOutbox");
        boolean hasRelationshipSubmit = false;
        for (Object entry : outbox) {
            String json = (String) getField(entry, "submitOpsJson");
            if (json.contains("CreateRelationship") && json.contains("rel-1")) {
                hasRelationshipSubmit = true;
                break;
            }
        }
        Assertions.assertFalse(hasRelationshipSubmit,
                "standalone CreateRelationship should be suppressed when pending connection retry exists");
    }

    @Test
    void cleanupPendingConnectionRelationshipRemovesEntry() throws Exception {
        ArchimeshSessionManager sessionManager = new ArchimeshSessionManager();
        EmfChangeCapture capture = new EmfChangeCapture(sessionManager);

        IArchimateRelationship relationship = IArchimateFactory.eINSTANCE.createAssociationRelationship();
        relationship.setId("rel-1");

        IDiagramModelArchimateConnection connection = IArchimateFactory.eINSTANCE.createDiagramModelArchimateConnection();
        connection.setId("conn-1");
        connection.setArchimateRelationship(relationship);

        @SuppressWarnings("unchecked")
        Set<String> pendingSet = (Set<String>) getField(capture, "pendingConnectionRelationshipIds");
        pendingSet.add("rel-1");
        Assertions.assertTrue(pendingSet.contains("rel-1"));

        Method cleanup = EmfChangeCapture.class.getDeclaredMethod(
                "cleanupPendingConnectionRelationship", IDiagramModelArchimateConnection.class);
        cleanup.setAccessible(true);
        cleanup.invoke(capture, connection);

        Assertions.assertFalse(pendingSet.contains("rel-1"),
                "cleanup should remove the relationship from the pending set");
    }

    @Test
    void retryExhaustionSubmitsRelationshipFallback() throws Exception {
        ArchimeshSessionManager sessionManager = new ArchimeshSessionManager();
        EmfChangeCapture capture = new EmfChangeCapture(sessionManager);

        setField(sessionManager, "currentModelId", "demo");
        setField(sessionManager, "lastKnownRevision", 0L);
        setField(sessionManager, "userId", "user");
        setField(sessionManager, "sessionId", "session");

        IBusinessObject sourceElement = IArchimateFactory.eINSTANCE.createBusinessObject();
        sourceElement.setId("elem-src");
        IBusinessObject targetElement = IArchimateFactory.eINSTANCE.createBusinessObject();
        targetElement.setId("elem-tgt");

        IArchimateRelationship relationship = IArchimateFactory.eINSTANCE.createAssociationRelationship();
        relationship.setId("rel-exhaust");
        relationship.setSource(sourceElement);
        relationship.setTarget(targetElement);

        IDiagramModelArchimateConnection connection = IArchimateFactory.eINSTANCE.createDiagramModelArchimateConnection();
        connection.setId("conn-exhaust");
        connection.setArchimateRelationship(relationship);

        // Register as pending (simulating that a retry registered it)
        @SuppressWarnings("unchecked")
        Set<String> pendingSet = (Set<String>) getField(capture, "pendingConnectionRelationshipIds");
        pendingSet.add("rel-exhaust");

        // Set the attempt count to exceed max so the exhaustion path triggers
        @SuppressWarnings("unchecked")
        var attempts = (java.util.Map<String, Integer>) getField(capture, "pendingConnectionCreateAttempts");
        attempts.put("conn-create:conn-exhaust", 21); // exceeds CONNECTION_CREATE_MAX_RETRIES (20)

        Method scheduleRetry = EmfChangeCapture.class.getDeclaredMethod(
                "scheduleConnectionCreateRetry", IDiagramModelArchimateConnection.class, String.class);
        scheduleRetry.setAccessible(true);
        scheduleRetry.invoke(capture, connection, "test-exhaustion");

        // After exhaustion, pending set should no longer contain the relationship
        Assertions.assertFalse(pendingSet.contains("rel-exhaust"),
                "exhausted retry should remove from pending set");

        // The relationship should now be in submittedRelationshipIds (standalone fallback submitted)
        @SuppressWarnings("unchecked")
        Set<String> submittedRelIds = (Set<String>) getField(capture, "submittedRelationshipIds");
        Assertions.assertTrue(submittedRelIds.contains("rel-exhaust"),
                "exhausted retry should submit relationship standalone as fallback");
    }

    @Test
    void createRelationshipInFolderIsDeferredWhenPending() throws Exception {
        ArchimeshSessionManager sessionManager = new ArchimeshSessionManager();
        EmfChangeCapture capture = new EmfChangeCapture(sessionManager);

        setField(sessionManager, "currentModelId", "demo");
        setField(sessionManager, "lastKnownRevision", 0L);
        setField(sessionManager, "userId", "user");
        setField(sessionManager, "sessionId", "session");

        IArchimateRelationship relationship = IArchimateFactory.eINSTANCE.createAssociationRelationship();
        relationship.setId("rel-folder");
        relationship.setSource(IArchimateFactory.eINSTANCE.createBusinessObject());
        relationship.getSource().setId("elem-src");
        relationship.setTarget(IArchimateFactory.eINSTANCE.createBusinessObject());
        relationship.getTarget().setId("elem-tgt");

        com.archimatetool.model.IFolder folder = IArchimateFactory.eINSTANCE.createFolder();
        folder.setId("folder-1");

        @SuppressWarnings("unchecked")
        Set<String> pendingSet = (Set<String>) getField(capture, "pendingConnectionRelationshipIds");
        pendingSet.add("rel-folder");
        Assertions.assertTrue(pendingSet.contains("rel-folder"));

        // Simulate folder ADD for a new relationship (elements feature on folder)
        org.eclipse.emf.common.notify.impl.NotificationImpl notif =
                new org.eclipse.emf.common.notify.impl.NotificationImpl(
                        org.eclipse.emf.common.notify.Notification.ADD, null, relationship) {
                    public Object getFeature() {
                        return com.archimatetool.model.IArchimatePackage.Literals.FOLDER__ELEMENTS;
                    }
                    public Object getNotifier() {
                        return folder;
                    }
                };

        Method handleAdd = EmfChangeCapture.class.getDeclaredMethod(
                "handleAdd", org.eclipse.emf.common.notify.Notification.class, Object.class);
        handleAdd.setAccessible(true);
        handleAdd.invoke(capture, notif, relationship);

        // The outbox should not contain a CreateRelationshipInFolder — it was deferred
        @SuppressWarnings("unchecked")
        Deque<Object> outbox = (Deque<Object>) getField(sessionManager, "offlineOutbox");
        boolean hasRelSubmit = false;
        for (Object entry : outbox) {
            String json = (String) getField(entry, "submitOpsJson");
            if (json.contains("CreateRelationship") && json.contains("rel-folder")) {
                hasRelSubmit = true;
                break;
            }
        }
        Assertions.assertFalse(hasRelSubmit,
                "CreateRelationshipInFolder should be deferred when pending connection retry exists");
    }

    @Test
    void relationshipDeleteCleansPendingSet() throws Exception {
        ArchimeshSessionManager sessionManager = new ArchimeshSessionManager();
        EmfChangeCapture capture = new EmfChangeCapture(sessionManager);

        setField(sessionManager, "currentModelId", "demo");
        setField(sessionManager, "lastKnownRevision", 0L);
        setField(sessionManager, "userId", "user");
        setField(sessionManager, "sessionId", "session");

        IArchimateRelationship relationship = IArchimateFactory.eINSTANCE.createAssociationRelationship();
        relationship.setId("rel-del");

        @SuppressWarnings("unchecked")
        Set<String> pendingSet = (Set<String>) getField(capture, "pendingConnectionRelationshipIds");
        pendingSet.add("rel-del");
        Assertions.assertTrue(pendingSet.contains("rel-del"));

        // Simulate relationship removal via handleRemove
        Method handleRemove = EmfChangeCapture.class.getDeclaredMethod(
                "handleRemove", org.eclipse.emf.common.notify.Notification.class, Object.class);
        handleRemove.setAccessible(true);

        org.eclipse.emf.common.notify.impl.NotificationImpl notif =
                new org.eclipse.emf.common.notify.impl.NotificationImpl(
                        org.eclipse.emf.common.notify.Notification.REMOVE, relationship, null) {
                    public Object getNotifier() {
                        return relationship;
                    }
                };

        handleRemove.invoke(capture, notif, relationship);

        Assertions.assertFalse(pendingSet.contains("rel-del"),
                "relationship delete should remove from pending set");
    }

    @Test
    void closeClearsPendingSet() throws Exception {
        ArchimeshSessionManager sessionManager = new ArchimeshSessionManager();
        EmfChangeCapture capture = new EmfChangeCapture(sessionManager);

        @SuppressWarnings("unchecked")
        Set<String> pendingSet = (Set<String>) getField(capture, "pendingConnectionRelationshipIds");
        pendingSet.add("rel-close");

        Method close = EmfChangeCapture.class.getDeclaredMethod("close");
        close.setAccessible(true);
        close.invoke(capture);

        Assertions.assertTrue(pendingSet.isEmpty(),
                "close() should clear the pending set");
    }

    // --- Reflection helpers ---

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private static Object getField(Object target, String fieldName) throws Exception {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }
}
