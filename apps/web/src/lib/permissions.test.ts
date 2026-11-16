import { describe, expect, it } from "vitest";
import { assignableRoles, canEditMember, canManageMembers, canManageSettings, INVITABLE_ROLES } from "./permissions";
import type { WorkspaceRole } from "./types";

const ROLES: WorkspaceRole[] = ["OWNER", "ADMIN", "SUPERVISOR", "AGENT"];

describe("permissions", () => {
  it("lets only owners and admins manage members and settings", () => {
    expect(ROLES.filter(canManageMembers)).toEqual(["OWNER", "ADMIN"]);
    expect(ROLES.filter(canManageSettings)).toEqual(["OWNER", "ADMIN"]);
  });

  it("lets only an owner grant ownership", () => {
    expect(assignableRoles("OWNER")).toContain("OWNER");
    expect(assignableRoles("ADMIN")).not.toContain("OWNER");
    expect(assignableRoles("SUPERVISOR")).toEqual([]);
    expect(assignableRoles("AGENT")).toEqual([]);
  });

  it("never offers ownership by invitation", () => {
    expect(INVITABLE_ROLES).not.toContain("OWNER");
  });

  it("prevents members from editing themselves", () => {
    expect(canEditMember({ role: "OWNER", userId: "u1" }, { role: "OWNER", user_id: "u1" })).toBe(false);
  });

  it("prevents an admin from editing an owner but allows editing others", () => {
    const admin = { role: "ADMIN" as const, userId: "admin" };
    expect(canEditMember(admin, { role: "OWNER", user_id: "owner" })).toBe(false);
    expect(canEditMember(admin, { role: "AGENT", user_id: "agent" })).toBe(true);
  });

  it("prevents supervisors and agents from editing anyone", () => {
    for (const role of ["SUPERVISOR", "AGENT"] as const) {
      expect(canEditMember({ role, userId: "me" }, { role: "AGENT", user_id: "other" })).toBe(false);
    }
  });
});
