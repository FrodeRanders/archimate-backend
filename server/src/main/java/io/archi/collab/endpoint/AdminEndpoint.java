package io.archi.collab.endpoint;

import io.archi.collab.auth.AuthorizationAction;
import io.archi.collab.auth.AuthorizationService;
import io.archi.collab.model.*;
import io.archi.collab.service.CollaborationService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.SecurityContext;

import java.util.List;

@Path("/admin")
public class AdminEndpoint {

    @Inject
    CollaborationService collaborationService;

    @Inject
    AuthorizationService authorizationService;

    @GET
    @Path("/models")
    @Produces(MediaType.APPLICATION_JSON)
    public List<ModelCatalogEntry> modelCatalog(@Context HttpHeaders headers,
                                                @Context SecurityContext securityContext) {
        authorizationService.requireRestAllowed(headers, securityContext, AuthorizationAction.ADMIN_MODEL_CATALOG_READ, null, null);
        return collaborationService.getModelCatalog();
    }

    @GET
    @Path("/auth/diagnostics")
    @Produces(MediaType.APPLICATION_JSON)
    public AdminAuthorizationDiagnostics authorizationDiagnostics(@Context HttpHeaders headers,
                                                                 @Context SecurityContext securityContext) {
        authorizationService.requireRestAllowed(headers, securityContext, AuthorizationAction.ADMIN_OVERVIEW_READ, null, null);
        var subject = authorizationService.currentRestSubject(headers, securityContext);
        return new AdminAuthorizationDiagnostics(
                authorizationService.currentIdentityMode(),
                subject.userId(),
                subject.roles());
    }

    @POST
    @Path("/models/{modelId}")
    @Produces(MediaType.APPLICATION_JSON)
    public ModelCatalogEntry registerModel(@PathParam("modelId") String modelId,
                                           @QueryParam("modelName") String modelName,
                                           @Context HttpHeaders headers,
                                           @Context SecurityContext securityContext) {
        authorizationService.requireRestAllowed(headers, securityContext, AuthorizationAction.ADMIN_MODEL_CREATE, modelId, null);
        return collaborationService.registerModel(modelId, modelName,
                authorizationService.currentRestSubject(headers, securityContext).userId());
    }

    @PUT
    @Path("/models/{modelId}")
    @Produces(MediaType.APPLICATION_JSON)
    public ModelCatalogEntry renameModel(@PathParam("modelId") String modelId,
                                         @QueryParam("modelName") String modelName,
                                         @Context HttpHeaders headers,
                                         @Context SecurityContext securityContext) {
        authorizationService.requireRestAllowed(headers, securityContext, AuthorizationAction.ADMIN_MODEL_RENAME, modelId, null);
        return collaborationService.renameModel(modelId, modelName);
    }

    @GET
    @Path("/models/{modelId}/acl")
    @Produces(MediaType.APPLICATION_JSON)
    public ModelAccessControl readModelAccessControl(@PathParam("modelId") String modelId,
                                                     @Context HttpHeaders headers,
                                                     @Context SecurityContext securityContext) {
        authorizationService.requireRestAllowed(headers, securityContext, AuthorizationAction.ADMIN_MODEL_ACL_READ, modelId, null);
        return collaborationService.getModelAccessControl(modelId);
    }

    @PUT
    @Path("/models/{modelId}/acl")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public ModelAccessControl updateModelAccessControl(@PathParam("modelId") String modelId,
                                                       ModelAccessControlUpdateRequest request,
                                                       @Context HttpHeaders headers,
                                                       @Context SecurityContext securityContext) {
        authorizationService.requireRestAllowed(headers, securityContext, AuthorizationAction.ADMIN_MODEL_ACL_UPDATE, modelId, null);
        ModelAccessControlUpdateRequest safeRequest = request == null
                ? new ModelAccessControlUpdateRequest(null, null, null)
                : request;
        return collaborationService.updateModelAccessControl(
                modelId,
                safeRequest.adminUsers(),
                safeRequest.writerUsers(),
                safeRequest.readerUsers());
    }

    @GET
    @Path("/models/{modelId}/tags")
    @Produces(MediaType.APPLICATION_JSON)
    public List<ModelTagEntry> listModelTags(@PathParam("modelId") String modelId,
                                             @Context HttpHeaders headers,
                                             @Context SecurityContext securityContext) {
        authorizationService.requireRestAllowed(headers, securityContext, AuthorizationAction.ADMIN_MODEL_TAG_LIST, modelId, null);
        return collaborationService.getModelTags(modelId);
    }

    @POST
    @Path("/models/{modelId}/tags")
    @Produces(MediaType.APPLICATION_JSON)
    public ModelTagEntry createModelTag(@PathParam("modelId") String modelId,
                                        @QueryParam("tagName") String tagName,
                                        @QueryParam("description") String description,
                                        @Context HttpHeaders headers,
                                        @Context SecurityContext securityContext) {
        authorizationService.requireRestAllowed(headers, securityContext, AuthorizationAction.ADMIN_MODEL_TAG_CREATE, modelId, tagName);
        return collaborationService.createModelTag(modelId, tagName, description);
    }

    @DELETE
    @Path("/models/{modelId}/tags/{tagName}")
    @Produces(MediaType.APPLICATION_JSON)
    public void deleteModelTag(@PathParam("modelId") String modelId,
                               @PathParam("tagName") String tagName,
                               @Context HttpHeaders headers,
                               @Context SecurityContext securityContext) {
        authorizationService.requireRestAllowed(headers, securityContext, AuthorizationAction.ADMIN_MODEL_TAG_DELETE, modelId, tagName);
        try {
            collaborationService.deleteModelTag(modelId, tagName);
        } catch (IllegalStateException ex) {
            throw new WebApplicationException(ex.getMessage(), Response.Status.CONFLICT);
        }
    }

    @GET
    @Path("/models/{modelId}/status")
    @Produces(MediaType.APPLICATION_JSON)
    public AdminStatus status(@PathParam("modelId") String modelId,
                              @Context HttpHeaders headers,
                              @Context SecurityContext securityContext) {
        authorizationService.requireRestAllowed(headers, securityContext, AuthorizationAction.ADMIN_MODEL_STATUS_READ, modelId, null);
        return collaborationService.getAdminStatus(modelId);
    }

    @POST
    @Path("/models/{modelId}/rebuild-and-status")
    @Produces(MediaType.APPLICATION_JSON)
    public AdminRebuildStatus rebuildAndStatus(@PathParam("modelId") String modelId,
                                               @Context HttpHeaders headers,
                                               @Context SecurityContext securityContext) {
        authorizationService.requireRestAllowed(headers, securityContext, AuthorizationAction.ADMIN_MODEL_REBUILD, modelId, null);
        return collaborationService.rebuildAndGetAdminStatus(modelId);
    }

    @POST
    @Path("/models/{modelId}/compact")
    @Produces(MediaType.APPLICATION_JSON)
    public AdminCompactionStatus compact(@PathParam("modelId") String modelId,
                                         @QueryParam("retainRevisions") Long retainRevisions,
                                         @Context HttpHeaders headers,
                                         @Context SecurityContext securityContext) {
        authorizationService.requireRestAllowed(headers, securityContext, AuthorizationAction.ADMIN_MODEL_COMPACT, modelId, null);
        // Compaction reclaims eligible metadata/op-log history according to committed-horizon watermark policy
        return collaborationService.compactModelMetadata(modelId, retainRevisions);
    }

    @GET
    @Path("/models/{modelId}/window")
    @Produces(MediaType.APPLICATION_JSON)
    public AdminModelWindow window(@PathParam("modelId") String modelId,
                                   @QueryParam("limit") Integer limit,
                                   @Context HttpHeaders headers,
                                   @Context SecurityContext securityContext) {
        authorizationService.requireRestAllowed(headers, securityContext, AuthorizationAction.ADMIN_MODEL_WINDOW_READ, modelId, null);
        return collaborationService.getAdminModelWindow(modelId, limit);
    }

    @GET
    @Path("/models/{modelId}/integrity")
    @Produces(MediaType.APPLICATION_JSON)
    public AdminIntegrityReport integrity(@PathParam("modelId") String modelId,
                                          @Context HttpHeaders headers,
                                          @Context SecurityContext securityContext) {
        authorizationService.requireRestAllowed(headers, securityContext, AuthorizationAction.ADMIN_MODEL_INTEGRITY_READ, modelId, null);
        return collaborationService.getAdminIntegrity(modelId);
    }

    @DELETE
    @Path("/models/{modelId}")
    @Produces(MediaType.APPLICATION_JSON)
    public AdminDeleteResult deleteModel(@PathParam("modelId") String modelId,
                                         @QueryParam("force") Boolean force,
                                         @Context HttpHeaders headers,
                                         @Context SecurityContext securityContext) {
        authorizationService.requireRestAllowed(headers, securityContext, AuthorizationAction.ADMIN_MODEL_DELETE, modelId, null);
        return collaborationService.deleteModel(modelId, Boolean.TRUE.equals(force));
    }

    @GET
    @Path("/models/{modelId}/export")
    @Produces(MediaType.APPLICATION_JSON)
    public AdminModelExport exportModel(@PathParam("modelId") String modelId,
                                        @Context HttpHeaders headers,
                                        @Context SecurityContext securityContext) {
        authorizationService.requireRestAllowed(headers, securityContext, AuthorizationAction.ADMIN_MODEL_EXPORT, modelId, null);
        return collaborationService.exportModel(modelId);
    }

    @POST
    @Path("/models/import")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public AdminModelImportResult importModel(AdminModelExport exportPackage,
                                              @QueryParam("overwrite") Boolean overwrite,
                                              @Context HttpHeaders headers,
                                              @Context SecurityContext securityContext) {
        authorizationService.requireRestAllowed(headers, securityContext, AuthorizationAction.ADMIN_MODEL_IMPORT,
                exportPackage == null || exportPackage.model() == null ? null : exportPackage.model().modelId(), null);
        try {
            return collaborationService.importModel(exportPackage, Boolean.TRUE.equals(overwrite));
        } catch (IllegalStateException ex) {
            throw new WebApplicationException(ex.getMessage(), Response.Status.CONFLICT);
        } catch (IllegalArgumentException ex) {
            throw new WebApplicationException(ex.getMessage(), Response.Status.BAD_REQUEST);
        }
    }

    @GET
    @Path("/overview")
    @Produces(MediaType.APPLICATION_JSON)
    public List<AdminModelWindow> overview(@QueryParam("limit") Integer limit,
                                           @Context HttpHeaders headers,
                                           @Context SecurityContext securityContext) {
        authorizationService.requireRestAllowed(headers, securityContext, AuthorizationAction.ADMIN_OVERVIEW_READ, null, null);
        // Lightweight aggregated view for operator dashboards
        return collaborationService.getAdminOverview(limit);
    }
}
