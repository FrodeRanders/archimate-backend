package io.archi.collab.endpoint;

import com.fasterxml.jackson.databind.JsonNode;
import io.archi.collab.model.RebuildStatus;
import io.archi.collab.service.CollaborationService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

@Path("/models/{modelId}")
public class ModelStateEndpoint {

    @Inject
    CollaborationService collaborationService;

    @GET
    @Path("/snapshot")
    @Produces(MediaType.APPLICATION_JSON)
    public JsonNode snapshot(@PathParam("modelId") String modelId,
                             @QueryParam("ref") String ref) {
        // Serves current materialized state for HEAD or an immutable tagged snapshot for historical pulls.
        return collaborationService.getSnapshot(modelId, ref);
    }

    @POST
    @Path("/rebuild")
    @Produces(MediaType.APPLICATION_JSON)
    public RebuildStatus rebuild(@PathParam("modelId") String modelId) {
        // Rebuild replays persisted commits into materialized state for operational recovery
        return collaborationService.rebuildMaterializedState(modelId);
    }
}
