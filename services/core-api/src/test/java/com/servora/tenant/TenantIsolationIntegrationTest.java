package com.servora.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.servora.support.IntegrationTestBase;
import com.servora.support.TwoTenants;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Cross-tenant negative tests for every M1 resource family: workspaces, members, invitations and
 * audit events. Each test has a user of one tenant try to reach the other tenant's data, either
 * through the other workspace's URL or by substituting the other tenant's object IDs.
 */
class TenantIsolationIntegrationTest extends IntegrationTestBase {

    private static TwoTenants tenants;

    @BeforeEach
    void createTenantsOnce() {
        if (tenants == null) {
            tenants = TwoTenants.create(this);
        }
    }

    private static String workspace(UUID id) {
        return "/api/v1/workspaces/" + id;
    }

    @Test
    void ownerCannotReadOrUpdateAnotherTenantsWorkspace() {
        Response read = get(workspace(tenants.hotelPro()), tenants.ownerA());
        Response update = patch(workspace(tenants.hotelPro()), "{\"name\": \"Hijacked\"}", tenants.ownerA());

        assertNotFound(read, "WORKSPACE_NOT_FOUND");
        assertNotFound(update, "WORKSPACE_NOT_FOUND");
        assertThat(get(workspace(tenants.hotelPro()), tenants.ownerB()).<String>json("$.name"))
                .isEqualTo("HotelPro");
    }

    @Test
    void aWorkspaceThatDoesNotExistLooksTheSameAsOneTheUserCannotAccess() {
        Response other = get(workspace(tenants.hotelPro()), tenants.ownerA());
        Response missing = get(workspace(UUID.randomUUID()), tenants.ownerA());

        assertThat(other.status()).isEqualTo(missing.status());
        assertThat(other.errorCode()).isEqualTo(missing.errorCode());
        assertThat(other.<String>json("$.error.message")).isEqualTo(missing.<String>json("$.error.message"));
    }

    @Test
    void workspaceListContainsOnlyTheUsersOwnWorkspaces() {
        Response listA = get("/api/v1/workspaces", tenants.ownerA());
        Response listAgentB = get("/api/v1/workspaces", tenants.agentB().session());

        assertThat(listA.<List<String>>json("$[*].id")).containsExactly(tenants.techStore().toString());
        assertThat(listAgentB.<List<String>>json("$[*].id")).containsExactly(tenants.hotelPro().toString());
        assertThat(listA.body()).doesNotContain("HotelPro");
    }

    @Test
    void membersOfAnotherTenantCannotBeListed() {
        Response crossTenant = get(workspace(tenants.hotelPro()) + "/members", tenants.ownerA());
        Response own = get(workspace(tenants.techStore()) + "/members", tenants.ownerA());

        assertNotFound(crossTenant, "WORKSPACE_NOT_FOUND");
        assertThat(own.<List<String>>json("$.items[*].email"))
                .contains(tenants.ownerA().email(), tenants.agentA().session().email());
        assertThat(own.body())
                .doesNotContain(tenants.ownerB().email())
                .doesNotContain(tenants.agentB().session().email());
    }

    @Test
    void memberIdFromAnotherTenantCannotBeChangedThroughTheCallersOwnWorkspace() {
        String body = "{\"role\": \"ADMIN\"}";

        // Object-ID substitution: a valid workspace the caller owns, with another tenant's member ID.
        Response substituted = patch(
                workspace(tenants.techStore()) + "/members/" + tenants.agentB().memberId(), body, tenants.ownerA());
        // And the direct route through the other workspace.
        Response direct = patch(
                workspace(tenants.hotelPro()) + "/members/" + tenants.agentB().memberId(), body, tenants.ownerA());

        assertNotFound(substituted, "MEMBER_NOT_FOUND");
        assertNotFound(direct, "WORKSPACE_NOT_FOUND");
        Response members = get(workspace(tenants.hotelPro()) + "/members", tenants.ownerB());
        assertThat(members.<List<String>>json(
                        "$.items[?(@.id == '" + tenants.agentB().memberId() + "')].role"))
                .containsExactly("AGENT");
    }

    @Test
    void invitationsOfAnotherTenantCannotBeListedCreatedOrRevoked() {
        String invitee = uniqueEmail("pending-b");
        Response created = invite(tenants.ownerB(), tenants.hotelPro(), invitee, "AGENT");
        String invitationId = created.json("$.id");

        Response list = get(workspace(tenants.hotelPro()) + "/invitations", tenants.ownerA());
        Response create = invite(tenants.ownerA(), tenants.hotelPro(), uniqueEmail("intruder"), "ADMIN");
        Response revokeDirect =
                delete(workspace(tenants.hotelPro()) + "/invitations/" + invitationId, tenants.ownerA());
        Response revokeSubstituted =
                delete(workspace(tenants.techStore()) + "/invitations/" + invitationId, tenants.ownerA());

        assertNotFound(list, "WORKSPACE_NOT_FOUND");
        assertNotFound(create, "WORKSPACE_NOT_FOUND");
        assertNotFound(revokeDirect, "WORKSPACE_NOT_FOUND");
        assertNotFound(revokeSubstituted, "INVITATION_NOT_FOUND");

        Response ownList = get(workspace(tenants.techStore()) + "/invitations", tenants.ownerA());
        assertThat(ownList.body()).doesNotContain(invitee);
        Response stillPending = get(workspace(tenants.hotelPro()) + "/invitations", tenants.ownerB());
        assertThat(stillPending.<List<String>>json("$.items[?(@.id == '" + invitationId + "')].status"))
                .containsExactly("PENDING");
    }

    @Test
    void anInvitationTokenOnlyWorksForTheInvitedEmailAddress() {
        String invitee = uniqueEmail("invited-b");
        invite(tenants.ownerB(), tenants.hotelPro(), invitee, "ADMIN");
        String token = mail.invitationToken(invitee);

        // A user of tenant A gets hold of tenant B's invitation link.
        Response stolen = acceptInvitation(tenants.ownerA(), token);

        assertThat(stolen.status()).isEqualTo(403);
        assertThat(stolen.errorCode()).isEqualTo("INVITATION_EMAIL_MISMATCH");
        assertNotFound(get(workspace(tenants.hotelPro()), tenants.ownerA()), "WORKSPACE_NOT_FOUND");
        assertThat(get("/api/v1/workspaces", tenants.ownerA()).<List<String>>json("$[*].id"))
                .containsExactly(tenants.techStore().toString());
    }

    @Test
    void auditEventsOfAnotherTenantCannotBeRead() {
        Response crossTenant = get(workspace(tenants.hotelPro()) + "/audit-events", tenants.ownerA());
        Response own = get(workspace(tenants.techStore()) + "/audit-events", tenants.ownerA());

        assertNotFound(crossTenant, "WORKSPACE_NOT_FOUND");
        assertThat(own.status()).isEqualTo(200);
        assertThat(own.<List<String>>json("$.items[*].action")).contains("WORKSPACE_CREATED", "INVITATION_ACCEPTED");
        assertThat(own.<List<String>>json("$.items[?(@.action == 'WORKSPACE_CREATED')].resource_id"))
                .containsExactly(tenants.techStore().toString());
        assertThat(own.body()).doesNotContain(tenants.hotelPro().toString());
    }

    @Test
    void aTakenSlugIsRejectedWithoutRevealingTheOtherWorkspace() {
        String slug = get(workspace(tenants.hotelPro()), tenants.ownerB()).json("$.slug");

        Response response = post(
                "/api/v1/workspaces",
                """
                {"name": "Copycat", "slug": "%s"}""".formatted(slug),
                tenants.ownerA());

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.errorCode()).isEqualTo("SLUG_TAKEN");
        assertThat(response.body()).doesNotContain("HotelPro").doesNotContain(tenants.hotelPro().toString());
    }

    /** FR-004 and FR-005: one user in two workspaces, switching only where a membership is active. */
    @Test
    void aUserCanBelongToTwoWorkspacesAndLosesAccessWhenOneMembershipIsDisabled() {
        Session shared = newUser("shared");
        addExistingUser(tenants.ownerA(), tenants.techStore(), shared, "AGENT");
        addExistingUser(tenants.ownerB(), tenants.hotelPro(), shared, "SUPERVISOR");

        assertThat(get("/api/v1/workspaces", shared).<List<String>>json("$[*].id"))
                .containsExactlyInAnyOrder(
                        tenants.techStore().toString(), tenants.hotelPro().toString());
        assertThat(get(workspace(tenants.techStore()), shared).<String>json("$.role"))
                .isEqualTo("AGENT");
        assertThat(get(workspace(tenants.hotelPro()), shared).<String>json("$.role"))
                .isEqualTo("SUPERVISOR");

        UUID memberIdInB = memberIdOf(tenants.ownerB(), tenants.hotelPro(), shared.email());
        Response disabled = patch(
                workspace(tenants.hotelPro()) + "/members/" + memberIdInB, "{\"status\": \"DISABLED\"}", tenants.ownerB());
        assertThat(disabled.status()).isEqualTo(200);

        assertNotFound(get(workspace(tenants.hotelPro()), shared), "WORKSPACE_NOT_FOUND");
        assertThat(get(workspace(tenants.techStore()), shared).status()).isEqualTo(200);
        assertThat(get("/api/v1/workspaces", shared).<List<String>>json("$[*].id"))
                .containsExactly(tenants.techStore().toString());
    }

    private static void assertNotFound(Response response, String code) {
        assertThat(response.status()).as(response.body()).isEqualTo(404);
        assertThat(response.errorCode()).isEqualTo(code);
    }
}
