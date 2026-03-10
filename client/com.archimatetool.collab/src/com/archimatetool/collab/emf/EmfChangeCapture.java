package com.archimatetool.collab.emf;

import java.util.Map;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.emf.common.notify.Notification;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EContentAdapter;

import com.archimatetool.collab.ArchiCollabPlugin;
import com.archimatetool.collab.ws.CollabSessionManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IBounds;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelBendpoint;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelArchimateComponent;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IIdentifier;
import com.archimatetool.model.IProperty;

/**
 * Captures local EMF changes and maps them to collaboration ops.
 */
public class EmfChangeCapture extends EContentAdapter {
    private static final long NOTATION_DEBOUNCE_MS = 180;
    private static final long CONNECTION_CREATE_RETRY_DELAY_MS = 120;
    private static final int CONNECTION_CREATE_MAX_RETRIES = 20;
    private static final long VIEW_OBJECT_CREATE_RETRY_DELAY_MS = 120;
    private static final int VIEW_OBJECT_CREATE_MAX_RETRIES = 20;

    private final OpMapper opMapper = new OpMapper();
    private final CollabSessionManager sessionManager;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "archi-collab-emf-debounce");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, ScheduledFuture<?>> pendingNotationUpdates = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> pendingConnectionCreates = new ConcurrentHashMap<>();
    private final Map<String, Integer> pendingConnectionCreateAttempts = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> pendingViewObjectCreates = new ConcurrentHashMap<>();
    private final Map<String, Integer> pendingViewObjectCreateAttempts = new ConcurrentHashMap<>();
    private final java.util.Set<String> knownElementIds = ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> submittedElementIds = ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> submittedRelationshipIds = ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> submittedConnectionIds = ConcurrentHashMap.newKeySet();
    // Folder ids are tracked locally so rename/move handling can keep referring to immutable ids.
    private final java.util.Set<String> knownFolderIds = ConcurrentHashMap.newKeySet();

    public EmfChangeCapture(CollabSessionManager sessionManager) {
        this.sessionManager = sessionManager;
        seedKnownElementIds();
        seedKnownFolderIds();
    }

    @Override
    public void notifyChanged(Notification notification) {
        super.notifyChanged(notification);
        ArchiCollabPlugin.logTrace("EMF event type=" + eventTypeName(notification.getEventType())
                + " notifier=" + className(notification.getNotifier())
                + " feature=" + featureName(notification));

        if(RemoteApplyGuard.isRemoteApply()) {
            // Prevent rebroadcast loops while applying server-originated mutations
            ArchiCollabPlugin.logTrace("EMF event ignored: remote apply guard active");
            return;
        }

        boolean hasCollabModelContext = sessionManager != null
                && sessionManager.getCurrentModelId() != null
                && !sessionManager.getCurrentModelId().isBlank();
        if(notification.getNotifier() == null
                || sessionManager == null
                || (!sessionManager.isConnected() && !hasCollabModelContext)) {
            ArchiCollabPlugin.logTrace("EMF event ignored: notifier/session missing or disconnected"
                    + " notifierNull=" + (notification.getNotifier() == null)
                    + " sessionNull=" + (sessionManager == null)
                    + " connected=" + (sessionManager != null && sessionManager.isConnected())
                    + " hasContext=" + hasCollabModelContext);
            return;
        }

        if(sessionManager.isCurrentReferenceReadOnly()) {
            ArchiCollabPlugin.logTrace("EMF event ignored: active collaboration ref is read-only ref="
                    + sessionManager.getCurrentModelRef());
            return;
        }

        switch(notification.getEventType()) {
            case Notification.ADD -> handleAdd(notification, notification.getNewValue());
            case Notification.ADD_MANY -> handleAddMany(notification);
            case Notification.REMOVE -> handleRemove(notification, notification.getOldValue());
            case Notification.REMOVE_MANY -> handleRemoveMany(notification);
            case Notification.SET -> handleSet(notification);
            default -> {
                ArchiCollabPlugin.logTrace("EMF event ignored: unsupported event type " + notification.getEventType());
            }
        }
    }

    private void handleAddMany(Notification notification) {
        Object newValue = notification.getNewValue();
        if(newValue instanceof Collection<?> items) {
            for(Object item : items) {
                handleAdd(notification, item);
            }
            return;
        }
        ArchiCollabPlugin.logTrace("ADD_MANY ignored: payload is not a collection (" + className(newValue) + ")");
    }

    private void handleRemoveMany(Notification notification) {
        Object oldValue = notification.getOldValue();
        if(oldValue instanceof Collection<?> items) {
            for(Object item : items) {
                handleRemove(notification, item);
            }
            return;
        }
        ArchiCollabPlugin.logTrace("REMOVE_MANY ignored: payload is not a collection (" + className(oldValue) + ")");
    }

    private void handleAdd(Notification notification, Object newValue) {
        String feature = featureName(notification);
        Object notifier = notification.getNotifier();
        if("folders".equals(feature) && newValue instanceof IFolder folder) {
            // Model-tree folders are synchronized explicitly; relying on default Archi folders would drift.
            handleFolderAdded(notifier, folder);
            return;
        }
        if("elements".equals(feature) && notifier instanceof IFolder folder) {
            // Placement changes are separate ops so object identity never depends on its current folder path.
            if(newValue instanceof IArchimateElement element) {
                send(opMapper.toMoveElementToFolderSubmitOps(
                        element,
                        folder,
                        sessionManager.getCurrentModelId(),
                        sessionManager.getLastKnownRevision(),
                        sessionManager.getUserId(),
                        sessionManager.getSessionId()),
                        "MoveElementToFolder");
                return;
            }
            if(newValue instanceof IArchimateRelationship relationship) {
                send(opMapper.toMoveRelationshipToFolderSubmitOps(
                        relationship,
                        folder,
                        sessionManager.getCurrentModelId(),
                        sessionManager.getLastKnownRevision(),
                        sessionManager.getUserId(),
                        sessionManager.getSessionId()),
                        "MoveRelationshipToFolder");
                return;
            }
            if(newValue instanceof IArchimateDiagramModel view) {
                send(opMapper.toMoveViewToFolderSubmitOps(
                        view,
                        folder,
                        sessionManager.getCurrentModelId(),
                        sessionManager.getLastKnownRevision(),
                        sessionManager.getUserId(),
                        sessionManager.getSessionId()),
                        "MoveViewToFolder");
                return;
            }
        }
        if("children".equals(feature)
                && notifier instanceof IDiagramModelArchimateObject parentViewObject
                && newValue instanceof IDiagramModelArchimateObject childViewObject) {
            send(opMapper.toAddViewObjectChildMemberSubmitOps(
                    "vo:" + parentViewObject.getId(),
                    "vo:" + childViewObject.getId(),
                    sessionManager.getCurrentModelId(),
                    sessionManager.getLastKnownRevision(),
                    sessionManager.getUserId(),
                    sessionManager.getSessionId()),
                    "AddViewObjectChildMember");
            return;
        }

        if(newValue instanceof IProperty property) {
            Object owner = property.eContainer() != null ? property.eContainer() : notification.getNotifier();
            String targetId = opMapper.targetIdForOwner(owner);
            if(targetId != null && property.getKey() != null && !property.getKey().isBlank()) {
                send(opMapper.toSetPropertySubmitOps(
                        targetId,
                        property.getKey(),
                        property.getValue(),
                        sessionManager.getCurrentModelId(),
                        sessionManager.getLastKnownRevision(),
                        sessionManager.getUserId(),
                        sessionManager.getSessionId()),
                        "SetProperty(add)");
            }
            return;
        }

        if(newValue instanceof IArchimateElement element) {
            if(hasSubmittedElement(element)) {
                return;
            }
            send(opMapper.toCreateElementSubmitOps(
                    element,
                    sessionManager.getCurrentModelId(),
                    sessionManager.getLastKnownRevision(),
                    sessionManager.getUserId(),
                    sessionManager.getSessionId()),
                    "CreateElement");
            rememberKnownElement(element);
            rememberSubmittedElement(element);
            return;
        }
        if(newValue instanceof IArchimateRelationship relationship) {
            rememberSubmittedRelationship(relationship);
            send(opMapper.toCreateRelationshipSubmitOps(
                    relationship,
                    sessionManager.getCurrentModelId(),
                    sessionManager.getLastKnownRevision(),
                    sessionManager.getUserId(),
                    sessionManager.getSessionId()),
                    "CreateRelationship");
            return;
        }
        if(newValue instanceof IArchimateDiagramModel view) {
            send(opMapper.toCreateViewSubmitOps(
                    view,
                    sessionManager.getCurrentModelId(),
                    sessionManager.getLastKnownRevision(),
                    sessionManager.getUserId(),
                    sessionManager.getSessionId()),
                    "CreateView");
            return;
        }
        if(newValue instanceof IDiagramModelArchimateObject viewObject) {
            trySendViewObjectCreate(viewObject, "add");
            return;
        }
        if(newValue instanceof IDiagramModelArchimateConnection connection) {
            trySendConnectionCreate(connection, "add");
            return;
        }
        ArchiCollabPlugin.logTrace("ADD ignored: no mapping for value type " + className(newValue));
    }

    private void handleRemove(Notification notification, Object oldValue) {
        String feature = featureName(notification);
        Object notifier = notification.getNotifier();
        if("folders".equals(feature) && oldValue instanceof IFolder folder) {
            handleFolderRemoved(folder);
            return;
        }
        if("children".equals(feature)
                && notifier instanceof IDiagramModelArchimateObject parentViewObject
                && oldValue instanceof IDiagramModelArchimateObject childViewObject) {
            send(opMapper.toRemoveViewObjectChildMemberSubmitOps(
                    "vo:" + parentViewObject.getId(),
                    "vo:" + childViewObject.getId(),
                    sessionManager.getCurrentModelId(),
                    sessionManager.getLastKnownRevision(),
                    sessionManager.getUserId(),
                    sessionManager.getSessionId()),
                    "RemoveViewObjectChildMember");
            return;
        }

        if("children".equals(feature)
                && notifier instanceof IDiagramModelContainer container
                && oldValue instanceof IDiagramModelArchimateObject childViewObject) {
            EObject owner = container instanceof EObject eo ? eo : null;
            EObject parentObject = owner != null && owner.eContainer() instanceof EObject parent ? parent : null;
            if(parentObject instanceof IDiagramModelArchimateObject parentViewObject) {
                send(opMapper.toRemoveViewObjectChildMemberSubmitOps(
                        "vo:" + parentViewObject.getId(),
                        "vo:" + childViewObject.getId(),
                        sessionManager.getCurrentModelId(),
                        sessionManager.getLastKnownRevision(),
                        sessionManager.getUserId(),
                        sessionManager.getSessionId()),
                        "RemoveViewObjectChildMember(container)");
                return;
            }
        }

        if(oldValue instanceof IProperty property) {
            Object owner = property.eContainer() != null ? property.eContainer() : notification.getNotifier();
            String targetId = opMapper.targetIdForOwner(owner);
            String key = opMapper.key(property);
            if(targetId != null && key != null && !key.isBlank()) {
                send(opMapper.toUnsetPropertySubmitOps(
                        targetId,
                        key,
                        sessionManager.getCurrentModelId(),
                        sessionManager.getLastKnownRevision(),
                        sessionManager.getUserId(),
                        sessionManager.getSessionId()),
                        "UnsetProperty(remove)");
            }
            return;
        }

        if(oldValue instanceof IArchimateElement element) {
            forgetKnownElement(element);
            forgetSubmittedElement(element);
            send(opMapper.toDeleteElementSubmitOps(
                    element,
                    sessionManager.getCurrentModelId(),
                    sessionManager.getLastKnownRevision(),
                    sessionManager.getUserId(),
                    sessionManager.getSessionId()),
                    "DeleteElement");
            return;
        }
        if(oldValue instanceof IArchimateRelationship relationship) {
            forgetSubmittedRelationship(relationship);
            send(opMapper.toDeleteRelationshipSubmitOps(
                    relationship,
                    sessionManager.getCurrentModelId(),
                    sessionManager.getLastKnownRevision(),
                    sessionManager.getUserId(),
                    sessionManager.getSessionId()),
                    "DeleteRelationship");
            return;
        }
        if(oldValue instanceof IArchimateDiagramModel view) {
            send(opMapper.toDeleteViewSubmitOps(
                    view,
                    sessionManager.getCurrentModelId(),
                    sessionManager.getLastKnownRevision(),
                    sessionManager.getUserId(),
                    sessionManager.getSessionId()),
                    "DeleteView");
            return;
        }
        if(oldValue instanceof IDiagramModelArchimateObject viewObject) {
            cancelPending("vo:" + viewObject.getId());
            send(opMapper.toDeleteViewObjectSubmitOps(
                    viewObject,
                    sessionManager.getCurrentModelId(),
                    sessionManager.getLastKnownRevision(),
                    sessionManager.getUserId(),
                    sessionManager.getSessionId()),
                    "DeleteViewObject");
            return;
        }
        if(oldValue instanceof IDiagramModelArchimateConnection connection) {
            clearPendingConnectionCreate("conn-create:" + connection.getId());
            cancelPending("conn:" + connection.getId());
            forgetSubmittedConnection(connection);
            send(opMapper.toDeleteConnectionSubmitOps(
                    connection,
                    sessionManager.getCurrentModelId(),
                    sessionManager.getLastKnownRevision(),
                    sessionManager.getUserId(),
                    sessionManager.getSessionId()),
                    "DeleteConnection");
            return;
        }
        ArchiCollabPlugin.logTrace("REMOVE ignored: no mapping for value type " + className(oldValue));
    }

    private void handleSet(Notification notification) {
        Object notifier = notification.getNotifier();
        String featureName = featureName(notification);

        if(notifier instanceof IArchimateRelationship relationship) {
            boolean includeName = "name".equals(featureName);
            boolean includeDocumentation = "documentation".equals(featureName);
            boolean includeEndpoints = "source".equals(featureName) || "target".equals(featureName);
            if(includeName || includeDocumentation || includeEndpoints) {
                send(opMapper.toUpdateRelationshipSubmitOps(
                        relationship,
                        sessionManager.getCurrentModelId(),
                        sessionManager.getLastKnownRevision(),
                        sessionManager.getUserId(),
                        sessionManager.getSessionId(),
                        includeName,
                        includeDocumentation,
                        includeEndpoints),
                        "UpdateRelationship");
                if(includeEndpoints) {
                    for(IDiagramModelArchimateComponent component : relationship.getReferencingDiagramComponents()) {
                        if(component instanceof IDiagramModelArchimateConnection connection) {
                            trySendConnectionCreate(connection, "relationship-endpoint-set");
                        }
                    }
                }
            } else {
                ArchiCollabPlugin.logTrace("SET ignored for relationship feature=" + featureName);
            }
            return;
        }

        if(notifier instanceof IArchimateElement element) {
            boolean includeName = "name".equals(featureName);
            boolean includeDocumentation = "documentation".equals(featureName);
            if(includeName || includeDocumentation) {
                send(opMapper.toUpdateElementSubmitOps(
                        element,
                        sessionManager.getCurrentModelId(),
                        sessionManager.getLastKnownRevision(),
                        sessionManager.getUserId(),
                        sessionManager.getSessionId(),
                        includeName,
                        includeDocumentation),
                        "UpdateElement");
            } else {
                ArchiCollabPlugin.logTrace("SET ignored for element feature=" + featureName);
            }
            return;
        }

        if(notifier instanceof IFolder folder) {
            boolean includeName = "name".equals(featureName);
            boolean includeDocumentation = "documentation".equals(featureName);
            if(includeName || includeDocumentation) {
                send(opMapper.toUpdateFolderSubmitOps(
                        folder,
                        sessionManager.getCurrentModelId(),
                        sessionManager.getLastKnownRevision(),
                        sessionManager.getUserId(),
                        sessionManager.getSessionId(),
                        includeName,
                        includeDocumentation),
                        "UpdateFolder");
            } else {
                ArchiCollabPlugin.logTrace("SET ignored for folder feature=" + featureName);
            }
            return;
        }

        if(notifier instanceof IDiagramModel view) {
            boolean includeName = "name".equals(featureName);
            boolean includeDocumentation = "documentation".equals(featureName);
            if(includeName || includeDocumentation) {
                send(opMapper.toUpdateViewSubmitOps(
                        view,
                        sessionManager.getCurrentModelId(),
                        sessionManager.getLastKnownRevision(),
                        sessionManager.getUserId(),
                        sessionManager.getSessionId(),
                        includeName,
                        includeDocumentation),
                        "UpdateView");
                return;
            }
            if("viewpoint".equals(featureName) && notifier instanceof IArchimateDiagramModel archimateView && notifier instanceof IIdentifier identifier) {
                send(opMapper.toSetPropertySubmitOps(
                        "view:" + identifier.getId(),
                        "viewpoint",
                        archimateView.getViewpoint(),
                        sessionManager.getCurrentModelId(),
                        sessionManager.getLastKnownRevision(),
                        sessionManager.getUserId(),
                        sessionManager.getSessionId()),
                        "SetProperty(viewpoint)");
                return;
            }
            if("connectionRouterType".equals(featureName) && notifier instanceof IIdentifier identifier) {
                send(opMapper.toSetPropertySubmitOps(
                        "view:" + identifier.getId(),
                        "connectionRouterType",
                        view.getConnectionRouterType(),
                        sessionManager.getCurrentModelId(),
                        sessionManager.getLastKnownRevision(),
                        sessionManager.getUserId(),
                        sessionManager.getSessionId()),
                        "SetProperty(connectionRouterType)");
            } else {
                ArchiCollabPlugin.logTrace("SET ignored for view feature=" + featureName);
            }
            return;
        }

        if(notifier instanceof IDiagramModelArchimateObject viewObject) {
            // Notation edits are noisy; debounce into one opaque update
            scheduleNotationUpdate("vo:" + viewObject.getId(),
                    () -> send(opMapper.toUpdateViewObjectOpaqueSubmitOps(
                            viewObject,
                            sessionManager.getCurrentModelId(),
                            sessionManager.getLastKnownRevision(),
                            sessionManager.getUserId(),
                            sessionManager.getSessionId()),
                            "UpdateViewObjectOpaque"));
            return;
        }

        if(notifier instanceof IDiagramModelArchimateConnection connection) {
            if("source".equals(featureName)
                    || "target".equals(featureName)
                    || "archimateConcept".equals(featureName)
                    || "archimateRelationship".equals(featureName)) {
                trySendConnectionCreate(connection, "connection-set-" + featureName);
            }
            scheduleNotationUpdate("conn:" + connection.getId(),
                    () -> send(opMapper.toUpdateConnectionOpaqueSubmitOps(
                            connection,
                            sessionManager.getCurrentModelId(),
                            sessionManager.getLastKnownRevision(),
                            sessionManager.getUserId(),
                            sessionManager.getSessionId()),
                            "UpdateConnectionOpaque"));
            return;
        }

        if(notifier instanceof IBounds bounds) {
            EObject container = bounds.eContainer();
            if(container instanceof IDiagramModelArchimateObject viewObject) {
                scheduleNotationUpdate("vo:" + viewObject.getId(),
                        () -> send(opMapper.toUpdateViewObjectOpaqueSubmitOps(
                                viewObject,
                                sessionManager.getCurrentModelId(),
                                sessionManager.getLastKnownRevision(),
                                sessionManager.getUserId(),
                                sessionManager.getSessionId()),
                                "UpdateViewObjectOpaque(bounds)"));
            }
            return;
        }

        if(notifier instanceof IDiagramModelBendpoint bendpoint) {
            EObject container = bendpoint.eContainer();
            if(container instanceof IDiagramModelConnection dmc && dmc instanceof IDiagramModelArchimateConnection connection) {
                scheduleNotationUpdate("conn:" + connection.getId(),
                        () -> send(opMapper.toUpdateConnectionOpaqueSubmitOps(
                                connection,
                                sessionManager.getCurrentModelId(),
                                sessionManager.getLastKnownRevision(),
                                sessionManager.getUserId(),
                                sessionManager.getSessionId()),
                                "UpdateConnectionOpaque(bendpoints)"));
            }
            return;
        }

        if(notifier instanceof IProperty property) {
            Object owner = property.eContainer();
            String targetId = opMapper.targetIdForOwner(owner);
            if(targetId == null) {
                ArchiCollabPlugin.logTrace("SET property ignored: could not resolve owner targetId");
                return;
            }
            String feature = featureName(notification);
            if("key".equals(feature)) {
                Object oldKey = notification.getOldValue();
                if(oldKey instanceof String oldKeyText && !oldKeyText.isBlank()) {
                    send(opMapper.toUnsetPropertySubmitOps(
                            targetId,
                            oldKeyText,
                            sessionManager.getCurrentModelId(),
                            sessionManager.getLastKnownRevision(),
                            sessionManager.getUserId(),
                            sessionManager.getSessionId()),
                            "UnsetProperty(key-changed)");
                }
            }
            String key = opMapper.key(property);
            if(key != null && !key.isBlank()) {
                send(opMapper.toSetPropertySubmitOps(
                        targetId,
                        key,
                        opMapper.value(property),
                        sessionManager.getCurrentModelId(),
                        sessionManager.getLastKnownRevision(),
                        sessionManager.getUserId(),
                        sessionManager.getSessionId()),
                        "SetProperty(update)");
            } else {
                ArchiCollabPlugin.logTrace("SET property ignored: key is blank");
            }
            return;
        }
        ArchiCollabPlugin.logTrace("SET ignored: no mapping for notifier type " + className(notifier) + " feature=" + featureName);
    }

    private String featureName(Notification notification) {
        Object feature = notification.getFeature();
        if(feature instanceof EStructuralFeature structuralFeature) {
            return structuralFeature.getName();
        }
        return "";
    }

    private void send(String submitOpsJson, String opLabel) {
        if(submitOpsJson == null || submitOpsJson.isBlank()) {
            ArchiCollabPlugin.logTrace("Submit skipped for opLabel=" + opLabel + " because mapped payload is empty");
            return;
        }
        sessionManager.sendSubmitOps(submitOpsJson);
        ArchiCollabPlugin.logInfo("Submitted " + opLabel + " op from local EMF capture");
    }

    private void trySendViewObjectCreate(IDiagramModelArchimateObject viewObject, String reason) {
        if(viewObject == null) {
            return;
        }
        String id = viewObject.getId();
        if(id == null || id.isBlank()) {
            return;
        }
        if(!isViewObjectCreateReady(viewObject)) {
            ArchiCollabPlugin.logTrace("CreateViewObject deferred: incomplete ids reason=" + reason + " voId=" + id);
            scheduleViewObjectCreateRetry(viewObject, reason);
            return;
        }
        clearPendingViewObjectCreate("vo-create:" + id);
        send(mapViewObjectCreateSubmitOps(viewObject), "CreateViewObject");
    }

    private String mapViewObjectCreateSubmitOps(IDiagramModelArchimateObject viewObject) {
        IArchimateElement element = viewObject == null ? null : viewObject.getArchimateElement();
        if(element != null && isNewLocalElement(element)) {
            rememberKnownElement(element);
            rememberSubmittedElement(element);
            return opMapper.toCreateViewObjectWithElementSubmitOps(
                    viewObject,
                    sessionManager.getCurrentModelId(),
                    sessionManager.getLastKnownRevision(),
                    sessionManager.getUserId(),
                    sessionManager.getSessionId());
        }
        return opMapper.toCreateViewObjectSubmitOps(
                viewObject,
                sessionManager.getCurrentModelId(),
                sessionManager.getLastKnownRevision(),
                sessionManager.getUserId(),
                sessionManager.getSessionId());
    }

    private void scheduleViewObjectCreateRetry(IDiagramModelArchimateObject viewObject, String reason) {
        String id = viewObject.getId();
        if(id == null || id.isBlank()) {
            return;
        }
        String key = "vo-create:" + id;
        if(pendingViewObjectCreates.containsKey(key)) {
            return;
        }
        int attempt = pendingViewObjectCreateAttempts.merge(key, 1, Integer::sum);
        if(attempt > VIEW_OBJECT_CREATE_MAX_RETRIES) {
            clearPendingViewObjectCreate(key);
            ArchiCollabPlugin.logInfo("CreateViewObject retry dropped after max attempts voId=" + id + " reason=" + reason);
            return;
        }

        ScheduledFuture<?> future = scheduler.schedule(() -> {
            pendingViewObjectCreates.remove(key);
            trySendViewObjectCreate(viewObject, reason + "-retry-" + attempt);
        }, VIEW_OBJECT_CREATE_RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
        pendingViewObjectCreates.put(key, future);
    }

    private void clearPendingViewObjectCreate(String key) {
        ScheduledFuture<?> previous = pendingViewObjectCreates.remove(key);
        if(previous != null) {
            previous.cancel(false);
        }
        pendingViewObjectCreateAttempts.remove(key);
    }

    private boolean isViewObjectCreateReady(IDiagramModelArchimateObject viewObject) {
        return hasId(viewObject)
                && hasId(viewObject.getDiagramModel())
                && hasId(viewObject.getArchimateElement());
    }

    private void trySendConnectionCreate(IDiagramModelArchimateConnection connection, String reason) {
        if(connection == null) {
            return;
        }
        String id = connection.getId();
        if(id == null || id.isBlank()) {
            return;
        }
        if(hasSubmittedConnection(connection)) {
            clearPendingConnectionCreate("conn-create:" + id);
            return;
        }
        if(!isConnectionCreateReady(connection)) {
            // Relationship/view endpoint IDs can appear in later EMF notifications
            ArchiCollabPlugin.logTrace("CreateConnection(+Relationship) deferred: incomplete ids reason=" + reason + " connId=" + id);
            scheduleConnectionCreateRetry(connection, reason);
            return;
        }
        clearPendingConnectionCreate("conn-create:" + id);
        String submitJson = mapConnectionCreateSubmitOps(connection);
        send(submitJson, "CreateConnection");
        if(submitJson != null && !submitJson.isBlank()) {
            rememberSubmittedConnection(connection);
        }
    }

    private String mapConnectionCreateSubmitOps(IDiagramModelArchimateConnection connection) {
        IArchimateRelationship relationship = connection.getArchimateRelationship();
        if(relationship != null && hasSubmittedRelationship(relationship)) {
            // Relationship already submitted in this session; emit connection-only create
            return opMapper.toCreateConnectionSubmitOps(
                    connection,
                    sessionManager.getCurrentModelId(),
                    sessionManager.getLastKnownRevision(),
                    sessionManager.getUserId(),
                    sessionManager.getSessionId());
        }
        // Otherwise submit the paired relationship+connection batch to satisfy server preconditions
        return opMapper.toCreateConnectionWithRelationshipSubmitOps(
                connection,
                sessionManager.getCurrentModelId(),
                sessionManager.getLastKnownRevision(),
                sessionManager.getUserId(),
                sessionManager.getSessionId());
    }

    private void scheduleConnectionCreateRetry(IDiagramModelArchimateConnection connection, String reason) {
        String id = connection.getId();
        if(id == null || id.isBlank()) {
            return;
        }
        String key = "conn-create:" + id;
        if(pendingConnectionCreates.containsKey(key)) {
            return;
        }
        int attempt = pendingConnectionCreateAttempts.merge(key, 1, Integer::sum);
        if(attempt > CONNECTION_CREATE_MAX_RETRIES) {
            // Avoid indefinite retries when a connection never reaches a valid ID state
            clearPendingConnectionCreate(key);
            ArchiCollabPlugin.logInfo("CreateConnection retry dropped after max attempts connId=" + id + " reason=" + reason);
            return;
        }

        ScheduledFuture<?> future = scheduler.schedule(() -> {
            pendingConnectionCreates.remove(key);
            trySendConnectionCreate(connection, reason + "-retry-" + attempt);
        }, CONNECTION_CREATE_RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
        pendingConnectionCreates.put(key, future);
    }

    private void clearPendingConnectionCreate(String key) {
        ScheduledFuture<?> previous = pendingConnectionCreates.remove(key);
        if(previous != null) {
            previous.cancel(false);
        }
        pendingConnectionCreateAttempts.remove(key);
    }

    private boolean isConnectionCreateReady(IDiagramModelArchimateConnection connection) {
        if(!hasId(connection)) {
            return false;
        }
        if(!hasId(connection.getDiagramModel())) {
            return false;
        }
        if(!hasId(asIdentifier(connection.getSource()))) {
            return false;
        }
        if(!hasId(asIdentifier(connection.getTarget()))) {
            return false;
        }
        var relationship = connection.getArchimateRelationship();
        if(!hasId(relationship)) {
            return false;
        }
        if(!hasId(relationship.getSource())) {
            return false;
        }
        if(!hasId(relationship.getTarget())) {
            return false;
        }
        return true;
    }

    private boolean hasId(IIdentifier value) {
        return value != null && value.getId() != null && !value.getId().isBlank();
    }

    private IIdentifier asIdentifier(Object value) {
        return value instanceof IIdentifier identifier ? identifier : null;
    }

    private void rememberKnownElement(IArchimateElement element) {
        if(element != null && element.getId() != null && !element.getId().isBlank()) {
            knownElementIds.add(element.getId());
        }
    }

    private void forgetKnownElement(IArchimateElement element) {
        if(element != null && element.getId() != null && !element.getId().isBlank()) {
            knownElementIds.remove(element.getId());
        }
    }

    private void rememberSubmittedElement(IArchimateElement element) {
        if(element != null && element.getId() != null && !element.getId().isBlank()) {
            submittedElementIds.add(element.getId());
        }
    }

    private void forgetSubmittedElement(IArchimateElement element) {
        if(element != null && element.getId() != null && !element.getId().isBlank()) {
            submittedElementIds.remove(element.getId());
        }
    }

    private boolean hasSubmittedElement(IArchimateElement element) {
        return element != null
                && element.getId() != null
                && !element.getId().isBlank()
                && submittedElementIds.contains(element.getId());
    }

    private boolean isNewLocalElement(IArchimateElement element) {
        return element != null
                && element.getId() != null
                && !element.getId().isBlank()
                && !knownElementIds.contains(element.getId());
    }

    private void rememberSubmittedRelationship(IArchimateRelationship relationship) {
        if(relationship != null && relationship.getId() != null && !relationship.getId().isBlank()) {
            submittedRelationshipIds.add(relationship.getId());
        }
    }

    private void forgetSubmittedRelationship(IArchimateRelationship relationship) {
        if(relationship != null && relationship.getId() != null && !relationship.getId().isBlank()) {
            submittedRelationshipIds.remove(relationship.getId());
        }
    }

    private boolean hasSubmittedRelationship(IArchimateRelationship relationship) {
        return relationship != null
                && relationship.getId() != null
                && !relationship.getId().isBlank()
                && submittedRelationshipIds.contains(relationship.getId());
    }

    private void rememberSubmittedConnection(IDiagramModelArchimateConnection connection) {
        if(connection != null && connection.getId() != null && !connection.getId().isBlank()) {
            submittedConnectionIds.add(connection.getId());
        }
    }

    private void forgetSubmittedConnection(IDiagramModelArchimateConnection connection) {
        if(connection != null && connection.getId() != null && !connection.getId().isBlank()) {
            submittedConnectionIds.remove(connection.getId());
        }
    }

    private boolean hasSubmittedConnection(IDiagramModelArchimateConnection connection) {
        return connection != null
                && connection.getId() != null
                && !connection.getId().isBlank()
                && submittedConnectionIds.contains(connection.getId());
    }

    private void scheduleNotationUpdate(String key, Runnable task) {
        cancelPending(key);
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            try {
                task.run();
            }
            finally {
                pendingNotationUpdates.remove(key);
            }
        }, NOTATION_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        pendingNotationUpdates.put(key, future);
    }

    private void cancelPending(String key) {
        ScheduledFuture<?> previous = pendingNotationUpdates.remove(key);
        if(previous != null) {
            previous.cancel(false);
        }
    }

    public void close() {
        pendingNotationUpdates.values().forEach(f -> f.cancel(false));
        pendingNotationUpdates.clear();
        pendingConnectionCreates.values().forEach(f -> f.cancel(false));
        pendingConnectionCreates.clear();
        pendingConnectionCreateAttempts.clear();
        pendingViewObjectCreates.values().forEach(f -> f.cancel(false));
        pendingViewObjectCreates.clear();
        pendingViewObjectCreateAttempts.clear();
        knownElementIds.clear();
        submittedElementIds.clear();
        submittedRelationshipIds.clear();
        submittedConnectionIds.clear();
        scheduler.shutdownNow();
    }

    private String eventTypeName(int eventType) {
        return switch(eventType) {
            case Notification.ADD -> "ADD";
            case Notification.ADD_MANY -> "ADD_MANY";
            case Notification.REMOVE -> "REMOVE";
            case Notification.REMOVE_MANY -> "REMOVE_MANY";
            case Notification.SET -> "SET";
            default -> String.valueOf(eventType);
        };
    }

    private String className(Object object) {
        return object == null ? "null" : object.getClass().getSimpleName();
    }

    private void handleFolderAdded(Object notifier, IFolder folder) {
        if(folder == null || isRootFolder(folder)) {
            return;
        }
        String folderId = opMapper.folderId(folder);
        if(folderId == null || folderId.isBlank()) {
            return;
        }
        String parentFolderId = resolveParentFolderId(notifier, folder);
        if(knownFolderIds.add(folderId)) {
            send(opMapper.toCreateFolderSubmitOps(
                    folder,
                    parentFolderId,
                    sessionManager.getCurrentModelId(),
                    sessionManager.getLastKnownRevision(),
                    sessionManager.getUserId(),
                    sessionManager.getSessionId()),
                    "CreateFolder");
            return;
        }
        send(opMapper.toMoveFolderSubmitOps(
                folder,
                parentFolderId,
                sessionManager.getCurrentModelId(),
                sessionManager.getLastKnownRevision(),
                sessionManager.getUserId(),
                sessionManager.getSessionId()),
                "MoveFolder");
    }

    private void handleFolderRemoved(IFolder folder) {
        if(folder == null || isRootFolder(folder)) {
            return;
        }
        if(folder.eContainer() instanceof IFolder || folder.eContainer() instanceof IArchimateModel) {
            return;
        }
        knownFolderIds.remove(opMapper.folderId(folder));
        send(opMapper.toDeleteFolderSubmitOps(
                folder,
                sessionManager.getCurrentModelId(),
                sessionManager.getLastKnownRevision(),
                sessionManager.getUserId(),
                sessionManager.getSessionId()),
                "DeleteFolder");
    }

    private String resolveParentFolderId(Object notifier, IFolder folder) {
        if(notifier instanceof IFolder parentFolder) {
            return opMapper.folderId(parentFolder);
        }
        if(notifier instanceof IArchimateModel) {
            return null;
        }
        return opMapper.parentFolderId(folder);
    }

    private boolean isRootFolder(IFolder folder) {
        return folder != null
                && folder.getType() != null
                && folder.getType() != FolderType.USER
                && folder.eContainer() instanceof IArchimateModel;
    }

    private void seedKnownFolderIds() {
        IArchimateModel model = sessionManager == null ? null : sessionManager.getAttachedModel();
        if(model == null) {
            return;
        }
        for(var it = model.eAllContents(); it.hasNext();) {
            Object next = it.next();
            if(next instanceof IFolder folder && !isRootFolder(folder)) {
                knownFolderIds.add(opMapper.folderId(folder));
            }
        }
    }

    private void seedKnownElementIds() {
        IArchimateModel model = sessionManager == null ? null : sessionManager.getAttachedModel();
        if(model == null) {
            return;
        }
        for(var it = model.eAllContents(); it.hasNext();) {
            Object next = it.next();
            if(next instanceof IArchimateElement element && element.getId() != null && !element.getId().isBlank()) {
                knownElementIds.add(element.getId());
            }
        }
    }
}
