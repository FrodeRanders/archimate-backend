package io.archi.collab.endpoint;

import io.archi.collab.model.AdminRebuildStatus;
import io.archi.collab.model.AdminModelWindow;
import io.archi.collab.model.AdminStatus;
import io.archi.collab.model.AdminIntegrityReport;
import io.archi.collab.service.CollaborationService;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import io.archi.collab.model.AdminDeleteResult;

@Path("/admin")
public class AdminEndpoint {

    @Inject
    CollaborationService collaborationService;

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
        return collaborationService.getAdminOverview(limit);
    }
}
