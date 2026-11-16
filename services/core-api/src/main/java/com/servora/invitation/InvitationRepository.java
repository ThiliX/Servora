package com.servora.invitation;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

/** Tenant-scoped, except the token lookup, which RLS limits to the one invitation whose token was presented. */
public interface InvitationRepository extends Repository<Invitation, UUID> {

    Invitation save(Invitation invitation);

    Invitation saveAndFlush(Invitation invitation);

    Optional<Invitation> findByTenantIdAndId(UUID tenantId, UUID id);

    Page<Invitation> findByTenantId(UUID tenantId, Pageable pageable);

    Optional<Invitation> findByTokenHash(String tokenHash);
}
