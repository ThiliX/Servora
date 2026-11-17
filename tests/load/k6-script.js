// Load test of the core API's non-AI endpoints (handbook section 5: P95 < 500 ms under a stated
// local load). Run through run.mjs, which starts the API, seeds a workspace and passes its ids.
import http from "k6/http";
import { check, sleep } from "k6";

const BASE = __ENV.BASE_URL;
const TOKEN = __ENV.TOKEN;
const WORKSPACE = __ENV.WORKSPACE;
const WIDGET_KEY = __ENV.WIDGET_KEY;
const SITE = __ENV.SITE;
const CONVERSATIONS = JSON.parse(__ENV.CONVERSATIONS);
const VUS = Number(__ENV.VUS || 20);

export const options = {
  scenarios: {
    dashboard: {
      executor: "ramping-vus",
      startVUs: 1,
      stages: [
        { duration: "15s", target: VUS }, // warm-up, not excluded from results
        { duration: "60s", target: VUS },
        { duration: "5s", target: 0 },
      ],
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(95)<500"],
    // One per endpoint, so the summary reports each of them.
    ...Object.fromEntries(
      ["tickets", "messages", "notifications", "conversations", "workspace", "workspaces", "documents", "analytics",
        "usage", "audit", "widget_config", "add_note"].map((name) => [`http_req_duration{name:${name}}`, ["p(95)<500"]]),
    ),
  },
  summaryTrendStats: ["avg", "med", "p(90)", "p(95)", "p(99)", "max"],
};

const auth = { headers: { Authorization: `Bearer ${TOKEN}` } };
const ws = `${BASE}/api/v1/workspaces/${WORKSPACE}`;

// Weighted towards what an agent's dashboard does most: queue, conversation, notifications.
const requests = [
  [3, "tickets", () => http.get(`${ws}/tickets?view=ALL&state=ACTIVE`, { ...auth, tags: { name: "tickets" } })],
  [3, "messages", () => http.get(`${ws}/conversations/${pick(CONVERSATIONS)}/messages?page=0&size=50`, { ...auth, tags: { name: "messages" } })],
  [3, "notifications", () => http.get(`${ws}/notifications`, { ...auth, tags: { name: "notifications" } })],
  [2, "conversations", () => http.get(`${ws}/conversations?page=0&size=20`, { ...auth, tags: { name: "conversations" } })],
  [2, "workspace", () => http.get(ws, { ...auth, tags: { name: "workspace" } })],
  [1, "workspaces", () => http.get(`${BASE}/api/v1/workspaces`, { ...auth, tags: { name: "workspaces" } })],
  [1, "documents", () => http.get(`${ws}/knowledge/documents`, { ...auth, tags: { name: "documents" } })],
  [1, "analytics", () => http.get(`${ws}/analytics?days=30`, { ...auth, tags: { name: "analytics" } })],
  [1, "usage", () => http.get(`${ws}/usage`, { ...auth, tags: { name: "usage" } })],
  [1, "audit", () => http.get(`${ws}/audit-events?page=0&size=50`, { ...auth, tags: { name: "audit" } })],
  [2, "widget_config", () =>
    http.get(`${BASE}/api/v1/public/widgets/${WIDGET_KEY}/config`, { headers: { Origin: SITE }, tags: { name: "widget_config" } })],
  [1, "add_note", () =>
    http.post(`${ws}/conversations/${pick(CONVERSATIONS)}/notes`, JSON.stringify({ body: "Load test note" }), {
      headers: { ...auth.headers, "Content-Type": "application/json" },
      tags: { name: "add_note" },
    })],
];
const total = requests.reduce((sum, [weight]) => sum + weight, 0);

function pick(list) {
  return list[Math.floor(Math.random() * list.length)];
}

export default function () {
  let roll = Math.random() * total;
  for (const [weight, name, send] of requests) {
    roll -= weight;
    if (roll < 0) {
      const response = send();
      const ok = check(response, { [`${name} ok`]: (r) => r.status === 200 || r.status === 201 });
      if (!ok) {
        console.warn(`${name} failed: status=${response.status} error=${response.error_code} ${response.error}`);
      }
      break;
    }
  }
  sleep(0.2);
}
