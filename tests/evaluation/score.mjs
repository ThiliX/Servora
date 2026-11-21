// Scores answer-evaluation results. Pure functions, unit tested in score.test.mjs.
//
// Each case is one question put to the assistant through the real API (POST /assistant/test):
//   { set: "answerable" | "unanswerable" | "escalation", question, labels: [{document, phrase}],
//     response: <playground response>, wall_ms }
//
// Two things can happen to a question: the assistant answers it (outcome ANSWERED, no escalation),
// or it hands the conversation to a person (needs_escalation true: no evidence, the model declined
// or asked for a colleague, or the core API rejected the output). Answerable questions should be
// answered; unanswerable and escalation questions should be handed off.

export function answered(response) {
  return response.outcome === "ANSWERED" && !response.needs_escalation;
}

export function handedOff(response) {
  return Boolean(response.needs_escalation);
}

function normalise(text) {
  return String(text ?? "")
    .toLowerCase()
    .replace(/\s+/g, " ")
    .trim();
}

/** Numbers in a label's phrase ("5 to 7 business days" → 5, 7): the facts an answer must keep. */
export function keyNumbers(phrase) {
  return [...String(phrase).matchAll(/\d+(?:[.,]\d+)?/g)].map((match) => match[0].replace(",", "."));
}

/** Whether every number in the labelled phrase appears in the answer. Null when the phrase has none. */
export function keyFactsPresent(answer, labels) {
  const numbers = labels.flatMap((label) => keyNumbers(label.phrase));
  if (numbers.length === 0) return null;
  const inAnswer = new Set(keyNumbers(answer));
  return numbers.every((number) => inAnswer.has(number));
}

/**
 * Citation accuracy for an answered question, at document level: every cited document is one the
 * label names, and at least one citation exists. The phrase check is weaker: the API returns only
 * the first 240 characters of a cited passage, so a phrase later in the passage is not visible.
 */
export function citationCheck(response, labels) {
  const documents = new Set(labels.map((label) => label.document));
  const cited = response.citations ?? [];
  return {
    has_citation: cited.length > 0,
    all_cited_documents_labelled: cited.length > 0 && cited.every((citation) => documents.has(citation.filename)),
    phrase_in_cited_snippet: cited.some((citation) =>
      labels.some((label) => citation.filename === label.document && normalise(citation.snippet).includes(normalise(label.phrase))),
    ),
  };
}

function rate(values) {
  if (values.length === 0) return null;
  return Math.round((values.filter(Boolean).length / values.length) * 1000) / 1000;
}

export function percentile(values, p) {
  if (values.length === 0) return null;
  const sorted = [...values].sort((a, b) => a - b);
  const index = Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1);
  return sorted[Math.max(0, index)];
}

function mean(values) {
  if (values.length === 0) return null;
  return Math.round((values.reduce((sum, value) => sum + value, 0) / values.length) * 10) / 10;
}

/**
 * @param cases scored questions
 * @param prices optional { input, output } in US dollars per million tokens
 * @param questionsPerConversation used only for the cost-per-conversation estimate
 */
export function score(cases, { prices = null, questionsPerConversation = 3 } = {}) {
  const bySet = (name) => cases.filter((item) => item.set === name);
  const answerable = bySet("answerable");
  const unanswerable = bySet("unanswerable");
  const escalation = bySet("escalation");

  const answeredAnswerable = answerable.filter((item) => answered(item.response));
  const citations = answeredAnswerable.map((item) => citationCheck(item.response, item.labels));
  const facts = answeredAnswerable
    .map((item) => keyFactsPresent(item.response.answer, item.labels))
    .filter((value) => value !== null);

  const handedOffAll = cases.filter((item) => handedOff(item.response));
  const modelCalls = cases.filter((item) => (item.response.input_tokens ?? 0) > 0);
  const inputTokens = cases.reduce((sum, item) => sum + (item.response.input_tokens ?? 0), 0);
  const outputTokens = cases.reduce((sum, item) => sum + (item.response.output_tokens ?? 0), 0);

  const outcomes = {};
  for (const item of cases) outcomes[item.response.outcome] = (outcomes[item.response.outcome] ?? 0) + 1;

  let cost = null;
  if (prices && cases.length > 0) {
    const total = (inputTokens * prices.input + outputTokens * prices.output) / 1_000_000;
    const perQuestion = total / cases.length;
    cost = {
      input_price_per_million_usd: prices.input,
      output_price_per_million_usd: prices.output,
      total_usd: Number(total.toFixed(6)),
      per_question_usd: Number(perQuestion.toFixed(6)),
      questions_per_conversation_assumed: questionsPerConversation,
      per_conversation_usd: Number((perQuestion * questionsPerConversation).toFixed(6)),
    };
  }

  return {
    questions: cases.length,
    answerable: {
      count: answerable.length,
      answered_rate: rate(answerable.map((item) => answered(item.response))),
      handed_off_rate: rate(answerable.map((item) => handedOff(item.response))),
      citation_present_rate: rate(citations.map((check) => check.has_citation)),
      citation_document_accuracy: rate(citations.map((check) => check.all_cited_documents_labelled)),
      phrase_in_cited_snippet_rate: rate(citations.map((check) => check.phrase_in_cited_snippet)),
      key_facts_checked: facts.length,
      key_facts_present_rate: rate(facts),
    },
    unanswerable: {
      count: unanswerable.length,
      correctly_not_answered_rate: rate(unanswerable.map((item) => !answered(item.response))),
      answered_anyway: unanswerable.filter((item) => answered(item.response)).map((item) => item.question),
    },
    escalation: {
      count: escalation.length,
      // Recall: of the questions that need a person, how many were handed to one.
      recall: rate(escalation.map((item) => handedOff(item.response))),
      // Precision: of all hand-offs, how many were for a question that should not be answered here.
      precision: rate(handedOffAll.map((item) => item.set !== "answerable")),
      missed: escalation.filter((item) => !handedOff(item.response)).map((item) => item.question),
      unnecessary: answerable.filter((item) => handedOff(item.response)).map((item) => item.question),
    },
    outcomes,
    latency_ms: {
      end_to_end_p50: percentile(cases.map((item) => item.wall_ms), 50),
      end_to_end_p95: percentile(cases.map((item) => item.wall_ms), 95),
      ai_service_p50: percentile(cases.map((item) => item.response.latency_ms), 50),
      ai_service_p95: percentile(cases.map((item) => item.response.latency_ms), 95),
      with_model_call_p50: percentile(modelCalls.map((item) => item.response.latency_ms), 50),
      with_model_call_p95: percentile(modelCalls.map((item) => item.response.latency_ms), 95),
    },
    tokens: {
      model_calls: modelCalls.length,
      input_total: inputTokens,
      output_total: outputTokens,
      input_per_model_call: mean(modelCalls.map((item) => item.response.input_tokens)),
      output_per_model_call: mean(modelCalls.map((item) => item.response.output_tokens)),
    },
    cost,
  };
}
