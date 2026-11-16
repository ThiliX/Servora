package com.servora.membership;

import static org.assertj.core.api.Assertions.assertThat;

import com.servora.support.IntegrationTestBase;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** FR-006 and the workspace role matrix, enforced by the backend over HTTP. */
class RoleEnforcementIntegrationTest extends IntegrationTestBase {

    private static UUID workspaceId;
    private static Session owner;
    private static Member admin;
    private static Member supervisor;
    private static Member agent;

    @BeforeEach
    void createWorkspaceOnce() {
        if (workspaceId == null) {
            owner = newUser("role-owner");
            workspaceId = createWorkspace(owner, "Roles");
            admin = addMember(owner, workspaceId, "role-admin", "ADMIN");
            supervisor = addMember(owner, workspaceId, "role-supervisor", "SUPERVISOR");
            agent = addMember(owner, workspaceId, "role-agent", "AGENT");
        }
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private String base() {
        return "/api/v1/workspaces/" + workspaceId;
    }

    @Test
    void everyRoleCanViewTheWorkspaceAndItsMembers() {
        for (Session session : List.of(owner, admin.session(), supervisor.session(), agent.session())) {
            assertThat(get(base(), session).status()).isEqualTo(200);
            assertThat(get(base() + "/members", session).status()).isEqualTo(200);
        }
    }

    @Test
    void supervisorsAndAgentsCannotManageTheWorkspace() {
        for (Member member : List.of(supervisor, agent)) {
            Session session = member.session();
            assertForbidden(patch(base(), "{\"name\": \"Renamed\"}", session));
            assertForbidden(invite(session, workspaceId, uniqueEmail("nope"), "AGENT"));
            assertForbidden(get(base() + "/invitations", session));
            assertForbidden(get(base() + "/audit-events", session));
            assertForbidden(patch(base() + "/members/" + admin.memberId(), "{\"role\": \"AGENT\"}", session));
        }
        assertThat(get(base(), owner).<String>json("$.name")).isEqualTo("Roles");
    }

    @Test
    void adminCanManageMembersAndSettings() {
        Member newcomer = addMember(admin.session(), workspaceId, "by-admin", "AGENT");

        Response promoted = patch(
                base() + "/members/" + newcomer.memberId(), "{\"role\": \"SUPERVISOR\"}", admin.session());
        Response settings = patch(
                base(),
                """
                {"timezone": "Asia/Colombo", "default_language": "en", "industry": "Retail"}""",
                admin.session());

        assertThat(promoted.status()).isEqualTo(200);
        assertThat(promoted.<String>json("$.role")).isEqualTo("SUPERVISOR");
        assertThat(settings.status()).isEqualTo(200);
        assertThat(settings.<String>json("$.timezone")).isEqualTo("Asia/Colombo");
        assertThat(settings.<String>json("$.industry")).isEqualTo("Retail");
    }

    @Test
    void adminCannotChangeAnOwnerOrGrantOwnership() {
        UUID ownerMemberId = memberIdOf(owner, workspaceId, owner.email());

        assertForbidden(patch(base() + "/members/" + ownerMemberId, "{\"role\": \"AGENT\"}", admin.session()));
        assertForbidden(patch(base() + "/members/" + ownerMemberId, "{\"status\": \"DISABLED\"}", admin.session()));
        assertForbidden(patch(base() + "/members/" + agent.memberId(), "{\"role\": \"OWNER\"}", admin.session()));

        assertThat(get(base(), owner).<String>json("$.role")).isEqualTo("OWNER");
    }

    @Test
    void ownershipCannotBeGrantedByInvitation() {
        Response response = invite(owner, workspaceId, uniqueEmail("would-be-owner"), "OWNER");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.errorCode()).isEqualTo("INVALID_ROLE");
    }

    @Test
    void membersCannotChangeTheirOwnMembership() {
        UUID ownerMemberId = memberIdOf(owner, workspaceId, owner.email());

        Response response = patch(base() + "/members/" + ownerMemberId, "{\"role\": \"AGENT\"}", owner);

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.errorCode()).isEqualTo("CANNOT_MODIFY_SELF");
    }

    @Test
    void aDisabledMemberLosesAccessImmediately() {
        Member leaver = addMember(owner, workspaceId, "leaver", "AGENT");
        assertThat(get(base(), leaver.session()).status()).isEqualTo(200);

        patch(base() + "/members/" + leaver.memberId(), "{\"status\": \"DISABLED\"}", owner);

        assertThat(get(base(), leaver.session()).status()).isEqualTo(404);
        assertThat(get("/api/v1/workspaces", leaver.session()).<List<String>>json("$[*].id"))
                .isEmpty();
    }

    @Test
    void invitationResponsesNeverExposeTheToken() {
        String invitee = uniqueEmail("secret");
        Response created = invite(owner, workspaceId, invitee, "AGENT");
        Response listed = get(base() + "/invitations", owner);

        String token = mail.invitationToken(invitee);
        assertThat(created.body()).doesNotContain(token).doesNotContain("token");
        assertThat(listed.body()).doesNotContain(token).doesNotContain("token");
    }

    @Test
    void duplicatePendingInvitationsAndExistingMembersAreRejected() {
        String invitee = uniqueEmail("twice");
        assertThat(invite(owner, workspaceId, invitee, "AGENT").status()).isEqualTo(201);

        Response duplicate = invite(owner, workspaceId, invitee.toUpperCase(), "ADMIN");
        Response existingMember = invite(owner, workspaceId, agent.session().email(), "ADMIN");

        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.errorCode()).isEqualTo("INVITATION_EXISTS");
        assertThat(existingMember.status()).isEqualTo(409);
        assertThat(existingMember.errorCode()).isEqualTo("ALREADY_MEMBER");
    }

    @Test
    void aRevokedInvitationCannotBeAccepted() {
        Session invitee = newUser("revoked");
        String invitationId = invite(owner, workspaceId, invitee.email(), "AGENT").json("$.id");

        assertThat(delete(base() + "/invitations/" + invitationId, owner).status()).isEqualTo(204);
        Response accepted = acceptInvitation(invitee, mail.invitationToken(invitee.email()));

        assertThat(accepted.status()).isEqualTo(409);
        assertThat(accepted.errorCode()).isEqualTo("INVITATION_NOT_PENDING");
        assertThat(get(base(), invitee).status()).isEqualTo(404);
    }

    @Test
    void anAcceptedInvitationCannotBeUsedTwice() {
        Session invitee = newUser("once");
        invite(owner, workspaceId, invitee.email(), "AGENT");
        String token = mail.invitationToken(invitee.email());

        assertThat(acceptInvitation(invitee, token).status()).isEqualTo(200);
        Response second = acceptInvitation(invitee, token);

        assertThat(second.status()).isEqualTo(409);
        assertThat(second.errorCode()).isEqualTo("INVITATION_NOT_PENDING");
    }

    @Test
    void anExpiredInvitationCannotBeAccepted() {
        Session invitee = newUser("late");
        invite(owner, workspaceId, invitee.email(), "AGENT");
        String token = mail.invitationToken(invitee.email());

        clock.advance(Duration.ofDays(8));
        Response accepted = acceptInvitation(invitee, token);

        assertThat(accepted.status()).isEqualTo(410);
        assertThat(accepted.errorCode()).isEqualTo("INVITATION_EXPIRED");
        clock.reset();
        assertThat(get(base(), invitee).status()).isEqualTo(404);
    }

    @Test
    void anUnknownInvitationTokenIsNotFound() {
        Response response = acceptInvitation(agent.session(), "this-token-does-not-exist");

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.errorCode()).isEqualTo("INVITATION_NOT_FOUND");
    }

    @Test
    void invalidTimezoneIsRejected() {
        Response response = patch(base(), "{\"timezone\": \"Mars/Olympus\"}", owner);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.errorCode()).isEqualTo("INVALID_TIMEZONE");
    }

    private static void assertForbidden(Response response) {
        assertThat(response.status()).as(response.body()).isEqualTo(403);
        assertThat(response.errorCode()).isEqualTo("FORBIDDEN");
    }
}
