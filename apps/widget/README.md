# Servora chat widget

The customer chat widget. Design: [ADR-005](../../docs/architecture/adr/0005-iframe-widget-mounted-by-a-small-loader-script.md), [ADR-014](../../docs/architecture/adr/0014-server-sent-events-from-the-core-api-for-customer-streaming.md), [ADR-017](../../docs/architecture/adr/0017-public-widget-access-and-session-tokens.md).

Two parts, built from one Vite project with no runtime dependencies:

| Part | Source | Built as | Runs on |
|---|---|---|---|
| Loader | `src/loader.ts` | `servora-widget.js` | The customer's website |
| Chat | `widget.html`, `src/widget.ts`, `src/chat.ts` | `widget.html` and assets | An iframe on Servora's origin |

The loader fetches the widget's settings (the browser sends the website's `Origin`, which the API checks), mounts the iframe, and starts a session when the iframe asks for one. The session token is passed to the iframe with `postMessage` and kept in the website's `sessionStorage` for the visit. All text is inserted with `textContent`; a lint rule forbids `innerHTML`.

Embed snippet (the dashboard's Widget page shows it with the real key):

```html
<script type="module" src="https://WIDGET-HOST/servora-widget.js" data-widget-key="wk_..." data-api-base="https://API-HOST" async></script>
```

`data-api-base` is optional and defaults to the widget's own origin.

## Develop

```bash
npm install
npm run dev
```

The chat is served on `http://localhost:5174`. The core API must allow it: `SERVORA_WIDGET_APP_ORIGIN=http://localhost:5174` (the default).

## Demo websites

Two static demo websites run on their own origins:

```bash
npm run demo
```

- TechStore: `http://localhost:5181/?key=<TechStore widget key>`
- HotelPro: `http://localhost:5182/?key=<HotelPro widget key>`

For each, add the site's address to the workspace's allowed websites on the Widget page, and upload the matching files from `services/ai-service/evaluation/fixtures` on the Knowledge page.

## Test

```bash
npm run lint && npm run typecheck && npm test && npm run build
```

Unit tests cover the chat UI, the SSE parser, the message protocol and the loader. There is no browser end-to-end suite yet; the demo websites were checked by hand.
