# Servora dashboard

React, TypeScript, Vite and Tailwind CSS dashboard for workspace owners, admins, supervisors and agents.

Implemented: registration, email verification, sign-in, workspace list and creation, workspace switcher, team and invitations, workspace settings (M1); assistant configuration, widget settings and knowledge base (M2).

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
