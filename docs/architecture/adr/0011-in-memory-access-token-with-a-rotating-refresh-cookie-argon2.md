# ADR-011: In-memory access token with a rotating refresh cookie; Argon2id password hashing

- **Status:** Accepted (recommended in M0)
- **Date:** 2026-11-16

## Context

Dashboard sessions must survive page reloads without putting long-lived bearer tokens where script injection can read them.

## Decision

The dashboard holds a short-lived access JWT in memory only. A refresh token is issued in an `HttpOnly`, `Secure`, `SameSite` cookie scoped to the refresh path, stored hashed in the database, rotated on every use, and revoked as a family when reuse is detected. Passwords are hashed with Argon2id through Spring Security's password encoder.

## Consequences

- Nothing long-lived is stored in `localStorage`.
- The refresh endpoint needs CSRF consideration because it is cookie-authenticated.
- Logout and password change revoke refresh tokens server-side.

## Revisit when

Revisit if single sign-on or a third-party identity provider is introduced.
