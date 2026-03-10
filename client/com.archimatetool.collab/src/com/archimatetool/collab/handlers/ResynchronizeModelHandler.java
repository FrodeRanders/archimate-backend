package com.archimatetool.collab.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.handlers.HandlerUtil;

import com.archimatetool.collab.ArchiCollabPlugin;
import com.archimatetool.collab.emf.ModelProjectionReset;
import com.archimatetool.collab.ws.CollabSessionManager;
import com.archimatetool.model.IArchimateModel;

/**
 * Forces a cold snapshot rebuild for the active attached model.
 */
public class ResynchronizeModelHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        ArchiCollabPlugin plugin = ArchiCollabPlugin.getInstance();
        if(plugin == null || plugin.getSessionManager() == null) {
            return null;
        }

        CollabSessionManager sessionManager = plugin.getSessionManager();
        IArchimateModel model = getActiveModel(event);
        if(model == null) {
            model = sessionManager.getAttachedModel();
        }
        if(model == null) {
            MessageDialog.openInformation(
                    HandlerUtil.getActiveShell(event),
                    "Resynchronize Model",
                    "No active collaboration model is attached.");
            return null;
        }

        String wsBaseUrl = sessionManager.getCurrentWsBaseUrl();
        String modelId = sessionManager.getCurrentModelId();
        String modelRef = sessionManager.getCurrentModelRef();
        if(wsBaseUrl == null || wsBaseUrl.isBlank() || modelId == null || modelId.isBlank()) {
            MessageDialog.openInformation(
                    HandlerUtil.getActiveShell(event),
                    "Resynchronize Model",
                    "The active model is not connected to a collaboration server.");
            return null;
        }

        boolean confirmed = MessageDialog.openConfirm(
                HandlerUtil.getActiveShell(event),
                "Resynchronize Model",
                "This will clear the local collaboration projection and pull a fresh snapshot from the server.\n\nContinue?");
        if(!confirmed) {
            return null;
        }

        sessionManager.connect(wsBaseUrl, modelId, modelRef, true);
        if(!sessionManager.isConnected()) {
            MessageDialog.openError(
                    HandlerUtil.getActiveShell(event),
                    "Resynchronize Model Failed",
                    sessionManager.getLastUserHint() == null || sessionManager.getLastUserHint().isBlank()
                            ? "The collaboration connection could not be re-established."
                            : sessionManager.getLastUserHint());
            return null;
        }

        ModelProjectionReset.clear(model);
        sessionManager.attachModel(model);
        ArchiCollabPlugin.logInfo("Forced model resynchronization modelId=" + modelId + " ref=" + modelRef);
        return null;
    }

    @Override
    public boolean isEnabled() {
        ArchiCollabPlugin plugin = ArchiCollabPlugin.getInstance();
        if(plugin == null || plugin.getSessionManager() == null) {
            return false;
        }
        CollabSessionManager sessionManager = plugin.getSessionManager();
        return sessionManager.isConnected() && sessionManager.getAttachedModel() != null;
    }

    private IArchimateModel getActiveModel(ExecutionEvent event) {
        if(HandlerUtil.getActivePart(event) == null) {
            return null;
        }
        return HandlerUtil.getActivePart(event).getAdapter(IArchimateModel.class);
    }
}
