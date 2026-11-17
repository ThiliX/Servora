import type { WorkspaceRole } from "./types";

// Mirrors the backend role matrix so the UI can hide controls a role cannot use.
// This is a convenience only: the backend enforces every permission.

const MANAGERS: readonly WorkspaceRole[] = ["OWNER", "ADMIN"];

export function canManageMembers(role: WorkspaceRole): boolean {
  return MANAGERS.includes(role);
}

export function canManageSettings(role: WorkspaceRole): boolean {
  return MANAGERS.includes(role);
}

/** Assistant, widget and knowledge base configuration. */
export function canConfigureAssistant(role: WorkspaceRole): boolean {
  return MANAGERS.includes(role);
}

/** Roles an actor may assign to someone else. Only an owner can grant ownership. */
export function assignableRoles(actor: WorkspaceRole): WorkspaceRole[] {
  if (actor === "OWNER") {
    return ["OWNER", "ADMIN", "SUPERVISOR", "AGENT"];
  }
  if (actor === "ADMIN") {
    return ["ADMIN", "SUPERVISOR", "AGENT"];
  }
  return [];
}

/** Whether an actor may change the given member. Nobody may change themselves; only owners may change owners. */
export function canEditMember(
  actor: { role: WorkspaceRole; userId: string },
  target: { role: WorkspaceRole; user_id: string },
): boolean {
  if (!canManageMembers(actor.role) || actor.userId === target.user_id) {
    return false;
  }
  return target.role !== "OWNER" || actor.role === "OWNER";
}

/** Ownership is never granted by invitation. */
export const INVITABLE_ROLES: readonly WorkspaceRole[] = ["ADMIN", "SUPERVISOR", "AGENT"];

export const ROLE_LABELS: Record<WorkspaceRole, string> = {
  OWNER: "Owner",
  ADMIN: "Admin",
  SUPERVISOR: "Supervisor",
  AGENT: "Agent",
};

/** Reading every conversation in the workspace. Agents get their own assigned conversations in M5. */
export function canViewConversations(role: WorkspaceRole): boolean {
  return role === "OWNER" || role === "ADMIN" || role === "SUPERVISOR";
}

/** Assigning tickets to team members. */
export function canAssignTickets(role: WorkspaceRole): boolean {
  return role === "OWNER" || role === "ADMIN" || role === "SUPERVISOR";
}
