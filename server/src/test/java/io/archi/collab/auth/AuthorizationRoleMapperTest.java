package io.archi.collab.auth;

import jakarta.ws.rs.core.SecurityContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.util.Optional;
import java.util.Set;

class AuthorizationRoleMapperTest {

    @Test
    void normalizesAliasRolesToCanonicalRoles() {
        AuthorizationRoleMapper mapper = new AuthorizationRoleMapper();
        mapper.adminRole = "admin";
        mapper.readerRole = "model_reader";
        mapper.writerRole = "model_writer";
        mapper.adminRoleAliasesRaw = Optional.of("realm-admin");
        mapper.readerRoleAliasesRaw = Optional.of("viewer,architect-reader");
        mapper.writerRoleAliasesRaw = Optional.of("editor,architect-writer");

        Set<String> normalized = mapper.normalizeRoles(Set.of("realm-admin", "editor", "viewer"));

        Assertions.assertEquals(Set.of("admin", "model_reader", "model_writer"), normalized);
    }

    @Test
    void extractsCanonicalRolesFromSecurityContextAliases() {
        AuthorizationRoleMapper mapper = new AuthorizationRoleMapper();
        mapper.adminRole = "admin";
        mapper.readerRole = "model_reader";
        mapper.writerRole = "model_writer";
        mapper.adminRoleAliasesRaw = Optional.of("realm-admin");
        mapper.readerRoleAliasesRaw = Optional.of("viewer");
        mapper.writerRoleAliasesRaw = Optional.of("editor");

        SecurityContext securityContext = new SecurityContext() {
            @Override
            public Principal getUserPrincipal() {
                return () -> "oidc-user";
            }

            @Override
            public boolean isUserInRole(String role) {
                return "realm-admin".equals(role) || "viewer".equals(role);
            }

            @Override
            public boolean isSecure() {
                return true;
            }

            @Override
            public String getAuthenticationScheme() {
                return "OIDC";
            }
        };

        Set<String> normalized = mapper.rolesFromSecurityContext(securityContext);
        Assertions.assertEquals(Set.of("admin", "model_reader"), normalized);
    }
}
