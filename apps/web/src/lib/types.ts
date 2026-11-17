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

export type Tone = "FRIENDLY" | "PROFESSIONAL" | "CONCISE";
export type ConfidencePolicy = "STRICT" | "BALANCED";

export interface Assistant {
  id: string;
  name: string;
  welcome_message: string;
  fallback_message: string;
  language: string;
  tone: Tone;
  automation_enabled: boolean;
  confidence_policy: ConfidencePolicy;
  current_version: number;
  updated_at: string;
}

export interface Widget {
  id: string;
  name: string;
  public_key: string;
  status: "ACTIVE" | "DISABLED";
  theme_color: string;
  position: "BOTTOM_RIGHT" | "BOTTOM_LEFT";
  title: string;
  show_branding: boolean;
  allowed_origins: string[];
  updated_at: string;
}

export type DocumentStatus = "UPLOADED" | "QUEUED" | "PROCESSING" | "READY" | "FAILED" | "ARCHIVED";

export interface KnowledgeDocument {
  id: string;
  lineage_id: string;
  version: number;
  filename: string;
  mime: string;
  size_bytes: number;
  status: DocumentStatus;
  error_code: string | null;
  chunk_count: number;
  created_at: string;
  processed_at: string | null;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

export type AnswerOutcome =
  | "ANSWERED"
  | "ESCALATED"
  | "NO_EVIDENCE"
  | "PROVIDER_UNAVAILABLE"
  | "INVALID_OUTPUT"
  | "INVALID_CITATION"
  | "UNGROUNDED"
  | "REFUSED"
  | "SERVICE_UNAVAILABLE";

export interface AnswerCitation {
  chunk_id: string;
  document_id: string;
  filename: string;
  page_number: number | null;
  heading: string | null;
  snippet: string;
}

export interface RetrievalHit {
  chunk_id: string;
  document_id: string;
  filename: string;
  score: number;
  used: boolean;
}

export interface PlaygroundResult {
  id: string;
  outcome: AnswerOutcome;
  answer: string;
  intent: string | null;
  needs_escalation: boolean;
  escalation_reason: string | null;
  citations: AnswerCitation[];
  retrieval: RetrievalHit[];
  provider: string;
  model: string | null;
  prompt_version: string;
  evidence_threshold: number;
  latency_ms: number;
  input_tokens: number;
  output_tokens: number;
}

export type ControlMode = "AI_ACTIVE" | "HUMAN_PENDING" | "HUMAN_ACTIVE";

export interface ConversationSummary {
  id: string;
  status: "OPEN" | "CLOSED";
  control_mode: ControlMode;
  origin: string;
  created_at: string;
  last_message_at: string | null;
  handoff_requested_at: string | null;
  message_count: number;
  last_message_preview: string | null;
}

export interface ThreadCitation {
  chunk_id: string;
  document_id: string;
  filename: string;
  page_number: number | null;
  heading: string | null;
  snippet: string;
}

export interface ThreadMessage {
  id: string;
  sender_type: "CUSTOMER" | "ASSISTANT" | "AGENT" | "SYSTEM";
  visibility: "PUBLIC" | "INTERNAL";
  delivery_status: "DELIVERED" | "SUPPRESSED";
  body: string;
  created_at: string;
  outcome: AnswerOutcome | null;
  citations: ThreadCitation[];
}
