package com.servora.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.servora.support.IntegrationTestBase;
import com.servora.support.TwoTenants;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proves tenant context does not leak between requests that share pooled connections.
 *
 * <p>The pool holds only two connections, so the many parallel requests below, alternating between
 * two tenants and mixing reads, writes and rejected requests, are forced to reuse the same physical
 * connections one after another. Every response must contain only the caller's own tenant.
 */
class PooledConnectionIsolationIntegrationTest extends IntegrationTestBase {

    private static final int ROUNDS = 40;
    private static final int THREADS = 12;

    @Autowired
    private DataSource dataSource;

    private record Outcome(String description, boolean ok, String detail) {}

    @Test
    void parallelRequestsFromTwoTenantsNeverSeeEachOthersData() throws Exception {
        TwoTenants tenants = TwoTenants.create(this);
        List<Callable<Outcome>> calls = new ArrayList<>();

        for (int i = 0; i < ROUNDS; i++) {
            calls.add(() -> ownMembers(
                    "A members",
                    tenants.ownerA(),
                    tenants.techStore(),
                    tenants.ownerA().email(),
                    tenants.ownerB().email()));
            calls.add(() -> ownMembers(
                    "B members",
                    tenants.ownerB(),
                    tenants.hotelPro(),
                    tenants.ownerB().email(),
                    tenants.ownerA().email()));
            calls.add(() -> ownWorkspace("A workspace", tenants.agentA().session(), tenants.techStore(), "TechStore"));
            calls.add(() -> ownWorkspace("B workspace", tenants.agentB().session(), tenants.hotelPro(), "HotelPro"));
            // Writes, so connections return to the pool after a committed tenant-scoped transaction.
            calls.add(() -> write("A write", tenants.ownerA(), tenants.techStore()));
            calls.add(() -> write("B write", tenants.ownerB(), tenants.hotelPro()));
            // Rejected cross-tenant requests, so connections also return after a rollback.
            calls.add(() -> denied("A reads B", tenants.ownerA(), tenants.hotelPro()));
            calls.add(() -> denied("B reads A", tenants.ownerB(), tenants.techStore()));
        }

        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        List<Outcome> failures = new ArrayList<>();
        try {
            for (Future<Outcome> future : executor.invokeAll(calls, 5, TimeUnit.MINUTES)) {
                Outcome outcome = future.get();
                if (!outcome.ok()) {
                    failures.add(outcome);
                }
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(calls).hasSize(ROUNDS * 8);
        assertThat(failures).isEmpty();
        assertPooledConnectionsCarryNoContext();
    }

    private Outcome ownMembers(String description, Session session, UUID workspaceId, String ownEmail, String otherEmail) {
        Response response = get("/api/v1/workspaces/" + workspaceId + "/members", session);
        boolean ok = response.status() == 200
                && response.body().contains(ownEmail)
                && !response.body().contains(otherEmail);
        return new Outcome(description, ok, response.status() + " " + response.body());
    }

    private Outcome ownWorkspace(String description, Session session, UUID workspaceId, String expectedName) {
        Response response = get("/api/v1/workspaces/" + workspaceId, session);
        boolean ok = response.status() == 200
                && expectedName.equals(response.json("$.name"))
                && workspaceId.toString().equals(response.json("$.id"));
        return new Outcome(description, ok, response.status() + " " + response.body());
    }

    private Outcome write(String description, Session owner, UUID workspaceId) {
        Response response = patch("/api/v1/workspaces/" + workspaceId, "{\"industry\": \"Testing\"}", owner);
        boolean ok = response.status() == 200 && workspaceId.toString().equals(response.json("$.id"));
        return new Outcome(description, ok, response.status() + " " + response.body());
    }

    private Outcome denied(String description, Session session, UUID otherWorkspaceId) {
        Response response = get("/api/v1/workspaces/" + otherWorkspaceId + "/members", session);
        return new Outcome(description, response.status() == 404, response.status() + " " + response.body());
    }

    /** Borrows every connection in the pool at once and checks each is clean. */
    private void assertPooledConnectionsCarryNoContext() throws Exception {
        List<Connection> borrowed = new ArrayList<>();
        try {
            for (int i = 0; i < POOL_SIZE; i++) {
                borrowed.add(dataSource.getConnection());
            }
            for (Connection connection : borrowed) {
                // Result sets are closed explicitly: the JDBC tracing keeps an observation open on this
                // thread until they are, which would leak a span into later tests.
                try (Statement statement = connection.createStatement();
                        ResultSet settings = statement.executeQuery("""
                                SELECT current_setting('app.tenant_id', true),
                                       current_setting('app.user_id', true),
                                       current_setting('app.invitation_token_hash', true)
                                """)) {
                    settings.next();
                    assertThat(settings.getString(1)).isNullOrEmpty();
                    assertThat(settings.getString(2)).isNullOrEmpty();
                    assertThat(settings.getString(3)).isNullOrEmpty();
                }
                try (Statement statement = connection.createStatement();
                        ResultSet visible = statement.executeQuery(
                                "SELECT (SELECT count(*) FROM memberships) + (SELECT count(*) FROM tenants)")) {
                    visible.next();
                    assertThat(visible.getInt(1)).isZero();
                }
            }
        } finally {
            for (Connection connection : borrowed) {
                connection.close();
            }
        }
    }
}
