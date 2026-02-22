package io.archi.collab.endpoint;

import io.archi.collab.model.ConsistencyStatus;
import io.archi.collab.service.CollaborationService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/models/{modelId}/consistency")
public class ConsistencyEndpoint {

    @Inject
    CollaborationService collaborationService;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public ConsistencyStatus status(@PathParam("modelId") String modelId) {
        return collaborationService.getConsistencyStatus(modelId);
    }
}
