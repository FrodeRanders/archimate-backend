package io.archi.collab.endpoint;

import io.archi.collab.model.*;
import io.archi.collab.service.CollaborationService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/admin")
public class AdminEndpoint {

    @Inject
    CollaborationService collaborationService;

    @GET
    @Path("/models")
    @Produces(MediaType.APPLICATION_JSON)
    public List<ModelCatalogEntry> modelCatalog() {
        return collaborationService.getModelCatalog();
    }

    @POST
    @Path("/models/{modelId}")
    @Produces(MediaType.APPLICATION_JSON)
    public ModelCatalogEntry registerModel(@PathParam("modelId") String modelId,
                                           @QueryParam("modelName") String modelName) {
        return collaborationService.registerModel(modelId, modelName);
    }

    @PUT
    @Path("/models/{modelId}")
    @Produces(MediaType.APPLICATION_JSON)
    public ModelCatalogEntry renameModel(@PathParam("modelId") String modelId,
                                         @QueryParam("modelName") String modelName) {
        return collaborationService.renameModel(modelId, modelName);
    }

    @GET
    @Path("/models/{modelId}/status")
    @Produces(MediaType.APPLICATION_JSON)
    public AdminStatus status(@PathParam("modelId") String modelId) {
        return collaborationService.getAdminStatus(modelId);
    }

    @POST
    @Path("/models/{modelId}/rebuild-and-status")
    @Produces(MediaType.APPLICATION_JSON)
    public AdminRebuildStatus rebuildAndStatus(@PathParam("modelId") String modelId) {
        return collaborationService.rebuildAndGetAdminStatus(modelId);
    }

    @POST
    @Path("/models/{modelId}/compact")
    @Produces(MediaType.APPLICATION_JSON)
    public AdminCompactionStatus compact(@PathParam("modelId") String modelId,
                                         @QueryParam("retainRevisions") Long retainRevisions) {
        // Compaction reclaims eligible metadata/op-log history according to committed-horizon watermark policy
        return collaborationService.compactModelMetadata(modelId, retainRevisions);
    }

    @GET
    @Path("/models/{modelId}/window")
    @Produces(MediaType.APPLICATION_JSON)
    public AdminModelWindow window(@PathParam("modelId") String modelId, @QueryParam("limit") Integer limit) {
        return collaborationService.getAdminModelWindow(modelId, limit);
    }

    @GET
    @Path("/models/{modelId}/integrity")
    @Produces(MediaType.APPLICATION_JSON)
    public AdminIntegrityReport integrity(@PathParam("modelId") String modelId) {
        return collaborationService.getAdminIntegrity(modelId);
    }

    @DELETE
    @Path("/models/{modelId}")
    @Produces(MediaType.APPLICATION_JSON)
    public AdminDeleteResult deleteModel(@PathParam("modelId") String modelId,
                                         @QueryParam("force") Boolean force) {
        return collaborationService.deleteModel(modelId, Boolean.TRUE.equals(force));
    }

    @GET
    @Path("/overview")
    @Produces(MediaType.APPLICATION_JSON)
    public List<AdminModelWindow> overview(@QueryParam("limit") Integer limit) {
        // Lightweight aggregated view for operator dashboards
        return collaborationService.getAdminOverview(limit);
    }
}
