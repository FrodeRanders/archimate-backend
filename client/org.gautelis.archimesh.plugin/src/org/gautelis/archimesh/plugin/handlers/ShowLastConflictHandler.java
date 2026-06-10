package org.gautelis.archimesh.plugin.handlers;

import java.time.Instant;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;

import org.gautelis.archimesh.plugin.ArchimeshPlugin;
import org.gautelis.archimesh.plugin.ws.ArchimeshSessionManager;

/**
 * Shows details for the latest Archimesh conflict surfaced by replay reconciliation.
 */
public class ShowLastConflictHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        ArchimeshPlugin plugin = ArchimeshPlugin.getInstance();
        if(plugin == null || plugin.getSessionManager() == null) {
            return null;
        }

        ArchimeshSessionManager sessionManager = plugin.getSessionManager();
        ArchimeshSessionManager.ConflictSnapshot conflict = sessionManager.getLastConflictSnapshot();
        Shell shell = HandlerUtil.getActiveShell(event);
        if(shell == null) {
            return null;
        }

        if(conflict == null) {
            MessageDialog.openInformation(shell, "Archimesh Conflict", "No Archimesh conflict has been recorded yet.");
            return null;
        }

        String details = "Time: " + Instant.ofEpochMilli(conflict.occurredAtEpochMs()) + "\n"
                + "Model: " + safe(conflict.modelId()) + "\n"
                + "Op Batch: " + safe(conflict.opBatchId()) + "\n"
                + "Code: " + safe(conflict.code()) + "\n"
                + "Message: " + safe(conflict.message());
        MessageDialog.openInformation(shell, "Last Archimesh Conflict", details);
        return null;
    }

    @Override
    public boolean isEnabled() {
        ArchimeshPlugin plugin = ArchimeshPlugin.getInstance();
        return plugin != null
                && plugin.getSessionManager() != null
                && plugin.getSessionManager().getLastConflictSnapshot() != null;
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "n/a" : value;
    }
}
