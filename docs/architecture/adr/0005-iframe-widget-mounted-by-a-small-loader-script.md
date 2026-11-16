# ADR-005: Iframe widget mounted by a small loader script

- **Status:** Accepted
- **Date:** 2026-11-16

## Context

The customer chat widget runs on third-party websites whose CSS and JavaScript Servora does not control.

## Decision

The widget is a separate React build served by Servora and displayed in an iframe. Customers embed a small loader script (`servora-widget.js`) that creates the iframe. The widget only ever holds a short-lived, narrowly scoped session token.

## Consequences

- Styles and scripts are isolated in both directions.
- Host-page integration is one script tag.
- Loader and iframe communicate only through a small, origin-checked `postMessage` contract.

## Revisit when

Revisit if customers need deep visual integration that an iframe cannot provide.
