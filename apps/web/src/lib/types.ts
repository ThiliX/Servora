// Response shapes of the core API. Field names match the wire format (snake_case).

export type WorkspaceRole = "OWNER" | "ADMIN" | "SUPERVISOR" | "AGENT";
export type MembershipStatus = "ACTIVE" | "DISABLED";
export type InvitationStatus = "PENDING" | "ACCEPTED" | "REVOKED";

export interface User {
  id: string;
  email: string;
  display_name: string;
}

export interface SessionResponse {
  access_token: string;
  token_type: "Bearer";
  expires_in: number;
  user: User;
}

export interface WorkspaceSummary {
  id: string;
  name: string;
  slug: string;
  role: WorkspaceRole;
}

export interface Workspace extends WorkspaceSummary {
  status: string;
  timezone: string;
  default_language: string;
  industry: string | null;
  logo_url: string | null;
  created_at: string;
}

export interface Member {
  id: string;
  user_id: string;
  email: string;
  display_name: string;
  role: WorkspaceRole;
  status: MembershipStatus;
  joined_at: string;
}

export interface Invitation {
  id: string;
  email: string;
  role: WorkspaceRole;
  status: InvitationStatus;
  expires_at: string;
  created_at: string;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}
