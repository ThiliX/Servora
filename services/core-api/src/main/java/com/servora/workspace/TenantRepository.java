package com.servora.workspace;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The tenant row is the tenant boundary itself, so it is loaded by its own ID. Row-Level Security
 * returns it only for the current tenant or for a tenant the current user is an active member of.
 */
public interface TenantRepository extends Repository<Tenant, UUID> {

    Tenant saveAndFlush(Tenant tenant);

    Tenant save(Tenant tenant);

    Optional<Tenant> findById(UUID id);

    @Query("""
            select new com.servora.workspace.WorkspaceSummary(t.id, t.name, t.slug, m.role)
            from Membership m join Tenant t on t.id = m.tenantId
            where m.userId = :userId
              and m.status = com.servora.membership.Membership.Status.ACTIVE
              and t.status = com.servora.workspace.Tenant.Status.ACTIVE
            order by t.name, t.id
            """)
    List<WorkspaceSummary> findActiveWorkspacesOf(@Param("userId") UUID userId);
}
