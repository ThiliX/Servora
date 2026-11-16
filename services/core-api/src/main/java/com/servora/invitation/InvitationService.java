package com.servora.invitation;

import com.servora.audit.AuditService;
import com.servora.auth.User;
import com.servora.auth.UserRepository;
import com.servora.common.error.ApiException;
import com.servora.common.mail.MailGateway;
import com.servora.common.security.AuthProperties;
import com.servora.common.security.Tokens;
import com.servora.common.tenant.TenantContext;
import com.servora.common.web.PageResponse;
import com.servora.membership.Membership;
import com.servora.membership.MembershipRepository;
import com.servora.membership.Permission;
import com.servora.membership.WorkspaceAccess;
import com.servora.membership.WorkspaceContext;
import com.servora.membership.WorkspaceRole;
import com.servora.workspace.TenantRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvitationService {

    /** The token and its hash are never part of a response. */
    public record InvitationView(
            UUID id, String email, WorkspaceRole role, Invitation.Status status, Instant expiresAt, Instant createdAt) {

        static InvitationView of(Invitation invitation) {
            return new InvitationView(
                    invitation.getId(),
                    invitation.getEmail(),
                    invitation.getRole(),
                    invitation.getStatus(),
                    invitation.getExpiresAt(),
                    invitation.getCreatedAt());
        }
    }

    public record AcceptedInvitation(UUID workspaceId, WorkspaceRole role) {}

    private final WorkspaceAccess access;
    private final TenantContext tenantContext;
    private final InvitationRepository invitations;
    private final MembershipRepository memberships;
    private final TenantRepository tenants;
    private final UserRepository users;
    private final AuditService audit;
    private final MailGateway mail;
    private final AuthProperties properties;
    private final Clock clock;
    private final String webBaseUrl;

    public InvitationService(
            WorkspaceAccess access,
            TenantContext tenantContext,
            InvitationRepository invitations,
            MembershipRepository memberships,
            TenantRepository tenants,
            UserRepository users,
            AuditService audit,
            MailGateway mail,
            AuthProperties properties,
            Clock clock,
            @Value("${servora.web.base-url}") String webBaseUrl) {
        this.access = access;
        this.tenantContext = tenantContext;
        this.invitations = invitations;
        this.memberships = memberships;
        this.tenants = tenants;
        this.users = users;
        this.audit = audit;
        this.mail = mail;
        this.properties = properties;
        this.clock = clock;
        this.webBaseUrl = webBaseUrl;
    }

    @Transactional
    public InvitationView create(UUID userId, UUID workspaceId, String email, WorkspaceRole role) {
        WorkspaceContext ctx = access.authorize(userId, workspaceId, Permission.MANAGE_MEMBERS);
        if (role == WorkspaceRole.OWNER) {
            throw ApiException.badRequest(
                    "INVALID_ROLE", "Ownership cannot be granted by invitation. Invite the member, then change their role.");
        }
        String normalized = Tokens.normalizeEmail(email);
        Instant now = clock.instant();

        Optional<User> existingUser = users.findByEmail(normalized);
        if (existingUser.isPresent()
                && memberships
                        .findByTenantIdAndUserId(ctx.tenantId(), existingUser.get().getId())
                        .isPresent()) {
            throw ApiException.conflict("ALREADY_MEMBER", "That person is already a member of this workspace.");
        }

        String token = Tokens.generate();
        Invitation invitation = new Invitation(
                ctx.tenantId(),
                normalized,
                role,
                Tokens.hash(token),
                now.plus(properties.invitationTtl()),
                ctx.membershipId(),
                now);
        try {
            invitations.saveAndFlush(invitation);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("INVITATION_EXISTS", "A pending invitation already exists for that address.");
        }
        audit.record(ctx, "INVITATION_CREATED", "invitation", invitation.getId(), Map.of("role", role.name()));

        String workspaceName = tenants.findById(ctx.tenantId()).orElseThrow().getName();
        // The token travels in the URL fragment, which browsers do not send to servers or proxies.
        mail.sendWorkspaceInvitation(normalized, workspaceName, webBaseUrl + "/accept-invitation#token=" + token);
        return InvitationView.of(invitation);
    }

    @Transactional(readOnly = true)
    public PageResponse<InvitationView> list(UUID userId, UUID workspaceId, int page, int size) {
        WorkspaceContext ctx = access.authorize(userId, workspaceId, Permission.MANAGE_MEMBERS);
        return PageResponse.from(
                invitations.findByTenantId(
                        ctx.tenantId(),
                        PageResponse.request(page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")))),
                InvitationView::of);
    }

    @Transactional
    public void revoke(UUID userId, UUID workspaceId, UUID invitationId) {
        WorkspaceContext ctx = access.authorize(userId, workspaceId, Permission.MANAGE_MEMBERS);
        Invitation invitation = invitations
                .findByTenantIdAndId(ctx.tenantId(), invitationId)
                .orElseThrow(InvitationService::notFound);
        if (!invitation.isPending()) {
            throw ApiException.conflict("INVITATION_NOT_PENDING", "The invitation is no longer pending.");
        }
        invitation.revoke(clock.instant());
        invitations.save(invitation);
        audit.record(ctx, "INVITATION_REVOKED", "invitation", invitation.getId(), Map.of());
    }

    /**
     * Accepts an invitation for the signed-in user.
     *
     * <p>The caller is not yet a member, so the tenant cannot be resolved from membership. The
     * presented token is the proof instead: its hash is bound as context, RLS then exposes exactly
     * that one invitation, and the tenant context is set from the invitation only after the token,
     * status, expiry and email have all been checked.
     */
    @Transactional
    public AcceptedInvitation accept(UUID userId, String token) {
        Instant now = clock.instant();
        String tokenHash = Tokens.hash(token);
        tenantContext.bindUser(userId);
        tenantContext.bindInvitationTokenHash(tokenHash);

        Invitation invitation = invitations.findByTokenHash(tokenHash).orElseThrow(InvitationService::notFound);
        if (!invitation.isPending()) {
            throw ApiException.conflict("INVITATION_NOT_PENDING", "The invitation is no longer pending.");
        }
        if (invitation.isExpired(now)) {
            throw new ApiException(HttpStatus.GONE, "INVITATION_EXPIRED", "The invitation has expired.");
        }
        User user = users.findById(userId).filter(User::isActive).orElseThrow(InvitationService::notFound);
        if (!user.getEmail().equals(invitation.getEmail())) {
            throw ApiException.forbidden(
                    "INVITATION_EMAIL_MISMATCH", "This invitation was sent to a different email address.");
        }

        tenantContext.bindTenant(invitation.getTenantId());
        if (memberships.findByTenantIdAndUserId(invitation.getTenantId(), userId).isPresent()) {
            throw ApiException.conflict("ALREADY_MEMBER", "You are already a member of this workspace.");
        }
        Membership membership =
                memberships.save(new Membership(invitation.getTenantId(), userId, invitation.getRole(), now));
        invitation.accept(now);
        invitations.save(invitation);
        audit.record(
                new WorkspaceContext(invitation.getTenantId(), userId, membership.getId(), membership.getRole()),
                "INVITATION_ACCEPTED",
                "invitation",
                invitation.getId(),
                Map.of("role", invitation.getRole().name()));
        return new AcceptedInvitation(invitation.getTenantId(), invitation.getRole());
    }

    private static ApiException notFound() {
        return ApiException.notFound("INVITATION_NOT_FOUND", "The invitation was not found.");
    }
}
