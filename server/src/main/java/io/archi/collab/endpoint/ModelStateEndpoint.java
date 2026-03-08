package io.archi.collab.endpoint;

import com.fasterxml.jackson.databind.JsonNode;
import io.archi.collab.auth.AuthorizationAction;
import io.archi.collab.auth.AuthorizationService;
import io.archi.collab.model.RebuildStatus;
import io.archi.collab.service.CollaborationService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.SecurityContext;

@Path("/models/{modelId}")
public class ModelStateEndpoint {

    @Inject
    CollaborationService collaborationService;

    @Inject
    AuthorizationService authorizationService;

    @GET
    @Path("/snapshot")
    @Produces(MediaType.APPLICATION_JSON)
    public JsonNode snapshot(@PathParam("modelId") String modelId,
                             @QueryParam("ref") String ref,
                             @Context HttpHeaders headers,
                             @Context SecurityContext securityContext) {
        authorizationService.requireRestAllowed(headers, securityContext, AuthorizationAction.MODEL_SNAPSHOT_READ, modelId, ref);
        // Serves current materialized state for HEAD or an immutable tagged snapshot for historical pulls.
        return collaborationService.getSnapshot(modelId, ref);
    }

    @POST
    @Path("/rebuild")
    @Produces(MediaType.APPLICATION_JSON)
    public RebuildStatus rebuild(@PathParam("modelId") String modelId,
                                 @Context HttpHeaders headers,
                                 @Context SecurityContext securityContext) {
        authorizationService.requireRestAllowed(headers, securityContext, AuthorizationAction.ADMIN_MODEL_REBUILD, modelId, null);
        // Rebuild replays persisted commits into materialized state for operational recovery
        return collaborationService.rebuildMaterializedState(modelId);
    }
}
