package io.archi.collab.client;

import com.archimatetool.collab.emf.OpMapper;
import com.archimatetool.collab.util.SimpleJson;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IBusinessObject;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IArchimateDiagramModel;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class OpMapperConnectionBatchTest {

    @Test
    void createConnectionWithRelationshipUsesSingleOrderedTwoOpBatch() {
        OpMapper mapper = new OpMapper();
        IArchimateDiagramModel view = IArchimateFactory.eINSTANCE.createArchimateDiagramModel();
        view.setId("view-1");

        IBusinessObject sourceElement = IArchimateFactory.eINSTANCE.createBusinessObject();
        sourceElement.setId("elem-1");
        IBusinessObject targetElement = IArchimateFactory.eINSTANCE.createBusinessObject();
        targetElement.setId("elem-2");

        IArchimateRelationship relationship = IArchimateFactory.eINSTANCE.createAssociationRelationship();
        relationship.setId("rel-1");
        relationship.setSource(sourceElement);
        relationship.setTarget(targetElement);

        IDiagramModelArchimateObject sourceViewObject = IArchimateFactory.eINSTANCE.createDiagramModelArchimateObject();
        sourceViewObject.setId("vo-1");
        sourceViewObject.setArchimateElement(sourceElement);
        view.getChildren().add(sourceViewObject);

        IDiagramModelArchimateObject targetViewObject = IArchimateFactory.eINSTANCE.createDiagramModelArchimateObject();
        targetViewObject.setId("vo-2");
        targetViewObject.setArchimateElement(targetElement);
        view.getChildren().add(targetViewObject);

        IDiagramModelArchimateConnection connection = IArchimateFactory.eINSTANCE.createDiagramModelArchimateConnection();
        connection.setId("conn-1");
        connection.setArchimateRelationship(relationship);
        connection.setSource(sourceViewObject);
        connection.setTarget(targetViewObject);

        String submit = mapper.toCreateConnectionWithRelationshipSubmitOps(connection, "demo", 7L, "user", "session");
        String payload = SimpleJson.asJsonObject(SimpleJson.readRawField(submit, "payload"));
        Assertions.assertNotNull(payload);
        Assertions.assertEquals("demo", SimpleJson.readStringField(payload, "modelId"));
        Assertions.assertEquals(Long.valueOf(7L), SimpleJson.readLongField(payload, "baseRevision"));

        String rawOps = SimpleJson.readRawField(payload, "ops");
        List<String> ops = SimpleJson.splitArrayObjects(rawOps);
        Assertions.assertEquals(2, ops.size(), "connection creation should batch relationship+connection");
        Assertions.assertEquals("CreateRelationship", SimpleJson.readStringField(ops.get(0), "type"));
        Assertions.assertEquals("CreateConnection", SimpleJson.readStringField(ops.get(1), "type"));
    }
}
