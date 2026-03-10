package com.archimatetool.collab.ui;

import com.archimatetool.collab.util.CollabAuthHints;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.graphics.Point;

/**
 * Dialog for collaboration connection settings.
 */
public class ConnectCollabDialog extends TitleAreaDialog {

    private String wsBaseUrl;
    private String modelId;
    private String modelName;
    private String userId;
    private String sessionId;
    private String authToken;

    private Text wsBaseUrlText;
    private Combo modelIdCombo;
    private Button reloadModelsButton;
    private Text userIdText;
    private Text sessionIdText;
    private Text authTokenText;
    private Label authHintLabel;
    private Label authTokenExpiryLabel;
    private Label authTokenIdentityLabel;
    private final List<ModelCatalogClient.ModelOption> modelOptions = new ArrayList<>();

    public ConnectCollabDialog(Shell parentShell, String wsBaseUrl, String modelId, String userId, String sessionId, String authToken) {
        super(parentShell);
        this.wsBaseUrl = wsBaseUrl;
        this.modelId = modelId;
        this.userId = userId;
        this.sessionId = sessionId;
        this.authToken = authToken;
    }

    @Override
    public void create() {
        super.create();
        setTitle("Connect Collaboration");
        setMessage("Configure websocket endpoint and actor information for model collaboration.");
        getShell().setMinimumSize(760, 680);
    }

    @Override
    protected boolean isResizable() {
        return true;
    }

    @Override
    protected Point getInitialSize() {
        Point size = super.getInitialSize();
        return new Point(Math.max(size.x, 760), Math.max(size.y, 680));
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
        authTokenText.addModifyListener(e -> updateAuthHint());

        createLabel(container, "Auth Hint");
        authHintLabel = new Label(container, SWT.WRAP);
        authHintLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        createLabel(container, "Token Status");
        authTokenExpiryLabel = new Label(container, SWT.WRAP);
        authTokenExpiryLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        createLabel(container, "Token Identity");
        authTokenIdentityLabel = new Label(container, SWT.WRAP);
        authTokenIdentityLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        createLabel(container, "Token Actions");
        Button copyTokenPreviewButton = new Button(container, SWT.PUSH);
        copyTokenPreviewButton.setText("Copy Token Preview");
        copyTokenPreviewButton.addListener(SWT.Selection, e -> copyTokenPreview());
        updateAuthHint();

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

        reloadModelsButton = new Button(modelRow, SWT.PUSH);
        reloadModelsButton.setText("Load");
        reloadModelsButton.addListener(SWT.Selection, e -> reloadModelOptions(true));

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

        if(userId.isEmpty()) {
            userId = "anonymous";
        }

        if(sessionId.isEmpty()) {
            sessionId = "archi-local";
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
            if(showMessageOnFailure || !ws.isBlank()) {
                setErrorMessage("Failed loading model catalog: " + ex.getMessage());
            }
            if(ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private String trimOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private void updateAuthHint() {
        if(authHintLabel == null || authHintLabel.isDisposed()) {
            return;
        }
        String token = trimOrEmpty(authTokenText.getText());
        authHintLabel.setText(CollabAuthHints.describePreflightAuthHint(!token.isEmpty()));
        if(authTokenExpiryLabel != null && !authTokenExpiryLabel.isDisposed()) {
            authTokenExpiryLabel.setText(CollabAuthHints.describeTokenExpiry(token));
        }
        if(authTokenIdentityLabel != null && !authTokenIdentityLabel.isDisposed()) {
            authTokenIdentityLabel.setText(CollabAuthHints.describeTokenIdentity(token));
        }
        authHintLabel.getParent().layout();
    }

    private void copyTokenPreview() {
        if(getShell() == null || getShell().isDisposed()) {
            return;
        }
        String token = trimOrEmpty(authTokenText.getText());
        String payload = "tokenStatus=" + CollabAuthHints.describeTokenExpiry(token)
                + System.lineSeparator()
                + "tokenIdentity=" + CollabAuthHints.describeTokenIdentity(token);
        Clipboard clipboard = new Clipboard(getShell().getDisplay());
        try {
            clipboard.setContents(new Object[] {payload}, new Transfer[] {TextTransfer.getInstance()});
            setMessage("Token preview copied to clipboard.");
        } finally {
            clipboard.dispose();
        }
    }
}
