package org.gautelis.archimesh.endpoint;

import org.gautelis.archimesh.model.ConsistencyStatus;
import org.gautelis.archimesh.service.ArchimeshService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/models/{modelId}/consistency")
public class ConsistencyEndpoint {

    @Inject
    ArchimeshService archimeshService;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public ConsistencyStatus status(@PathParam("modelId") String modelId) {
        return archimeshService.getConsistencyStatus(modelId);
    }
}
