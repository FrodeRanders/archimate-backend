package io.archi.collab.client;

import com.archimatetool.collab.emf.OpMapper;
import com.archimatetool.collab.emf.RemoteOpApplier;
import com.archimatetool.collab.ws.CollabSessionManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.INameable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

class FolderSyncClientTest {

    @Test
    void checkoutSnapshotRestoresNestedFoldersAndMembers() {
        CollabSessionManager sessionManager = new CollabSessionManager();
        IArchimateModel model = IArchimateFactory.eINSTANCE.createArchimateModel();
        ensureRootFolder(model, FolderType.BUSINESS);
        ensureRootFolder(model, FolderType.RELATIONS);
        ensureRootFolder(model, FolderType.DIAGRAMS);
        sessionManager.attachModel(model);

        RemoteOpApplier applier = new RemoteOpApplier(sessionManager);
        applySnapshotEnvelopeDirect(applier, folderSnapshotEnvelope());

        IFolder businessRoot = model.getFolder(FolderType.BUSINESS);
        IFolder relationsRoot = model.getFolder(FolderType.RELATIONS);
        IFolder diagramsRoot = model.getFolder(FolderType.DIAGRAMS);

        IFolder capabilities = folderByName(businessRoot, "Capabilities");
        IFolder payments = folderByName(capabilities, "Payments");
        IFolder relationFolder = folderByName(relationsRoot, "Dependencies");
        IFolder viewFolder = folderByName(diagramsRoot, "Published");

        Assertions.assertNotNull(capabilities);
        Assertions.assertNotNull(payments);
        Assertions.assertNotNull(relationFolder);
        Assertions.assertNotNull(viewFolder);
        Assertions.assertEquals("Folder element", ((INameable) payments.getElements().get(0)).getName());
        Assertions.assertEquals("Folder relation", ((IArchimateRelationship) relationFolder.getElements().get(0)).getName());
        Assertions.assertEquals("Overview", ((IDiagramModel) viewFolder.getElements().get(0)).getName());
    }

    @Test
    void opMapperUsesStableFolderIdsAcrossRenameAndMove() {
        OpMapper mapper = new OpMapper();

        IArchimateModel model = IArchimateFactory.eINSTANCE.createArchimateModel();
        ensureRootFolder(model, FolderType.BUSINESS);
        IFolder businessRoot = model.getFolder(FolderType.BUSINESS);
        IFolder folder = IArchimateFactory.eINSTANCE.createFolder();
        folder.setId("folder-123");
        folder.setType(FolderType.USER);
        folder.setName("Capabilities");
        businessRoot.getFolders().add(folder);

        String initialId = mapper.folderId(folder);
        folder.setName("Capabilities Shared");

        String updateJson = mapper.toUpdateFolderSubmitOps(folder, "demo", 7L, "alice", "session-1", true, false);
        String moveJson = mapper.toMoveFolderSubmitOps(folder, "folder:root-business", "demo", 7L, "alice", "session-1");

        Assertions.assertEquals("folder:folder-123", initialId);
        Assertions.assertTrue(updateJson.contains("\"folderId\":\"folder:folder-123\""), updateJson);
        Assertions.assertTrue(updateJson.contains("\"name\":\"Capabilities Shared\""), updateJson);
        Assertions.assertTrue(moveJson.contains("\"folderId\":\"folder:folder-123\""), moveJson);
        Assertions.assertTrue(moveJson.contains("\"parentFolderId\":\"folder:root-business\""), moveJson);
    }

    private static IFolder folderByName(IFolder parent, String name) {
        if (parent == null) {
            return null;
        }
        for (IFolder folder : parent.getFolders()) {
            if (name.equals(folder.getName())) {
                return folder;
            }
        }
        return null;
    }

    private static void applySnapshotEnvelopeDirect(RemoteOpApplier applier, String envelopeJson) {
        try {
            Method method = RemoteOpApplier.class.getDeclaredMethod("applySnapshotEnvelopeInternal", String.class);
            method.setAccessible(true);
            method.invoke(applier, envelopeJson);
        }
        catch (Exception ex) {
            throw new AssertionError("Failed to invoke snapshot apply", ex);
        }
    }

    private static void ensureRootFolder(IArchimateModel model, FolderType type) {
        if (model == null || type == null || model.getFolder(type) != null) {
            return;
        }
        IFolder folder = IArchimateFactory.eINSTANCE.createFolder();
        folder.setId("root-" + type.name().toLowerCase());
        folder.setType(type);
        folder.setName(type.name());
        model.getFolders().add(folder);
    }

    private static String folderSnapshotEnvelope() {
        return """
                {
                  "type": "CheckoutSnapshot",
                  "payload": {
                    "snapshot": {
                      "format": "archimate-materialized-v1",
                      "modelId": "demo",
                      "headRevision": 7,
                      "folders": [
                        {"id":"folder:f-cap","folderType":"USER","name":"Capabilities","parentFolderId":"folder:root-business"},
                        {"id":"folder:f-pay","folderType":"USER","name":"Payments","parentFolderId":"folder:f-cap"},
                        {"id":"folder:f-rel","folderType":"USER","name":"Dependencies","parentFolderId":"folder:root-relations"},
                        {"id":"folder:f-view","folderType":"USER","name":"Published","parentFolderId":"folder:root-diagrams"}
                      ],
                      "elements": [
                        {"id":"elem:src","archimateType":"BusinessActor","name":"Source"},
                        {"id":"elem:dst","archimateType":"BusinessActor","name":"Target"},
                        {"id":"elem:folder-elt","archimateType":"BusinessActor","name":"Folder element"}
                      ],
                      "relationships": [
                        {"id":"rel:r1","archimateType":"AssociationRelationship","name":"Folder relation","sourceId":"elem:src","targetId":"elem:dst"}
                      ],
                      "views": [
                        {"id":"view:v1","name":"Overview","notationJson":{}}
                      ],
                      "elementFolderMembers": [
                        {"elementId":"elem:folder-elt","folderId":"folder:f-pay"}
                      ],
                      "relationshipFolderMembers": [
                        {"relationshipId":"rel:r1","folderId":"folder:f-rel"}
                      ],
                      "viewFolderMembers": [
                        {"viewId":"view:v1","folderId":"folder:f-view"}
                      ],
                      "viewObjects": [],
                      "viewObjectChildMembers": [],
                      "connections": []
                    }
                  }
                }
                """;
    }
}
