package org.gautelis.archimesh.plugin.emf;

import org.gautelis.archimesh.plugin.ArchimeshPlugin;
import org.gautelis.archimesh.plugin.ws.ArchimeshSessionManager;
import com.archimatetool.model.IArchimateModel;

/**
 * Binds EMF capture to a model and sends mapped ops through the websocket session manager.
 */
public class ArchimeshModelController {

    private final ArchimeshSessionManager sessionManager;
    private EmfChangeCapture changeCapture;
    private IArchimateModel model;

    public ArchimeshModelController(ArchimeshSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    public void attach(IArchimateModel model) {
        // Always detach first so we never register multiple adapters on one model lifecycle
        detach();
        this.changeCapture = new EmfChangeCapture(sessionManager);
        this.model = model;
        this.model.eAdapters().add(changeCapture);
        ArchimeshPlugin.logInfo("Attached Archimesh change capture to model");
    }

    public void detach() {
        if(model != null) {
            if(changeCapture != null) {
                model.eAdapters().remove(changeCapture);
            }
            model = null;
        }
        if(changeCapture != null) {
            // Release scheduler/resources owned by the capture adapter
            changeCapture.close();
            changeCapture = null;
        }
    }
}
