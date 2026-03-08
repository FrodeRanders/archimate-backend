package com.archimatetool.collab.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.ui.handlers.HandlerUtil;

import com.archimatetool.collab.ArchiCollabPlugin;
import com.archimatetool.collab.ui.ConnectCollabDialog;
import com.archimatetool.collab.ws.CollabSessionManager;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IIdentifier;

/**
 * Switches the active local model to server-backed collaboration mode.
 */
public class SwitchActiveModelServerBackedHandler extends AbstractHandler {

    private static final String DEFAULT_WS_BASE_URL = "ws://localhost:8081";

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        ArchiCollabPlugin plugin = ArchiCollabPlugin.getInstance();
        if(plugin == null || plugin.getSessionManager() == null) {
            return null;
        }

        IArchimateModel model = getActiveModel(event);
        if(model == null) {
            ArchiCollabPlugin.logInfo("Switch active model skipped: no active model found");
            return null;
        }

        CollabSessionManager sessionManager = plugin.getSessionManager();
        String defaultModelId = sessionManager.getCurrentModelId();
        if((defaultModelId == null || defaultModelId.isBlank()) && model instanceof IIdentifier identifier) {
            defaultModelId = identifier.getId();
        }

        ConnectCollabDialog dialog = new ConnectCollabDialog(
                HandlerUtil.getActiveShell(event),
                DEFAULT_WS_BASE_URL,
                defaultModelId,
                sessionManager.getUserId(),
                sessionManager.getSessionId(),
                sessionManager.getAuthToken());
        if(dialog.open() != Window.OK) {
            return null;
        }

        sessionManager.setActor(dialog.getUserId(), dialog.getSessionId());
        sessionManager.setAuthToken(dialog.getAuthToken());
        sessionManager.setServerBackedSession(true);
        sessionManager.connect(dialog.getWsBaseUrl(), dialog.getModelId());
        if(sessionManager.isConnected()) {
            sessionManager.attachModel(model);
            ArchiCollabPlugin.logInfo("Active model switched to server-backed collaboration modelId=" + dialog.getModelId());
        }
        else {
            ArchiCollabPlugin.logInfo("Failed switching active model to server-backed modelId=" + dialog.getModelId());
            MessageDialog.openError(
                    HandlerUtil.getActiveShell(event),
                    "Switch To Collaboration Failed",
                    sessionManager.getLastUserHint() == null || sessionManager.getLastUserHint().isBlank()
                            ? "The active model could not be switched to the collaboration server."
                            : sessionManager.getLastUserHint());
        }
        return null;
    }

    @Override
    public boolean isEnabled() {
        ArchiCollabPlugin plugin = ArchiCollabPlugin.getInstance();
        return plugin != null && plugin.getSessionManager() != null;
    }

    private IArchimateModel getActiveModel(ExecutionEvent event) {
        if(HandlerUtil.getActivePart(event) == null) {
            return null;
        }
        return HandlerUtil.getActivePart(event).getAdapter(IArchimateModel.class);
    }
}
