package org.gautelis.archimesh.plugin.startup;

import org.eclipse.ui.IStartup;

import org.gautelis.archimesh.plugin.ArchimeshPlugin;

/**
 * Early startup hook for mesh wiring.
 */
public class ArchimeshStartup implements IStartup {
    private final StartupServerModelBootstrap startupServerModelBootstrap = new StartupServerModelBootstrap();

    @Override
    public void earlyStartup() {
        ArchimeshPlugin plugin = ArchimeshPlugin.getInstance();
        if(plugin != null) {
            plugin.initializeLifecycleIfNeeded();
            startupServerModelBootstrap.maybeBootstrap(plugin);
        }
        ArchimeshPlugin.logInfo("Archimesh startup initialized");
        // Auto-connect remains opt-in through startup pull configuration.
    }
}
