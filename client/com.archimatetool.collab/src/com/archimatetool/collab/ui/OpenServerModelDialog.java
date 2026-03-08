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
    private String modelRef = "HEAD";
    private String userId;
    private String sessionId;
    private String authToken;

    private Text wsBaseUrlText;
    private Combo modelIdCombo;
    private Text modelNameReadOnlyText;
    private Combo modelRefCombo;
    private Text modelRefModeReadOnlyText;
    private Button reloadModelsButton;
    private Text userIdText;
    private Text sessionIdText;
    private Text authTokenText;
    private final List<ModelCatalogClient.ModelOption> modelOptions = new ArrayList<>();
    private final List<ModelCatalogClient.ModelTagOption> modelTagOptions = new ArrayList<>();

    public OpenServerModelDialog(Shell parentShell, String wsBaseUrl, String modelId, String modelName, String modelRef, String userId, String sessionId, String authToken) {
        super(parentShell);
        this.wsBaseUrl = wsBaseUrl;
        this.modelId = modelId;
        this.modelName = modelName;
        this.modelRef = modelRef == null || modelRef.isBlank() ? "HEAD" : modelRef.trim();
        this.userId = userId;
        this.sessionId = sessionId;
        this.authToken = authToken;
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

        createLabel(container, "Bearer Token");
        authTokenText = createText(container, authToken, "optional for oidc mode");

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

        createLabel(container, "Reference");
        modelRefCombo = new Combo(container, SWT.DROP_DOWN | SWT.READ_ONLY);
        modelRefCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        modelRefCombo.setToolTipText("HEAD is writable. Tags are immutable read-only snapshots.");
        modelRefCombo.addListener(SWT.Selection, e -> updateModelRefDisplay());

        createLabel(container, "Reference Mode");
        modelRefModeReadOnlyText = new Text(container, SWT.BORDER | SWT.READ_ONLY);
        modelRefModeReadOnlyText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

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
        authToken = trimOrEmpty(authTokenText.getText());

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
        modelRef = selectedModelRef();
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

    public String getModelRef() {
        return modelRef;
    }

    public String getUserId() {
        return userId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getAuthToken() {
        return authToken;
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
        String token = trimOrEmpty(authTokenText.getText());
        try {
            List<ModelCatalogClient.ModelOption> loaded = ModelCatalogClient.fetchModels(ws, token);
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
            clearTagOptions();
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
        reloadTagOptions(false);
    }

    private void reloadTagOptions(boolean showMessageOnFailure) {
        clearTagOptions();
        int selectedIndex = modelIdCombo.getSelectionIndex();
        if(selectedIndex < 0 || selectedIndex >= modelOptions.size()) {
            return;
        }
        String ws = trimOrEmpty(wsBaseUrlText.getText());
        String token = trimOrEmpty(authTokenText.getText());
        String selectedModelId = modelOptions.get(selectedIndex).modelId();
        try {
            List<ModelCatalogClient.ModelTagOption> loaded = ModelCatalogClient.fetchTags(ws, selectedModelId, token);
            modelTagOptions.clear();
            modelTagOptions.addAll(loaded);
            modelRefCombo.add("HEAD (writable)");
            for(ModelCatalogClient.ModelTagOption option : modelTagOptions) {
                modelRefCombo.add(option.label());
            }
            int refIndex = 0;
            if(modelRef != null && !modelRef.isBlank() && !"HEAD".equalsIgnoreCase(modelRef)) {
                for(int i = 0; i < modelTagOptions.size(); i++) {
                    if(modelRef.equals(modelTagOptions.get(i).ref())) {
                        refIndex = i + 1;
                        break;
                    }
                }
            }
            modelRefCombo.select(refIndex);
            updateModelRefDisplay();
            if(!modelOptions.isEmpty()) {
                setErrorMessage(null);
            }
        }
        catch(IOException | InterruptedException ex) {
            modelRefCombo.add("HEAD (writable)");
            modelRefCombo.select(0);
            updateModelRefDisplay();
            if(showMessageOnFailure || !ws.isBlank()) {
                setErrorMessage("Failed loading model tags: " + ex.getMessage());
            }
            if(ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void clearTagOptions() {
        modelTagOptions.clear();
        modelRefCombo.removeAll();
        modelRefCombo.add("HEAD (writable)");
        modelRefCombo.select(0);
        updateModelRefDisplay();
    }

    private void updateModelRefDisplay() {
        String ref = selectedModelRef();
        modelRefModeReadOnlyText.setText("HEAD".equalsIgnoreCase(ref) ? "Writable live collaboration tip" : "Read-only tagged snapshot");
    }

    private String selectedModelRef() {
        int selectedRefIndex = modelRefCombo.getSelectionIndex();
        if(selectedRefIndex <= 0) {
            return "HEAD";
        }
        int tagIndex = selectedRefIndex - 1;
        if(tagIndex < 0 || tagIndex >= modelTagOptions.size()) {
            return "HEAD";
        }
        return modelTagOptions.get(tagIndex).ref();
    }

    private String trimOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
