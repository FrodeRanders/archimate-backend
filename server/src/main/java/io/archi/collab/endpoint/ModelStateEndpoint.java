package io.archi.collab.endpoint;

import com.fasterxml.jackson.databind.JsonNode;
import io.archi.collab.model.RebuildStatus;
import io.archi.collab.service.CollaborationService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/models/{modelId}")
public class ModelStateEndpoint {

    @Inject
    CollaborationService collaborationService;

    @GET
    @Path("/snapshot")
    @Produces(MediaType.APPLICATION_JSON)
    public JsonNode snapshot(@PathParam("modelId") String modelId) {
        return collaborationService.getSnapshot(modelId);
    }

    @POST
    @Path("/rebuild")
    @Produces(MediaType.APPLICATION_JSON)
    public RebuildStatus rebuild(@PathParam("modelId") String modelId) {
        return collaborationService.rebuildMaterializedState(modelId);
    }
}

