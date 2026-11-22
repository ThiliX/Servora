import { useState, type FormEvent } from "react";
import { Button, Card, Empty, ErrorState, Field, FormError, Loading, Notice, PageHeader, Select } from "../components/ui";
import { api, type ApiError } from "../lib/api";
import { assignableRoles, canEditMember, canManageMembers, INVITABLE_ROLES, ROLE_LABELS } from "../lib/permissions";
import type { Invitation, Member, MembershipStatus, Page, WorkspaceRole } from "../lib/types";
import { toApiError, useAsync } from "../lib/useAsync";
import { useWorkspace } from "./WorkspaceLayout";

function MembersCard() {
  const { workspace, user } = useWorkspace();
  const base = `/workspaces/${workspace.id}/members`;
  const { state, reload } = useAsync(() => api<Page<Member>>(`${base}?size=100`), base);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const actor = { role: workspace.role, userId: user.id };

  // Controls stay disabled while a change is in flight so two edits cannot race.
  async function change(member: Member, patch: { role?: WorkspaceRole; status?: MembershipStatus }) {
    setBusyId(member.id);
    setError(null);
    try {
      await api<Member>(`${base}/${member.id}`, { method: "PATCH", body: patch });
      reload();
    } catch (caught) {
      setError(toApiError(caught));
    } finally {
      setBusyId(null);
    }
  }

  return (
    <Card title="Members">
      <div className="space-y-3">
        <FormError error={error} />
        {state.status === "loading" && <Loading label="Loading members" />}
        {state.status === "error" && <ErrorState error={state.error} onRetry={reload} />}
        {state.status === "ready" && state.data.items.length === 0 && <Empty>No members yet.</Empty>}
        {state.status === "ready" && state.data.items.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="text-xs uppercase text-fg-subtle">
                <tr>
                  <th scope="col" className="py-2 pr-4">
                    Member
                  </th>
                  <th scope="col" className="py-2 pr-4">
                    Role
                  </th>
                  <th scope="col" className="py-2 pr-4">
                    Status
                  </th>
                  <th scope="col" className="py-2">
                    <span className="sr-only">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line">
                {state.data.items.map((member) => {
                  const editable = canEditMember(actor, member);
                  const busy = busyId === member.id;
                  return (
                    <tr key={member.id}>
                      <td className="py-3 pr-4">
                        <div className="font-medium">
                          {member.display_name}
                          {member.user_id === user.id && <span className="ml-1 text-fg-subtle">(you)</span>}
                        </div>
                        <div className="text-fg-subtle">{member.email}</div>
                      </td>
                      <td className="py-3 pr-4">
                        {editable ? (
                          <Select
                            id={`role-${member.id}`}
                            label={`Role for ${member.display_name}`}
                            hideLabel
                            disabled={busy}
                            value={member.role}
                            onChange={(e) => void change(member, { role: e.target.value as WorkspaceRole })}
                          >
                            {assignableRoles(workspace.role).map((role) => (
                              <option key={role} value={role}>
                                {ROLE_LABELS[role]}
                              </option>
                            ))}
                          </Select>
                        ) : (
                          ROLE_LABELS[member.role]
                        )}
                      </td>
                      <td className="py-3 pr-4">{member.status === "ACTIVE" ? "Active" : "Disabled"}</td>
                      <td className="py-3 text-right">
                        {editable && (
                          <Button
                            variant={member.status === "ACTIVE" ? "danger" : "secondary"}
                            disabled={busy}
                            onClick={() =>
                              void change(member, { status: member.status === "ACTIVE" ? "DISABLED" : "ACTIVE" })
                            }
                          >
                            {member.status === "ACTIVE" ? "Disable" : "Enable"}
                          </Button>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </Card>
  );
}

function InvitationsCard() {
  const { workspace } = useWorkspace();
  const base = `/workspaces/${workspace.id}/invitations`;
  const { state, reload } = useAsync(() => api<Page<Invitation>>(`${base}?size=100`), base);
  const [email, setEmail] = useState("");
  const [role, setRole] = useState<WorkspaceRole>("AGENT");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [sentTo, setSentTo] = useState<string | null>(null);

  async function invite(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    setSentTo(null);
    try {
      await api<Invitation>(base, { method: "POST", body: { email, role } });
      setSentTo(email);
      setEmail("");
      reload();
    } catch (caught) {
      setError(toApiError(caught));
    } finally {
      setSubmitting(false);
    }
  }

  async function revoke(invitation: Invitation) {
    setError(null);
    try {
      await api(`${base}/${invitation.id}`, { method: "DELETE" });
      reload();
    } catch (caught) {
      setError(toApiError(caught));
    }
  }

  const pending = state.status === "ready" ? state.data.items.filter((item) => item.status === "PENDING") : [];
  return (
    <Card title="Invitations">
      <div className="space-y-4">
        <form onSubmit={invite} className="flex flex-wrap items-end gap-3" noValidate>
          <div className="min-w-56 flex-1">
            <Field
              id="invite-email"
              label="Email address"
              type="email"
              required
              value={email}
              onChange={(e) => setEmail(e.target.value)}
            />
          </div>
          <div className="w-40">
            <Select id="invite-role" label="Role" value={role} onChange={(e) => setRole(e.target.value as WorkspaceRole)}>
              {INVITABLE_ROLES.map((item) => (
                <option key={item} value={item}>
                  {ROLE_LABELS[item]}
                </option>
              ))}
            </Select>
          </div>
          <Button type="submit" disabled={submitting || !email}>
            {submitting ? "Sending…" : "Send invitation"}
          </Button>
        </form>
        <FormError error={error} />
        {sentTo && <Notice tone="success">Invitation created for {sentTo}.</Notice>}

        {state.status === "loading" && <Loading label="Loading invitations" />}
        {state.status === "error" && <ErrorState error={state.error} onRetry={reload} />}
        {state.status === "ready" && pending.length === 0 && <Empty>No pending invitations.</Empty>}
        {pending.length > 0 && (
          <ul className="divide-y divide-line text-sm">
            {pending.map((invitation) => (
              <li key={invitation.id} className="flex items-center justify-between py-3">
                <span>
                  <span className="font-medium">{invitation.email}</span>
                  <span className="ml-2 text-fg-subtle">
                    {ROLE_LABELS[invitation.role]} · expires {new Date(invitation.expires_at).toLocaleDateString()}
                  </span>
                </span>
                <Button variant="danger" onClick={() => void revoke(invitation)}>
                  Revoke
                </Button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </Card>
  );
}

export function TeamPage() {
  const { workspace } = useWorkspace();
  return (
    <>
      <PageHeader title="Team" description="The people in this workspace and their roles." />
      <MembersCard />
      {canManageMembers(workspace.role) && <InvitationsCard />}
    </>
  );
}
