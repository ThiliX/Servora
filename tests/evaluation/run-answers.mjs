#!/usr/bin/env node
// Answer evaluation: puts every labelled question to the assistant through the real API
// (POST /workspaces/{id}/assistant/test), which runs the whole path: the core API, the AI service's
// retrieval and evidence gate, the configured model, and the core API's own check of the output.
// It needs the two workspaces created by scripts/seed-demo.mjs.
//
//   node tests/evaluation/run-answers.mjs --seed .demo/seed-<run>.json [--delay-ms 4000] \
//     [--input-price 0.10 --output-price 0.40] [--label gemini]
//
// Writes tests/evaluation/results/answers-<label>-<date>.json with every question, the full
// response and the summary. With a real model this sends the demo documents and questions to the
// provider and uses its quota: run it only with demo data.
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";
import { score } from "./score.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, "..", "..");
const datasets = join(root, "services", "ai-service", "evaluation");

const { values: options } = parseArgs({
  options: {
    seed: { type: "string" },
    "delay-ms": { type: "string", default: "0" },
    "input-price": { type: "string" },
    "output-price": { type: "string" },
    "questions-per-conversation": { type: "string", default: "3" },
    label: { type: "string", default: "run" },
    limit: { type: "string" },
  },
});
if (!options.seed) throw new Error("Pass --seed .demo/seed-<run>.json (written by scripts/seed-demo.mjs)");
const seed = JSON.parse(readFileSync(options.seed, "utf8"));
const API = seed.api.replace(/\/$/, "");
const delay = Number(options["delay-ms"]);
const prices =
  options["input-price"] && options["output-price"]
    ? { input: Number(options["input-price"]), output: Number(options["output-price"]) }
    : null;

function readJsonl(file) {
  return readFileSync(join(datasets, file), "utf8")
    .split("\n")
    .filter((line) => line.trim())
    .map((line) => JSON.parse(line));
}

async function signIn(person) {
  const response = await fetch(`${API}/api/v1/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email: person.email, password: person.password }),
  });
  if (!response.ok) throw new Error(`Sign-in failed with ${response.status}`);
  return (await response.json()).access_token;
}

async function ask(token, workspaceId, question) {
  const started = performance.now();
  const response = await fetch(`${API}/api/v1/workspaces/${workspaceId}/assistant/test`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
    body: JSON.stringify({ question }),
  });
  const wall = Math.round(performance.now() - started);
  const body = await response.json();
  if (!response.ok) {
    throw new Error(`Question failed with ${response.status} ${body.error?.code ?? ""}: ${question}`);
  }
  return { body, wall };
}

const cases = [];
for (const workspace of seed.workspaces) {
  const key = workspace.company.toLowerCase();
  const token = await signIn(workspace.owner);
  const questions = [
    ...readJsonl(`${key}.jsonl`).map((row) => ({
      set: row.relevant.length > 0 ? "answerable" : "unanswerable",
      question: row.question,
      labels: row.relevant,
    })),
    ...readJsonl(`${key}-escalation.jsonl`).map((row) => ({
      set: "escalation",
      question: row.question,
      labels: [],
      why: row.why,
    })),
  ].slice(0, options.limit ? Number(options.limit) : undefined);

  for (const [index, item] of questions.entries()) {
    const { body, wall } = await ask(token, workspace.workspace_id, item.question);
    cases.push({ company: workspace.company, ...item, wall_ms: wall, response: body });
    process.stdout.write(
      `${workspace.company} ${String(index + 1).padStart(2)}/${questions.length} ${item.set.padEnd(12)} ${body.outcome}\n`,
    );
    if (delay > 0) await new Promise((done) => setTimeout(done, delay));
  }
}

const options_ = { prices, questionsPerConversation: Number(options["questions-per-conversation"]) };
const summary = {
  both: score(cases, options_),
  ...Object.fromEntries(
    seed.workspaces.map((workspace) => [
      workspace.company,
      score(cases.filter((item) => item.company === workspace.company), options_),
    ]),
  ),
};
const first = cases.find((item) => item.response.provider);
const report = {
  label: options.label,
  date: new Date().toISOString().slice(0, 10),
  provider: first?.response.provider ?? null,
  model: cases.find((item) => item.response.model)?.response.model ?? null,
  prompt_version: first?.response.prompt_version ?? null,
  evidence_threshold: first?.response.evidence_threshold ?? null,
  summary,
  cases: cases.map(({ response, ...rest }) => ({
    ...rest,
    outcome: response.outcome,
    needs_escalation: response.needs_escalation,
    escalation_reason: response.escalation_reason,
    answer: response.answer,
    citations: (response.citations ?? []).map((citation) => citation.filename),
    top_score: response.retrieval?.[0]?.score ?? null,
    latency_ms: response.latency_ms,
    input_tokens: response.input_tokens,
    output_tokens: response.output_tokens,
  })),
};

const out = join(here, "results", `answers-${options.label}-${report.date}.json`);
mkdirSync(dirname(out), { recursive: true });
writeFileSync(out, JSON.stringify(report, null, 2) + "\n");
console.log("");
console.log(JSON.stringify(summary.both, null, 2));
console.log(`\nWritten to ${out}`);
