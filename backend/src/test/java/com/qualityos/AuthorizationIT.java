package com.qualityos;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuthorizationIT extends AbstractIntegrationTest {

    @Test
    void viewerCannotManageUsersRolesOrTheOrganization() throws Exception {
        Session owner = register();
        Session viewer = addMember(owner, "VIEWER");

        as(viewer, get("/api/v1/users")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        as(viewer, get("/api/v1/audit-logs")).andExpect(status().isForbidden());
        as(viewer, postJson("/api/v1/roles", Map.of("name", "X", "permissions", List.of("REPORT_VIEW"))))
                .andExpect(status().isForbidden());
        as(viewer, put("/api/v1/organizations/" + owner.tenantId())
                        .contentType("application/json").content(json.writeValueAsString(Map.of(
                                "name", "Hacked", "countryCode", "IN", "timezone", "UTC", "currency", "INR",
                                "locale", "en", "version", 0))))
                .andExpect(status().isForbidden());
    }

    @Test
    void administratorWithoutAPermissionCannotGrantItThroughARole() throws Exception {
        Session owner = register();
        Session admin = addMember(owner, "ORGANISATION_ADMIN");

        as(admin, postJson("/api/v1/roles", Map.of("name", "Billing", "permissions", List.of("SUBSCRIPTION_MANAGE"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void administratorCannotPromoteAnyoneToOwner() throws Exception {
        Session owner = register();
        Session admin = addMember(owner, "ORGANISATION_ADMIN");
        Session viewer = addMember(owner, "VIEWER");

        as(admin, put("/api/v1/users/" + membershipId(owner, viewer.userId()) + "/roles")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("roleIds", List.of(roleId(owner, "ORGANISATION_OWNER"))))))
                .andExpect(status().isForbidden());
    }

    @Test
    void roleChangesApplyImmediatelyToExistingTokens() throws Exception {
        Session owner = register();
        Session member = addMember(owner, "VIEWER");
        as(member, get("/api/v1/users")).andExpect(status().isForbidden());

        as(owner, put("/api/v1/users/" + membershipId(owner, member.userId()) + "/roles")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("roleIds", List.of(roleId(owner, "ORGANISATION_ADMIN"))))))
                .andExpect(status().isOk());

        as(member, get("/api/v1/users")).andExpect(status().isOk());
    }

    @Test
    void lastOwnerCannotBeRemoved() throws Exception {
        Session owner = register();
        String membership = membershipId(owner, owner.userId());

        as(owner, put("/api/v1/users/" + membership + "/roles")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("roleIds", List.of(roleId(owner, "VIEWER"))))))
                .andExpect(status().isUnprocessableContent());
        as(owner, postJson("/api/v1/users/" + membership + "/deactivate", Map.of()))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void systemRolesAreImmutableButCustomRolesAreConfigurable() throws Exception {
        Session owner = register();
        String system = roleId(owner, "VIEWER");

        as(owner, put("/api/v1/roles/" + system).contentType("application/json")
                        .content(json.writeValueAsString(Map.of("name", "X", "permissions", List.of("REPORT_VIEW")))))
                .andExpect(status().isUnprocessableContent());
        as(owner, delete("/api/v1/roles/" + system)).andExpect(status().isUnprocessableContent());

        String body = as(owner, postJson("/api/v1/roles", Map.of(
                        "name", "Night Shift Lead", "permissions", List.of("REPORT_VIEW"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.code").value("NIGHT_SHIFT_LEAD"))
                .andReturn().getResponse().getContentAsString();
        String custom = JsonPath.read(body, "$.data.id");

        as(owner, put("/api/v1/roles/" + custom).contentType("application/json")
                        .content(json.writeValueAsString(Map.of(
                                "name", "Night Shift Lead", "permissions", List.of("REPORT_VIEW", "NCR_VIEW")))))
                .andExpect(status().isOk());
        as(owner, delete("/api/v1/roles/" + custom)).andExpect(status().isNoContent());
    }

    @Test
    void existingAccountCanJoinASecondOrganization() throws Exception {
        Session first = register();
        Session second = register();

        as(second, postJson("/api/v1/users", Map.of(
                        "email", first.email(), "fullName", "Ignored", "roleIds", List.of(roleId(second, "VIEWER")))))
                .andExpect(status().isCreated());

        as(first, get("/api/v1/organizations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }
}
