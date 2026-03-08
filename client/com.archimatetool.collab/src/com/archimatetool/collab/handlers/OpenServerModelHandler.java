package com.archimatetool.collab.handlers;

import java.io.File;
import java.nio.file.Path;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.ui.handlers.HandlerUtil;

import com.archimatetool.collab.ArchiCollabPlugin;
import com.archimatetool.collab.ui.OpenServerModelDialog;
import com.archimatetool.collab.ws.CollabSessionManager;
import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IIdentifier;

/**
 * Opens a collaboration-backed model from server and connects immediately.
 */
public class OpenServerModelHandler extends AbstractHandler {

    private static final String DEFAULT_WS_BASE_URL = "ws://localhost:8081";

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        ArchiCollabPlugin plugin = ArchiCollabPlugin.getInstance();
        if(plugin == null || plugin.getSessionManager() == null) {
            return null;
        }

        CollabSessionManager sessionManager = plugin.getSessionManager();
        OpenServerModelDialog dialog = new OpenServerModelDialog(
                HandlerUtil.getActiveShell(event),
                DEFAULT_WS_BASE_URL,
                sessionManager.getCurrentModelId(),
                "",
                sessionManager.getCurrentModelRef(),
                sessionManager.getUserId(),
                sessionManager.getSessionId(),
                sessionManager.getAuthToken());

        if(dialog.open() != Window.OK) {
            return null;
        }

        IArchimateModel model = findAlreadyOpenModel(dialog.getModelId(), dialog.getModelRef());
        boolean reusedExistingModel = model != null;
        if(model == null) {
            model = IArchimateFactory.eINSTANCE.createArchimateModel();
            model.setDefaults();
            if(model instanceof IIdentifier identifier) {
                identifier.setId(dialog.getModelId());
            }
            String modelName = dialog.getModelName();
            String effectiveName = modelName == null || modelName.isBlank()
                    ? "Collaboration: " + dialog.getModelId()
                    : modelName;
            if(!"HEAD".equalsIgnoreCase(dialog.getModelRef())) {
                effectiveName = effectiveName + " @" + dialog.getModelRef();
            }
            model.setName(effectiveName);
            IEditorModelManager.INSTANCE.openModel(model);
        }

        boolean alreadyConnectedSameModel = sessionManager.isConnected()
                && dialog.getModelId().equals(sessionManager.getCurrentModelId())
                && dialog.getModelRef().equalsIgnoreCase(sessionManager.getCurrentModelRef());
        if(alreadyConnectedSameModel && reusedExistingModel) {
            if(sessionManager.getAttachedModel() != model) {
                sessionManager.attachModel(model);
            }
            ArchiCollabPlugin.logInfo("Open collaboration model skipped reconnect: already connected modelId=" + dialog.getModelId());
            return null;
        }

        sessionManager.setActor(dialog.getUserId(), dialog.getSessionId());
        sessionManager.setAuthToken(dialog.getAuthToken());
        sessionManager.setServerBackedSession(true);
        sessionManager.connect(dialog.getWsBaseUrl(), dialog.getModelId(), dialog.getModelRef(), true);
        if(sessionManager.isConnected()) {
            sessionManager.attachModel(model);
            if(reusedExistingModel) {
                ArchiCollabPlugin.logInfo("Reused already-open collaboration model modelId=" + dialog.getModelId());
            }
            ArchiCollabPlugin.logInfo("Opened collaboration model from server modelId=" + dialog.getModelId()
                    + " ref=" + dialog.getModelRef());
        }
        else {
            ArchiCollabPlugin.logInfo("Failed to open collaboration model from server modelId=" + dialog.getModelId()
                    + " ref=" + dialog.getModelRef());
            MessageDialog.openError(
                    HandlerUtil.getActiveShell(event),
                    "Open Collaboration Model Failed",
                    sessionManager.getLastUserHint() == null || sessionManager.getLastUserHint().isBlank()
                            ? "The collaboration model could not be opened from the server."
                            : sessionManager.getLastUserHint());
        }

        return null;
    }

    @Override
    public boolean isEnabled() {
        ArchiCollabPlugin plugin = ArchiCollabPlugin.getInstance();
        return plugin != null && plugin.getSessionManager() != null;
    }

    private IArchimateModel findAlreadyOpenModel(String modelId, String modelRef) {
        File expectedCacheFile = expectedCacheFile(modelId, modelRef);
        for(IArchimateModel candidate : IEditorModelManager.INSTANCE.getModels()) {
            if(candidate == null) {
                continue;
            }
            if(expectedCacheFile != null && candidate.getFile() != null
                    && expectedCacheFile.equals(candidate.getFile())) {
                return candidate;
            }
            if("HEAD".equalsIgnoreCase(modelRef)
                    && candidate instanceof IIdentifier identifier
                    && modelId.equals(identifier.getId())
                    && candidate.getFile() == null) {
                return candidate;
            }
        }
        return null;
    }

    private File expectedCacheFile(String modelId, String modelRef) {
        String cacheKey = (modelId == null ? "" : modelId) + "__" + ((modelRef == null || modelRef.isBlank()) ? "HEAD" : modelRef.trim());
        String safeCacheKey = cacheKey.replaceAll("[^a-zA-Z0-9._-]", "_");
        if(safeCacheKey.isBlank()) {
            return null;
        }
        String userHome = System.getProperty("user.home", "");
        if(userHome.isBlank()) {
            return null;
        }
        return Path.of(userHome, "Archi", "collab-cache", safeCacheKey + ".archimate").toFile();
    }
}
