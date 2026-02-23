package com.archimatetool.collab.ui;

import java.util.UUID;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * Dialog for opening a collaboration-backed model directly from server.
 */
public class OpenServerModelDialog extends TitleAreaDialog {

    private String wsBaseUrl;
    private String modelId;
    private String modelName;
    private String userId;
    private String sessionId;

    private Text wsBaseUrlText;
    private Combo modelIdCombo;
    private Text modelNameReadOnlyText;
    private Button reloadModelsButton;
    private Text userIdText;
    private Text sessionIdText;
    private final List<ModelCatalogClient.ModelOption> modelOptions = new ArrayList<>();

    public OpenServerModelDialog(Shell parentShell, String wsBaseUrl, String modelId, String modelName, String userId, String sessionId) {
        super(parentShell);
        this.wsBaseUrl = wsBaseUrl;
        this.modelId = modelId;
        this.modelName = modelName;
        this.userId = userId;
        this.sessionId = sessionId;
    }

    @Override
    public void create() {
        super.create();
        setTitle("Open Collaboration Model From Server");
        setMessage("Open an in-memory model from the collaboration server and connect immediately.");
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite)super.createDialogArea(parent);
        Composite container = new Composite(area, SWT.NONE);
        container.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        container.setLayout(new GridLayout(2, false));

        createLabel(container, "WebSocket Base URL");
        wsBaseUrlText = createText(container, wsBaseUrl, "ws://localhost:8081");

        createLabel(container, "Model");
        Composite modelRow = new Composite(container, SWT.NONE);
        modelRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout modelRowLayout = new GridLayout(2, false);
        modelRowLayout.marginWidth = 0;
        modelRowLayout.marginHeight = 0;
        modelRow.setLayout(modelRowLayout);

        modelIdCombo = new Combo(modelRow, SWT.DROP_DOWN | SWT.READ_ONLY);
        modelIdCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        modelIdCombo.setToolTipText("Select an existing model from central admin catalog");
        modelIdCombo.addListener(SWT.Selection, e -> updateModelNameDisplay());

        reloadModelsButton = new Button(modelRow, SWT.PUSH);
        reloadModelsButton.setText("Load");
        reloadModelsButton.addListener(SWT.Selection, e -> reloadModelOptions(true));

        createLabel(container, "Central Model Name");
        modelNameReadOnlyText = new Text(container, SWT.BORDER | SWT.READ_ONLY);
        modelNameReadOnlyText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        createLabel(container, "User ID");
        userIdText = createText(container, userId, "anonymous");

        createLabel(container, "Session ID");
        sessionIdText = createText(container, sessionId, "auto-generated if empty");

        reloadModelOptions(false);
        return area;
    }

    @Override
    protected void okPressed() {
        wsBaseUrl = trimOrEmpty(wsBaseUrlText.getText());
        userId = trimOrEmpty(userIdText.getText());
        sessionId = trimOrEmpty(sessionIdText.getText());

        if(wsBaseUrl.isEmpty()) {
            setErrorMessage("WebSocket base URL is required");
            return;
        }
        int selectedIndex = modelIdCombo.getSelectionIndex();
        if(selectedIndex < 0 || selectedIndex >= modelOptions.size()) {
            setErrorMessage("Select an existing model from server");
            return;
        }
        modelId = modelOptions.get(selectedIndex).modelId();
        modelName = modelOptions.get(selectedIndex).modelName();
        if(userId.isEmpty()) {
            userId = "anonymous";
        }
        if(sessionId.isEmpty()) {
            sessionId = "archi-open-" + UUID.randomUUID();
        }

        super.okPressed();
    }

    public String getWsBaseUrl() {
        return wsBaseUrl;
    }

    public String getModelId() {
        return modelId;
    }

    public String getModelName() {
        return modelName;
    }

    public String getUserId() {
        return userId;
    }

    public String getSessionId() {
        return sessionId;
    }

    private void createLabel(Composite parent, String text) {
        Label label = new Label(parent, SWT.NONE);
        label.setText(text);
    }

    private Text createText(Composite parent, String value, String message) {
        Text text = new Text(parent, SWT.BORDER);
        text.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        text.setMessage(message);
        text.setText(value == null ? "" : value);
        return text;
    }

    private void reloadModelOptions(boolean showMessageOnFailure) {
        String ws = trimOrEmpty(wsBaseUrlText.getText());
        try {
            List<ModelCatalogClient.ModelOption> loaded = ModelCatalogClient.fetchModels(ws);
            modelOptions.clear();
            modelOptions.addAll(loaded);
            modelIdCombo.removeAll();
            for(ModelCatalogClient.ModelOption option : modelOptions) {
                modelIdCombo.add(option.label());
            }

            int selectIndex = -1;
            if(modelId != null && !modelId.isBlank()) {
                for(int i = 0; i < modelOptions.size(); i++) {
                    if(modelId.equals(modelOptions.get(i).modelId())) {
                        selectIndex = i;
                        break;
                    }
                }
            }
            if(selectIndex < 0 && !modelOptions.isEmpty()) {
                selectIndex = 0;
            }
            if(selectIndex >= 0) {
                modelIdCombo.select(selectIndex);
            }
            updateModelNameDisplay();
            if(modelOptions.isEmpty()) {
                setErrorMessage("No existing models found in server catalog. Create models in admin UI first.");
            }
            else {
                setErrorMessage(null);
            }
        }
        catch(IOException | InterruptedException ex) {
            modelOptions.clear();
            modelIdCombo.removeAll();
            modelNameReadOnlyText.setText("");
            if(showMessageOnFailure || !ws.isBlank()) {
                setErrorMessage("Failed loading model catalog: " + ex.getMessage());
            }
            if(ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void updateModelNameDisplay() {
        int selectedIndex = modelIdCombo.getSelectionIndex();
        if(selectedIndex < 0 || selectedIndex >= modelOptions.size()) {
            modelNameReadOnlyText.setText("");
            return;
        }
        String centralName = modelOptions.get(selectedIndex).modelName();
        modelNameReadOnlyText.setText(centralName == null || centralName.isBlank() ? "(not set)" : centralName);
    }

    private String trimOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
