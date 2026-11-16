package com.servora.membership;

import static com.servora.membership.WorkspaceRole.ADMIN;
import static com.servora.membership.WorkspaceRole.AGENT;
import static com.servora.membership.WorkspaceRole.OWNER;
import static com.servora.membership.WorkspaceRole.SUPERVISOR;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The workspace role matrix from handbook section 2.1, stated independently of the implementation. */
class PermissionTest {

    private static final Map<Permission, Set<WorkspaceRole>> EXPECTED = new EnumMap<>(Permission.class);

    static {
        EXPECTED.put(Permission.VIEW_WORKSPACE, EnumSet.of(OWNER, ADMIN, SUPERVISOR, AGENT));
        EXPECTED.put(Permission.MANAGE_WORKSPACE_SETTINGS, EnumSet.of(OWNER, ADMIN));
        EXPECTED.put(Permission.MANAGE_MEMBERS, EnumSet.of(OWNER, ADMIN));
        EXPECTED.put(Permission.VIEW_AUDIT_LOG, EnumSet.of(OWNER, ADMIN));
        EXPECTED.put(Permission.CONFIGURE_AI, EnumSet.of(OWNER, ADMIN));
        EXPECTED.put(Permission.MANAGE_KNOWLEDGE, EnumSet.of(OWNER, ADMIN));
        EXPECTED.put(Permission.VIEW_ALL_CONVERSATIONS, EnumSet.of(OWNER, ADMIN, SUPERVISOR));
        EXPECTED.put(Permission.ASSIGN_TICKETS, EnumSet.of(OWNER, ADMIN, SUPERVISOR));
        EXPECTED.put(Permission.REPLY_TO_CUSTOMERS, EnumSet.of(OWNER, ADMIN, SUPERVISOR, AGENT));
        EXPECTED.put(Permission.MANAGE_SUBSCRIPTION, EnumSet.of(OWNER));
    }

    @Test
    void everyPermissionIsCoveredByThisTest() {
        assertThat(EXPECTED.keySet()).containsExactlyInAnyOrder(Permission.values());
    }

    @Test
    void eachPermissionIsGrantedToExactlyTheExpectedRoles() {
        for (Permission permission : Permission.values()) {
            Set<WorkspaceRole> granted = EnumSet.noneOf(WorkspaceRole.class);
            Arrays.stream(WorkspaceRole.values()).filter(permission::grantedTo).forEach(granted::add);
            assertThat(granted).as(permission.name()).isEqualTo(EXPECTED.get(permission));
        }
    }

    @Test
    void anOwnerHoldsEveryPermission() {
        assertThat(Permission.values()).allMatch(permission -> permission.grantedTo(OWNER));
    }
}
