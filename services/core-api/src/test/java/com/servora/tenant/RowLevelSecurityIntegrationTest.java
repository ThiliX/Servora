package com.servora.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.servora.support.IntegrationTestBase;
import com.servora.support.TwoTenants;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests the database's own defences, bypassing the application entirely. These connect directly as
 * the runtime role and prove what that role can and cannot do if application code ever forgets a
 * tenant predicate.
 */
class RowLevelSecurityIntegrationTest extends IntegrationTestBase {

    private static final List<String> TENANT_TABLES = List.of("tenants", "memberships", "invitations", "audit_events");

    private static TwoTenants tenants;

    @BeforeEach
    void createTenantsOnce() {
        if (tenants == null) {
            tenants = TwoTenants.create(this);
            invite(tenants.ownerA(), tenants.techStore(), uniqueEmail("rls-a"), "AGENT");
            invite(tenants.ownerB(), tenants.hotelPro(), uniqueEmail("rls-b"), "AGENT");
        }
    }

    @Test
    void runtimeRoleIsNotPrivilegedAndOwnsNoTables() throws SQLException {
        try (Connection connection = appConnection();
                Statement statement = connection.createStatement()) {
            ResultSet role = statement.executeQuery(
                    "SELECT rolsuper, rolbypassrls, rolcreaterole, rolcreatedb FROM pg_roles WHERE rolname = current_user");
            assertThat(role.next()).isTrue();
            assertThat(role.getBoolean("rolsuper")).isFalse();
            assertThat(role.getBoolean("rolbypassrls")).isFalse();
            assertThat(role.getBoolean("rolcreaterole")).isFalse();
            assertThat(role.getBoolean("rolcreatedb")).isFalse();

            ResultSet owned = statement.executeQuery(
                    "SELECT count(*) FROM pg_tables WHERE schemaname = 'public' AND tableowner = current_user");
            owned.next();
            assertThat(owned.getInt(1)).isZero();
        }
    }

    @Test
    void runtimeRoleCannotDisableRowLevelSecurityOrChangePolicies() throws SQLException {
        try (Connection connection = appConnection();
                Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.execute("ALTER TABLE memberships DISABLE ROW LEVEL SECURITY"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("must be owner");
            assertThatThrownBy(() -> statement.execute("DROP POLICY memberships_select ON memberships"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("must be owner");
        }
    }

    /** Guards future migrations: any table with a tenant_id column must have RLS enabled, forced and policied. */
    @Test
    void everyTenantOwnedTableHasForcedRowLevelSecurityAndAPolicy() throws SQLException {
        List<String> unprotected = new ArrayList<>();
        int checked = 0;
        try (Connection connection = ownerConnection();
                Statement statement = connection.createStatement()) {
            ResultSet tables = statement.executeQuery("""
                    SELECT c.relname,
                           c.relrowsecurity,
                           c.relforcerowsecurity,
                           (SELECT count(*) FROM pg_policy p WHERE p.polrelid = c.oid) AS policies
                    FROM pg_class c
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'public'
                      AND c.relkind = 'r'
                      AND (c.relname = 'tenants' OR EXISTS (
                            SELECT 1 FROM pg_attribute a
                            WHERE a.attrelid = c.oid AND a.attname = 'tenant_id' AND NOT a.attisdropped))
                    """);
            while (tables.next()) {
                checked++;
                if (!tables.getBoolean("relrowsecurity")
                        || !tables.getBoolean("relforcerowsecurity")
                        || tables.getInt("policies") == 0) {
                    unprotected.add(tables.getString("relname"));
                }
            }
        }
        assertThat(checked).isGreaterThanOrEqualTo(TENANT_TABLES.size());
        assertThat(unprotected).as("tenant-owned tables without forced RLS and a policy").isEmpty();
    }

    @Test
    void withoutTenantContextNoTenantOwnedRowIsVisible() throws SQLException {
        try (Connection connection = appConnection()) {
            for (String table : TENANT_TABLES) {
                assertThat(count(connection, table)).as(table).isZero();
            }
        }
    }

    @Test
    void tenantContextExposesOnlyThatTenantsRows() throws SQLException {
        try (Connection connection = appConnection()) {
            connection.setAutoCommit(false);
            setLocal(connection, "app.tenant_id", tenants.techStore());

            for (String table : List.of("memberships", "invitations", "audit_events")) {
                assertThat(distinctTenantIds(connection, table))
                        .as(table)
                        .containsExactly(tenants.techStore());
            }
            assertThat(distinctIds(connection, "tenants")).containsExactly(tenants.techStore());
            connection.rollback();
        }
    }

    @Test
    void userContextExposesOnlyThatUsersMembershipsAndWorkspaces() throws SQLException {
        try (Connection connection = appConnection()) {
            connection.setAutoCommit(false);
            setLocal(connection, "app.user_id", tenants.agentA().session().userId());

            assertThat(count(connection, "memberships")).isEqualTo(1);
            assertThat(distinctIds(connection, "tenants")).containsExactly(tenants.techStore());
            assertThat(count(connection, "invitations")).isZero();
            assertThat(count(connection, "audit_events")).isZero();
            connection.rollback();
        }
    }

    @Test
    void rowsCannotBeWrittenIntoOrMovedToAnotherTenant() throws SQLException {
        try (Connection connection = appConnection()) {
            connection.setAutoCommit(false);
            setLocal(connection, "app.tenant_id", tenants.techStore());

            assertThatThrownBy(() -> update(
                            connection,
                            "INSERT INTO audit_events (id, tenant_id, action, resource_type) VALUES (?, ?, 'FORGED', 'test')",
                            UUID.randomUUID(),
                            tenants.hotelPro()))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("row-level security");
            connection.rollback();

            setLocal(connection, "app.tenant_id", tenants.techStore());
            assertThatThrownBy(() -> update(
                            connection,
                            "UPDATE memberships SET tenant_id = ? WHERE tenant_id = ?",
                            tenants.hotelPro(),
                            tenants.techStore()))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("row-level security");
            connection.rollback();

            // Another tenant's rows are simply not there to update.
            setLocal(connection, "app.tenant_id", tenants.techStore());
            assertThat(update(connection, "UPDATE tenants SET name = 'Hijacked' WHERE id = ?", tenants.hotelPro()))
                    .isZero();
            assertThat(update(
                            connection,
                            "UPDATE memberships SET role = 'OWNER' WHERE tenant_id = ?",
                            tenants.hotelPro()))
                    .isZero();
            connection.rollback();
        }
    }

    @Test
    void tenantOwnedRowsCannotBeDeletedAndAuditEventsCannotBeChanged() throws SQLException {
        try (Connection connection = appConnection()) {
            connection.setAutoCommit(false);
            for (String table : TENANT_TABLES) {
                setLocal(connection, "app.tenant_id", tenants.techStore());
                assertThatThrownBy(() -> update(connection, "DELETE FROM " + table))
                        .as(table)
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("permission denied");
                connection.rollback();
            }
            setLocal(connection, "app.tenant_id", tenants.techStore());
            assertThatThrownBy(() -> update(connection, "UPDATE audit_events SET action = 'REWRITTEN'"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
            connection.rollback();
        }
    }

    @Test
    void anInvitationIsReadableByTokenHashButOnlyThatOneInvitation() throws SQLException {
        String tokenHash;
        try (Connection owner = ownerConnection();
                PreparedStatement statement = owner.prepareStatement(
                        "SELECT token_hash FROM invitations WHERE tenant_id = ? AND status = 'PENDING' LIMIT 1")) {
            statement.setObject(1, tenants.hotelPro());
            ResultSet result = statement.executeQuery();
            assertThat(result.next()).isTrue();
            tokenHash = result.getString(1);
        }
        try (Connection connection = appConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement =
                    connection.prepareStatement("SELECT set_config('app.invitation_token_hash', ?, true)")) {
                statement.setString(1, tokenHash);
                statement.execute();
            }
            assertThat(count(connection, "invitations")).isEqualTo(1);
            assertThat(count(connection, "memberships")).isZero();
            assertThat(count(connection, "tenants")).isZero();
            connection.rollback();
        }
    }

    /** The context is transaction-local: the same physical connection is clean after commit and after rollback. */
    @Test
    void tenantContextDoesNotSurviveTheTransactionOnTheSameConnection() throws SQLException {
        try (Connection connection = appConnection()) {
            connection.setAutoCommit(false);

            setLocal(connection, "app.tenant_id", tenants.techStore());
            assertThat(count(connection, "memberships")).isPositive();
            connection.commit();
            assertThat(currentSetting(connection, "app.tenant_id")).isNullOrEmpty();
            assertThat(count(connection, "memberships")).isZero();
            connection.commit();

            setLocal(connection, "app.tenant_id", tenants.hotelPro());
            assertThat(count(connection, "memberships")).isPositive();
            connection.rollback();
            assertThat(currentSetting(connection, "app.tenant_id")).isNullOrEmpty();
            assertThat(count(connection, "memberships")).isZero();
            connection.rollback();
        }
    }

    private static void setLocal(Connection connection, String name, UUID value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT set_config(?, ?, true)")) {
            statement.setString(1, name);
            statement.setString(2, value.toString());
            statement.execute();
        }
    }

    private static String currentSetting(Connection connection, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT current_setting(?, true)")) {
            statement.setString(1, name);
            ResultSet result = statement.executeQuery();
            result.next();
            return result.getString(1);
        }
    }

    private static int count(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            ResultSet result = statement.executeQuery("SELECT count(*) FROM " + table);
            result.next();
            return result.getInt(1);
        }
    }

    private static List<UUID> distinctTenantIds(Connection connection, String table) throws SQLException {
        return uuids(connection, "SELECT DISTINCT tenant_id FROM " + table);
    }

    private static List<UUID> distinctIds(Connection connection, String table) throws SQLException {
        return uuids(connection, "SELECT id FROM " + table);
    }

    private static List<UUID> uuids(Connection connection, String sql) throws SQLException {
        List<UUID> ids = new ArrayList<>();
        try (Statement statement = connection.createStatement()) {
            ResultSet result = statement.executeQuery(sql);
            while (result.next()) {
                ids.add(result.getObject(1, UUID.class));
            }
        }
        return ids;
    }

    private static int update(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            return statement.executeUpdate();
        }
    }
}
