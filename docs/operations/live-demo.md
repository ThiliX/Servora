# Running the public demo

A public, clickable demo served from one machine through [Tailscale Funnel](https://tailscale.com/kb/1223/funnel): the [production profile](production.md), plus two made-up customer websites, shared demo accounts with sample data, tighter limits, and a nightly reset. It needs no server, domain or certificate, but it is only online while the machine and Docker are running.

**Demo data only.** Visitors share the accounts and see each other's conversations, and their questions go to the model provider. Never put real customers' data in this stack.

## What visitors get

| Address | What it is |
|---|---|
| `https://<machine>.<tailnet>.ts.net` | The dashboard. The sign-in page offers one-click sign-in as the TechStore or HotelPro owner or agent |
| `https://<machine>.<tailnet>.ts.net:10000/techstore/` and `/hotelpro/` | The made-up customer websites with the chat widget. Links are on the sign-in page and in the dashboard's banner |
| `https://<machine>.<tailnet>.ts.net:8443` | The widget host, used by the websites |

A banner in the dashboard says the demo is public and resets nightly. Demo mode (`SERVORA_DEMO_MODE=true`, [`DemoMode`](../../services/core-api/src/main/java/com/servora/demo/DemoMode.java)) refuses anything that would spoil the demo for the next visitor: creating accounts or workspaces, renaming a workspace, changing the assistant's settings or the widget, managing the team, and deleting documents or conversations. The inbox, take-over and replies, the playground, uploads, analytics and the audit log work as usual.

## Limits

Set in [`compose.demo.yml`](../../compose.demo.yml) to keep the model provider's free quota for many visitors. When the quota still runs out, customers are handed to a person rather than shown an error.

| Limit | Demo | Normal |
|---|---|---|
| AI replies per workspace per day | 150 (`SERVORA_DEMO_AI_REPLIES_PER_DAY`) | 2,000 |
| Playground questions per workspace per hour | 20 (`SERVORA_DEMO_PLAYGROUND_PER_HOUR`) | 60 |
| Chat sessions per visitor per hour | 10 | 30 |
| Chat messages per visitor per minute | 6 | 30 |
| Uploads per workspace per hour, and size | 10, 1 MB | 60, 10 MB |
| Failed sign-ins before an account is locked for everyone | No account-wide lock | 20 |

Per-visitor limits use the visitor's real address: Funnel adds it to `X-Forwarded-For`, and the gateways trust that header only from Docker's own network ([`trusted-proxy.conf`](../../infrastructure/demo/trusted-proxy.conf)).

## Set up (once)

1. Install Docker Desktop and Node.js, and fill in `.env` as for the [production profile](production.md). For real answers, set `SERVORA_LLM_PROVIDER` and the provider's key there.
2. Install [Tailscale](https://tailscale.com/download) and sign in with a free account.
3. In the Tailscale admin console, turn on **HTTPS certificates** (DNS page) and allow **Funnel** for this machine (Access controls). The first `tailscale funnel` command below prints a link that does this if it is missing.

## Start

```bash
node scripts/live-demo.mjs start
```

The first start builds the images and creates the demo data (a few minutes). It prints the three addresses and the Funnel commands. Run those once; Funnel remembers them after a restart:

```bash
tailscale funnel --bg --https=443 http://127.0.0.1:8000
tailscale funnel --bg --https=8443 http://127.0.0.1:8001
tailscale funnel --bg --https=10000 http://127.0.0.1:8002
```

The demo uses its own compose project (`servora-demo`) and ports 8000 to 8002, so stop another production-profile stack on those ports first. The development stack is not affected.

## Reset every night

`reset` deletes the demo's containers and data, starts again with demo mode off, creates fresh demo data with new passwords, and switches demo mode back on. Visitors are signed out. To run it every night at 04:00 with Windows Task Scheduler (from PowerShell, in the repository):

```powershell
schtasks /Create /TN "Servora demo reset" /SC DAILY /ST 04:00 /TR "`"$((Get-Command node).Source)`" `"$PWD\scripts\live-demo.mjs`" reset"
```

## Stop

```bash
tailscale funnel reset              # takes the demo off the internet
node scripts/live-demo.mjs stop     # stops the containers, keeping the data
```

Remove the nightly task with `schtasks /Delete /TN "Servora demo reset"`.

## Keep in mind

- The machine must stay awake. Set Windows to never sleep while plugged in, or the demo goes offline.
- Visitors can upload documents and reply to other visitors' chats as an agent. The nightly reset clears both.
- Questions, retrieved passages and replies are sent to the configured model provider. On a free tier the provider may use them to improve its models.
- Funnel has bandwidth limits and no uptime guarantee. For an always-on demo, run the same files on a server behind a TLS proxy instead.
