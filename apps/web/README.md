# Servora dashboard

React, TypeScript, Vite and Tailwind CSS dashboard for workspace owners, admins, supervisors and agents.

Covers sign-up and sign-in, workspaces and the team, the knowledge base, assistant and widget settings, the test playground, the inbox and conversations, the overview, usage and audit log, workspace settings and platform administration. Light and dark themes are built on semantic design tokens in `src/index.css`; components live in `src/components/ui.tsx`.

```bash
npm install
npm run dev
```

The dev server runs on <http://localhost:5173> and proxies `/api` to the core API on port 8080. Set `SERVORA_API_URL` to point it elsewhere.

```bash
npm run lint
npm run typecheck
npm test
npm run build
```

## Notes

- The access token is held in memory only. A page reload restores the session through the HttpOnly refresh cookie.
- `src/lib/permissions.ts` mirrors the backend role matrix to hide controls. It is not an authorization control; the API enforces every permission.
- Server and customer text is always rendered as text, never as HTML.
