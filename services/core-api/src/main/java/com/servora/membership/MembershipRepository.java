package com.servora.membership;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Every method is scoped by tenant or by the authenticated user. There is deliberately no
 * {@code findById}: a membership is never loaded by its ID alone.
 */
public interface MembershipRepository extends Repository<Membership, UUID> {

    Membership save(Membership membership);

    Optional<Membership> findByTenantIdAndUserId(UUID tenantId, UUID userId);

    Optional<Membership> findByTenantIdAndId(UUID tenantId, UUID id);

    /** Locks the tenant's active owner rows so concurrent changes cannot remove the last owner. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select m from Membership m
            where m.tenantId = :tenantId
              and m.role = com.servora.membership.WorkspaceRole.OWNER
              and m.status = com.servora.membership.Membership.Status.ACTIVE
            """)
    List<Membership> lockActiveOwners(@Param("tenantId") UUID tenantId);

    @Query(
            value = """
                    select new com.servora.membership.MemberView(
                        m.id, u.id, u.email, u.displayName, m.role, m.status, m.joinedAt)
                    from Membership m join User u on u.id = m.userId
                    where m.tenantId = :tenantId
                    """,
            countQuery = "select count(m) from Membership m where m.tenantId = :tenantId")
    Page<MemberView> findMembers(@Param("tenantId") UUID tenantId, Pageable pageable);

    @Query("""
            select new com.servora.membership.MemberView(
                m.id, u.id, u.email, u.displayName, m.role, m.status, m.joinedAt)
            from Membership m join User u on u.id = m.userId
            where m.tenantId = :tenantId and m.id = :id
            """)
    Optional<MemberView> findMember(@Param("tenantId") UUID tenantId, @Param("id") UUID id);
}
