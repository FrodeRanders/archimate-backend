package io.archi.collab.endpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Path("/admin")
public class AdminEndpoint {
    private static final Logger LOG = LoggerFactory.getLogger(AdminEndpoint.class);

    @Inject
    CollaborationService collaborationService;

    @Inject
    AuthorizationService authorizationService;

    @Inject
    ObjectMapper objectMapper;

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
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("identityMode", authorizationService.currentIdentityMode());
        context.put("roles", subject.roles());
        audit("AdminAuthDiagnostics", null, subject.userId(), context);
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
        var subject = authorizationService.currentRestSubject(headers, securityContext);
        ModelCatalogEntry result = collaborationService.registerModel(modelId, modelName, subject.userId());
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("modelName", result.modelName());
        context.put("headRevision", result.headRevision());
        audit("AdminModelCreate", modelId, subject.userId(), context);
        return result;
    }

    @PUT
    @Path("/models/{modelId}")
    @Produces(MediaType.APPLICATION_JSON)
    public ModelCatalogEntry renameModel(@PathParam("modelId") String modelId,
                                         @QueryParam("modelName") String modelName,
                                         @Context HttpHeaders headers,
                                         @Context SecurityContext securityContext) {
        authorizationService.requireRestAllowed(headers, securityContext, AuthorizationAction.ADMIN_MODEL_RENAME, modelId, null);
        var subject = authorizationService.currentRestSubject(headers, securityContext);
        ModelCatalogEntry result = collaborationService.renameModel(modelId, modelName);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("modelName", result.modelName());
        context.put("headRevision", result.headRevision());
        audit("AdminModelRename", modelId, subject.userId(), context);
        return result;
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
        var subject = authorizationService.currentRestSubject(headers, securityContext);
        ModelAccessControlUpdateRequest safeRequest = request == null
                ? new ModelAccessControlUpdateRequest(null, null, null)
                : request;
        ModelAccessControl result = collaborationService.updateModelAccessControl(
                modelId,
                safeRequest.adminUsers(),
                safeRequest.writerUsers(),
                safeRequest.readerUsers());
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("configured", result.configured());
        context.put("adminUserCount", result.adminUsers().size());
        context.put("writerUserCount", result.writerUsers().size());
        context.put("readerUserCount", result.readerUsers().size());
        audit("AdminModelAclUpdate", modelId, subject.userId(), context);
        return result;
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
        var subject = authorizationService.currentRestSubject(headers, securityContext);
        ModelTagEntry result = collaborationService.createModelTag(modelId, tagName, description);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("tagName", result.tagName());
        context.put("revision", result.revision());
        context.put("hasDescription", result.description() != null && !result.description().isBlank());
        audit("AdminModelTagCreate", modelId, subject.userId(), context);
        return result;
    }

    @DELETE
    @Path("/models/{modelId}/tags/{tagName}")
    @Produces(MediaType.APPLICATION_JSON)
    public void deleteModelTag(@PathParam("modelId") String modelId,
                               @PathParam("tagName") String tagName,
                               @Context HttpHeaders headers,
                               @Context SecurityContext securityContext) {
        authorizationService.requireRestAllowed(headers, securityContext, AuthorizationAction.ADMIN_MODEL_TAG_DELETE, modelId, tagName);
        var subject = authorizationService.currentRestSubject(headers, securityContext);
        try {
            collaborationService.deleteModelTag(modelId, tagName);
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("tagName", tagName);
            audit("AdminModelTagDelete", modelId, subject.userId(), context);
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
        var subject = authorizationService.currentRestSubject(headers, securityContext);
        AdminRebuildStatus result = collaborationService.rebuildAndGetAdminStatus(modelId);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("snapshotHeadRevision", result.status() == null ? null : result.status().snapshotHeadRevision());
        context.put("consistent", result.status() != null
                && result.status().consistency() != null
                && result.status().consistency().consistent());
        audit("AdminModelRebuild", modelId, subject.userId(), context);
        return result;
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
        var subject = authorizationService.currentRestSubject(headers, securityContext);
        AdminCompactionStatus result = collaborationService.compactModelMetadata(modelId, retainRevisions);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("retainRevisions", result.retainRevisions());
        context.put("executed", result.executed());
        context.put("deletedCommitCount", result.deletedCommitCount());
        context.put("deletedOpCount", result.deletedOpCount());
        audit("AdminModelCompact", modelId, subject.userId(), context);
        return result;
    }

    @GET
    @Path("/models/{modelId}/window")
    @Produces(MediaType.APPLICATION_JSON)
    public AdminModelWindow window(@PathParam("modelId") String modelId,
                                   @QueryParam("limit") Integer limit,
                                   @Context HttpHeaders headers,
                                   @Context SecurityContext securityContext) {
        authorizationService.requireRestAllowed(headers, securityContext, AuthorizationAction.ADMIN_MODEL_WINDOW_READ, modelId, null);
        var subject = authorizationService.currentRestSubject(headers, securityContext);
        AdminModelWindow window = collaborationService.getAdminModelWindow(modelId, limit);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("limit", limit);
        context.put("activeSessionCount", window.activeSessionCount());
        audit("AdminWindowRead", modelId, subject.userId(), context);
        return window;
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
        var subject = authorizationService.currentRestSubject(headers, securityContext);
        AdminDeleteResult result = collaborationService.deleteModel(modelId, Boolean.TRUE.equals(force));
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("force", Boolean.TRUE.equals(force));
        context.put("deleted", result.deleted());
        context.put("activeSessions", result.activeSessions());
        context.put("message", result.message());
        audit("AdminModelDelete", modelId, subject.userId(), context);
        return result;
    }

    @GET
    @Path("/models/{modelId}/export")
    @Produces(MediaType.APPLICATION_JSON)
    public AdminModelExport exportModel(@PathParam("modelId") String modelId,
                                        @Context HttpHeaders headers,
                                        @Context SecurityContext securityContext) {
        authorizationService.requireRestAllowed(headers, securityContext, AuthorizationAction.ADMIN_MODEL_EXPORT, modelId, null);
        var subject = authorizationService.currentRestSubject(headers, securityContext);
        AdminModelExport result = collaborationService.exportModel(modelId);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("headRevision", result.model() == null ? null : result.model().headRevision());
        context.put("opBatchCount", result.opBatches() == null ? 0 : result.opBatches().size());
        context.put("tagCount", result.tags() == null ? 0 : result.tags().size());
        audit("AdminModelExport", modelId, subject.userId(), context);
        return result;
    }

    @POST
    @Path("/models/import")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public AdminModelImportResult importModel(AdminModelExport exportPackage,
                                              @QueryParam("overwrite") Boolean overwrite,
                                              @Context HttpHeaders headers,
                                              @Context SecurityContext securityContext) {
        String modelId = exportPackage == null || exportPackage.model() == null ? null : exportPackage.model().modelId();
        authorizationService.requireRestAllowed(headers, securityContext, AuthorizationAction.ADMIN_MODEL_IMPORT,
                modelId, null);
        var subject = authorizationService.currentRestSubject(headers, securityContext);
        try {
            AdminModelImportResult result = collaborationService.importModel(exportPackage, Boolean.TRUE.equals(overwrite));
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("overwrite", Boolean.TRUE.equals(overwrite));
            context.put("headRevision", result.headRevision());
            context.put("importedOpBatchCount", result.importedOpBatchCount());
            context.put("importedTagCount", result.importedTagCount());
            context.put("overwritten", result.overwritten());
            audit("AdminModelImport", result.modelId(), subject.userId(), context);
            return result;
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

    private void audit(String action, String modelId, String userId, Map<String, Object> context) {
        AdminAuditEvent event = new AdminAuditEvent(
                Instant.now().toString(),
                action,
                sanitize(modelId, "-"),
                sanitize(userId, "anonymous"),
                context == null ? Map.of() : context);
        try {
            LOG.info("admin_audit {}", objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            LOG.info("admin_audit action={} modelId={} userId={} context={}",
                    event.action(),
                    event.modelId(),
                    event.userId(),
                    event.context());
        }
    }

    private String sanitize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
