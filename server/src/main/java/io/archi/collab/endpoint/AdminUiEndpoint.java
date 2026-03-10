package io.archi.collab.endpoint;

import io.archi.collab.auth.AuthorizationService;
import io.archi.collab.model.AdminUiConfig;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/admin-ui")
public class AdminUiEndpoint {

    @Inject
    AuthorizationService authorizationService;

    @ConfigProperty(name = "app.authz.enabled", defaultValue = "false")
    boolean authorizationEnabled;

    @GET
    @Path("/config")
    @Produces(MediaType.APPLICATION_JSON)
    public AdminUiConfig config() {
        return new AdminUiConfig(
                authorizationService.currentIdentityMode(),
                authorizationEnabled);
    }
}
