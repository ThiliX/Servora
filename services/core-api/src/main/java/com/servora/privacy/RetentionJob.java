package com.servora.privacy;

import com.servora.audit.AuditService;
import com.servora.common.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes conversations whose last activity is older than their workspace's retention period.
 * A conversation with a ticket that is still being worked on is kept until the ticket is resolved.
 * Each workspace is handled in its own transaction, at most one batch per run.
 */
@Component
@EnableConfigurationProperties({DataProperties.Retention.class, DataProperties.Export.class})
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private record Policy(UUID tenantId, int days) {}

    private final JdbcClient jdbc;
    private final TenantContext tenantContext;
    private final AuditService audit;
    private final DataProperties.Retention properties;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public RetentionJob(
            JdbcClient jdbc,
            TenantContext tenantContext,
            AuditService audit,
            DataProperties.Retention properties,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${servora.retention.interval:PT1H}", initialDelayString = "PT1M")
    public void scheduled() {
        if (properties.enabled()) {
            purgeExpired();
        }
    }

    /** Runs one pass over every workspace with a retention period. Returns the number of conversations deleted. */
    public int purgeExpired() {
        List<Policy> policies = transactions.execute(status -> {
            tenantContext.enableRetentionJob();
            return jdbc.sql("""
                            SELECT id, conversation_retention_days FROM tenants
                            WHERE conversation_retention_days IS NOT NULL ORDER BY id
                            """)
                    .query((rs, n) -> new Policy(rs.getObject("id", UUID.class), rs.getInt("conversation_retention_days")))
                    .list();
        });
        int total = 0;
        for (Policy policy : policies) {
            try {
                total += purge(policy);
            } catch (RuntimeException e) {
                // One workspace's failure must not stop the others.
                log.error("Retention purge failed for tenant {}", policy.tenantId(), e);
            }
        }
        return total;
    }

    private int purge(Policy policy) {
        Integer deleted = transactions.execute(status -> {
            // The tenant ID was read from the tenants table itself, so it is a real workspace.
            tenantContext.bindTenant(policy.tenantId());
            Timestamp cutoff = Timestamp.from(clock.instant().minus(Duration.ofDays(policy.days())));
            List<UUID> expired = jdbc.sql("""
                            SELECT c.id FROM conversations c
                            WHERE c.tenant_id = :tenant
                              AND coalesce(c.last_message_at, c.created_at) < :cutoff
                              AND NOT EXISTS (SELECT 1 FROM tickets t
                                              WHERE t.tenant_id = c.tenant_id AND t.conversation_id = c.id
                                                AND t.status IN ('OPEN', 'IN_PROGRESS', 'WAITING_FOR_CUSTOMER'))
                            ORDER BY coalesce(c.last_message_at, c.created_at), c.id
                            LIMIT :limit
                            """)
                    .param("tenant", policy.tenantId())
                    .param("cutoff", cutoff)
                    .param("limit", properties.batchSize())
                    .query(UUID.class)
                    .list();
            int count = 0;
            for (UUID conversationId : expired) {
                if (Boolean.TRUE.equals(jdbc.sql("SELECT delete_conversation_data(:id)")
                        .param("id", conversationId)
                        .query(Boolean.class)
                        .single())) {
                    count++;
                }
            }
            if (count > 0) {
                audit.recordFor(policy.tenantId(), null, "CONVERSATIONS_PURGED", "workspace", policy.tenantId(),
                        Map.of("count", String.valueOf(count), "retention_days", String.valueOf(policy.days())));
            }
            return count;
        });
        if (deleted != null && deleted > 0) {
            log.info("Retention purge tenant={} deleted={}", policy.tenantId(), deleted);
        }
        return deleted == null ? 0 : deleted;
    }
}
