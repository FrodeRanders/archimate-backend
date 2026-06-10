package org.gautelis.archimesh.plugin;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

import org.gautelis.archimesh.plugin.startup.ArchimeshStatusLineBridge;
import org.gautelis.archimesh.plugin.startup.WorkbenchModelLifecycleBridge;
import org.gautelis.archimesh.plugin.ws.ArchimeshSessionManager;

/**
 * Archimesh plugin entry point.
 */
public class ArchimeshPlugin extends AbstractUIPlugin {

    public static final String PLUGIN_ID = "org.gautelis.archimesh.plugin";

    private static ArchimeshPlugin instance;
    private static volatile boolean debugEnabled =
            Boolean.parseBoolean(System.getProperty("archimesh.debug", "false"))
                    || "true".equalsIgnoreCase(System.getenv("ARCHIMESH_DEBUG"));
    private static volatile boolean traceEnabled =
            Boolean.parseBoolean(System.getProperty("archimesh.trace", "false"))
                    || "true".equalsIgnoreCase(System.getenv("ARCHIMESH_TRACE"));

    private ArchimeshSessionManager sessionManager;
    private WorkbenchModelLifecycleBridge lifecycleBridge;
    private ArchimeshStatusLineBridge statusLineBridge;
    private boolean lifecycleInitialized;

    public ArchimeshPlugin() {
        instance = this;
    }

    public static ArchimeshPlugin getInstance() {
        return instance;
    }

    public static void logInfo(String message) {
        if(instance != null) {
            instance.getLog().log(new Status(IStatus.INFO, PLUGIN_ID, message));
        }
    }

    public static void logDebug(String message) {
        if(debugEnabled) {
            logInfo("[DEBUG] " + message);
        }
    }

    public static boolean isDebugEnabled() {
        return debugEnabled;
    }

    public static void logTrace(String message) {
        if(traceEnabled) {
            logInfo("[TRACE] " + message);
        }
    }

    public static boolean isTraceEnabled() {
        return traceEnabled;
    }

    public static void logError(String message, Throwable throwable) {
        if(instance != null) {
            instance.getLog().log(new Status(IStatus.ERROR, PLUGIN_ID, message, throwable));
        }
    }

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        sessionManager = new ArchimeshSessionManager();
        lifecycleBridge = new WorkbenchModelLifecycleBridge(sessionManager);
        statusLineBridge = new ArchimeshStatusLineBridge(sessionManager);
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        if(lifecycleBridge != null) {
            try {
                lifecycleBridge.stop();
            }
            catch(Exception ex) {
                logError("Error stopping Archimesh lifecycle bridge", ex);
            }
        }
        lifecycleBridge = null;

        if(statusLineBridge != null) {
            try {
                statusLineBridge.stop();
            }
            catch(Exception ex) {
                logError("Error stopping Archimesh status bridge", ex);
            }
        }
        statusLineBridge = null;

        if(sessionManager != null) {
            try {
                sessionManager.disconnect();
            }
            catch(Exception ex) {
                logError("Error disconnecting Archimesh session manager", ex);
            }
        }
        sessionManager = null;
        instance = null;
        super.stop(context);
    }

    public synchronized void initializeLifecycleIfNeeded() {
        if(lifecycleInitialized || lifecycleBridge == null) {
            return;
        }

        lifecycleBridge.start();
        if(statusLineBridge != null) {
            statusLineBridge.start();
        }
        lifecycleInitialized = true;
    }

    public ArchimeshSessionManager getSessionManager() {
        return sessionManager;
    }
}
