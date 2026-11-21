import assert from "node:assert/strict";
import { test } from "node:test";
import { citationCheck, keyFactsPresent, keyNumbers, percentile, score } from "./score.mjs";

const label = { document: "returns-and-refunds.md", phrase: "5 to 7 business days" };

function response(outcome, { escalate = outcome !== "ANSWERED", answer = "", citations = [], tokens = [0, 0], latency = 10 } = {}) {
  return {
    outcome,
    answer,
    needs_escalation: escalate,
    citations,
    latency_ms: latency,
    input_tokens: tokens[0],
    output_tokens: tokens[1],
  };
}

test("key numbers are read from the labelled phrase", () => {
  assert.deepEqual(keyNumbers("5 to 7 business days"), ["5", "7"]);
  assert.deepEqual(keyNumbers("15 percent restocking fee"), ["15"]);
  assert.deepEqual(keyNumbers("free of charge"), []);
});

test("key facts must all appear in the answer", () => {
  assert.equal(keyFactsPresent("Refunds arrive in 5 to 7 business days.", [label]), true);
  assert.equal(keyFactsPresent("Refunds arrive within a week.", [label]), false);
  assert.equal(keyFactsPresent("Anything", [{ document: "a.md", phrase: "free of charge" }]), null);
});

test("citations are accurate only when every cited document is a labelled one", () => {
  const good = citationCheck(
    response("ANSWERED", { citations: [{ filename: "returns-and-refunds.md", snippet: "Refunds take 5 to 7 business days." }] }),
    [label],
  );
  assert.deepEqual(good, { has_citation: true, all_cited_documents_labelled: true, phrase_in_cited_snippet: true });

  const mixed = citationCheck(
    response("ANSWERED", {
      citations: [
        { filename: "returns-and-refunds.md", snippet: "..." },
        { filename: "shipping.md", snippet: "..." },
      ],
    }),
    [label],
  );
  assert.equal(mixed.all_cited_documents_labelled, false);
  assert.equal(citationCheck(response("ANSWERED"), [label]).has_citation, false);
});

test("percentiles use the nearest rank", () => {
  assert.equal(percentile([5, 1, 3, 2, 4], 50), 3);
  assert.equal(percentile([1, 2, 3, 4, 5, 6, 7, 8, 9, 10], 95), 10);
  assert.equal(percentile([], 50), null);
});

test("the summary separates answering, abstaining and escalating", () => {
  const cited = [{ filename: "returns-and-refunds.md", snippet: "5 to 7 business days" }];
  const cases = [
    { set: "answerable", question: "a1", labels: [label], wall_ms: 100, response: response("ANSWERED", { answer: "5 to 7 business days", citations: cited, tokens: [1000, 100] }) },
    { set: "answerable", question: "a2", labels: [label], wall_ms: 200, response: response("NO_EVIDENCE") },
    { set: "unanswerable", question: "u1", labels: [], wall_ms: 50, response: response("NO_EVIDENCE") },
    { set: "unanswerable", question: "u2", labels: [], wall_ms: 300, response: response("ANSWERED", { citations: cited, tokens: [800, 50] }) },
    { set: "escalation", question: "e1", labels: [], wall_ms: 400, response: response("ESCALATED", { tokens: [900, 60] }) },
    { set: "escalation", question: "e2", labels: [], wall_ms: 500, response: response("ANSWERED", { citations: cited, tokens: [700, 40] }) },
  ];

  const result = score(cases, { prices: { input: 1, output: 4 }, questionsPerConversation: 3 });

  assert.equal(result.answerable.answered_rate, 0.5);
  assert.equal(result.answerable.citation_document_accuracy, 1);
  assert.equal(result.answerable.key_facts_present_rate, 1);
  assert.equal(result.unanswerable.correctly_not_answered_rate, 0.5);
  assert.deepEqual(result.unanswerable.answered_anyway, ["u2"]);
  assert.equal(result.escalation.recall, 0.5);
  // Hand-offs: a2 (unnecessary), u1, e1. Two of three were for questions that needed a person.
  assert.equal(result.escalation.precision, 0.667);
  assert.deepEqual(result.escalation.missed, ["e2"]);
  assert.deepEqual(result.escalation.unnecessary, ["a2"]);
  assert.equal(result.tokens.model_calls, 4);
  assert.equal(result.tokens.input_total, 3400);
  // (3400 * 1 + 250 * 4) / 1e6 = 0.0044 over 6 questions.
  assert.equal(result.cost.total_usd, 0.0044);
  assert.equal(result.cost.per_conversation_usd, 0.0022);
  assert.deepEqual(result.outcomes, { ANSWERED: 3, NO_EVIDENCE: 2, ESCALATED: 1 });
});
