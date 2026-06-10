package org.gautelis.archimesh.plugin.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;

import org.gautelis.archimesh.plugin.ArchimeshPlugin;

/**
 * Disconnects the mesh websocket session.
 */
public class ArchimeshDisconnectHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        ArchimeshPlugin plugin = ArchimeshPlugin.getInstance();
        if(plugin != null && plugin.getSessionManager() != null) {
            plugin.getSessionManager().disconnect();
            ArchimeshPlugin.logInfo("Archimesh websocket disconnected");
        }
        return null;
    }

    @Override
    public boolean isEnabled() {
        ArchimeshPlugin plugin = ArchimeshPlugin.getInstance();
        return plugin != null
                && plugin.getSessionManager() != null
                && plugin.getSessionManager().isConnected();
    }
}
