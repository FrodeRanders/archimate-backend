package org.gautelis.archimesh.endpoint;

import com.fasterxml.jackson.databind.JsonNode;
import org.gautelis.archimesh.auth.AuthorizationAction;
import org.gautelis.archimesh.auth.AuthorizationService;
import org.gautelis.archimesh.model.RebuildStatus;
import org.gautelis.archimesh.service.ArchimeshService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.SecurityContext;

@Path("/models/{modelId}")
public class ModelStateEndpoint {

    @Inject
    ArchimeshService archimeshService;

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
        return archimeshService.getSnapshot(modelId, ref);
    }

    @POST
    @Path("/rebuild")
    @Produces(MediaType.APPLICATION_JSON)
    public RebuildStatus rebuild(@PathParam("modelId") String modelId,
                                 @Context HttpHeaders headers,
                                 @Context SecurityContext securityContext) {
        authorizationService.requireRestAllowed(headers, securityContext, AuthorizationAction.ADMIN_MODEL_REBUILD, modelId, null);
        // Rebuild replays persisted commits into materialized state for operational recovery
        return archimeshService.rebuildMaterializedState(modelId);
    }
}
