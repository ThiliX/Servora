# AI evaluation report

The first measurement of answers from a real model, end to end. It complements the [retrieval baseline](evaluation-baseline.md), which measured retrieval and the evidence gate without a model. Treat the figures as a first reading on a small, purpose-written dataset, not as a quality claim.

## Setup

| Item | Value |
|---|---|
| Date | 2026-11-21 |
| Model | Google `gemini-3.5-flash-lite`, free tier, through the AI service's Gemini provider |
| Prompt version | `2026-11-17.1` |
| Assistant settings | Friendly tone, English, confidence policy BALANCED (evidence threshold 0.35), top K = 5 |
| Embedding model | `sentence-transformers/all-MiniLM-L6-v2`, revision `1110a243…`, 384 dimensions |
| Corpus | The same 6 demo documents as the baseline (`services/ai-service/evaluation/fixtures`), 3 per tenant, uploaded through the API and indexed by the worker |
| Questions | 50: per tenant, 15 answerable, 5 unanswerable (`techstore.jsonl`, `hotelpro.jsonl`, unchanged from the baseline) and 5 that need a person (`*-escalation.jsonl`, new: asks for a person, upset customer, an action the assistant cannot take, an order or booking change, a safety or injury report) |
| Path measured | `POST /api/v1/workspaces/{id}/assistant/test` on the production profile: core API → AI service (retrieval, gate, model call, output checks) → core API answer verification. Everything a widget reply goes through except the core API's own escalation rules for widget conversations |
| Environment | Production Compose profile on a Windows 11 development machine (Docker Desktop), one run, 5 seconds between questions |
| How to repeat | [Repeating the evaluation](#repeating-the-evaluation) |
| Raw results | [`tests/evaluation/results/answers-gemini-2026-11-21.json`](../../tests/evaluation/results/answers-gemini-2026-11-21.json): every question, outcome, answer, citation, score, latency and token count |

## Definitions

Every question ends in one of two ways: the assistant **answers** (outcome `ANSWERED`, no escalation), or it **hands the conversation to a person** (any other outcome, or `ESCALATED`: no evidence above the threshold, the model declined or asked for a colleague, or a check rejected the output).

- **Answered rate:** answerable questions answered.
- **Citation accuracy:** answered questions whose every citation is the labelled document. Checked at document level; each corpus has only three documents, so this is a coarse check.
- **Key facts present:** for labels that contain a number (a price, a time, a period), whether every such number appears in the answer. Automatic and strict: it flags a correct answer that leaves out a number, and it cannot catch a wrong claim that has no number.
- **Correctly not answered:** unanswerable questions that were handed to a person instead of answered.
- **Escalation recall:** questions that need a person and were handed to one.
- **Escalation precision:** hand-offs that were for an unanswerable or escalation question, rather than an answerable one.
- **Groundedness:** whether every claim in a reply written by the model is supported by the cited document. Reviewed reply by reply; see below for who reviewed it.

## Results

| Metric | TechStore | HotelPro | Both |
|---|---|---|---|
| Answered rate (answerable) | 0.93 (14 of 15) | 0.80 (12 of 15) | **0.87** (26 of 30) |
| Citation accuracy (answered) | 1.00 | 1.00 | **1.00** |
| Key facts present | 1.00 (9 checked) | 0.86 (7 checked) | **0.94** (15 of 16) |
| Correctly not answered (unanswerable) | 1.00 (5 of 5) | 1.00 (5 of 5) | **1.00** (10 of 10) |
| Escalation recall | 1.00 (5 of 5) | 1.00 (5 of 5) | **1.00** (10 of 10) |
| Escalation precision | 0.91 | 0.77 | **0.83** (20 of 24 hand-offs) |

No answer was given to an unanswerable question, and no answer cited a document from the wrong place or from the other tenant.

### Groundedness

All 34 replies written by the model (26 answers, and the 8 hand-off messages it wrote itself) were checked claim by claim against the source documents. 33 contain only claims the source supports, including every price, time, limit and condition. One adds a detail the source does not state: asked whether the pool is open late, it said "open until 9 pm every day"; the source says "7 am to 9 pm", without "every day". No reply contradicted a source.

**Who reviewed:** this review was done by the AI coding assistant that built this phase (Claude), not by a person. Groundedness is normally judged by people; the raw results file has every reply next to its question and label so a person can repeat it. Until then, read the groundedness figure as a careful automated reading, not a human judgement.

### Hand-offs that were not needed

Four answerable questions went to a person:

| Question | Why |
|---|---|
| "What happens if I just do not turn up?" (HotelPro) | Top similarity 0.24, below the threshold. The source says "no-show"; the question shares no words with it |
| "Can I stay in the room a bit longer on my last day?" (HotelPro) | 0.24. The source says "late check-out" |
| "I left my charger in the room. Do you still have it?" (HotelPro) | 0.27. The source says "lost property" |
| "I dropped my tablet and the display broke. Is that covered?" (TechStore) | Answered correctly (accidental damage is not covered) and also asked a colleague to follow up. A reasonable choice for a customer with a broken device |

The first three are the vocabulary gap the baseline already found: short, everyday wording far from the document's terms. They fail safely: the customer is handed to a person.

### How requests for a person were handled

All ten escalation questions were handed to a person, in two ways:

- **Before the model.** Five ("Can I just talk to a real person please?", the two upset customers, "I want to speak to the manager", the injury report) matched no document above the threshold, so the model was never asked. The customer gets the assistant's fallback message, for example "I'm not sure about that one. Let me get a colleague to help you." That hands over correctly, but the wording does not fit a customer who asked for a person or reported an injury. In widget conversations the core API's own rules escalate explicit requests for a person and risky terms before the AI is asked, so this mainly affects the playground.
- **By the model.** The other five (refund, address change, booking cancellation, name change, fire) reached the model, which declined to act and said a colleague would follow up, as the prompt asks.

## Latency and cost

| | TechStore | HotelPro | Both |
|---|---|---|---|
| End to end, P50 / P95 (ms) | 1,675 / 4,710 | 1,414 / 1,924 | **1,485 / 4,436** |
| Questions with a model call, AI service P50 / P95 (ms) | 1,699 / 6,066 | 1,472 / 3,682 | **1,627 / 4,666** |
| Model calls | 18 of 25 | 16 of 25 | 34 of 50 |
| Tokens per model call, input / output | 640 / 79 | 604 / 87 | **623 / 83** |

Questions without evidence take about 20 ms in the AI service and 70 ms end to end, without a model call. Latency was measured on a free-tier key from a home connection; a paid tier and a server location will differ.

**Cost.** The run used the free tier, so it cost nothing. At Google's published standard paid-tier price for this model, $0.30 per million input tokens and $2.50 per million output tokens ([pricing page](https://ai.google.dev/gemini-api/docs/pricing), read on 2026-11-21), the 50 questions would cost **$0.013**: about **$0.00027 per question** and **$0.0008 per conversation**, assuming three questions per conversation. That assumption is ours; no conversation-length data exists yet. Embeddings run locally and cost nothing per question.

## Compared with the fake model

The same harness with the built-in fake model (`answers-fake-2026-11-21.json`) answered two unanswerable HotelPro questions and missed five escalations, because the fake model only quotes the best passage and never declines. The real model's declining is what turns the evidence gate's overlaps, measured in the baseline, into correct hand-offs: in this run every unanswerable question that passed the gate (two of ten) was declined by the model.

## What this shows

- With this corpus and model, the assistant answered 87 percent of answerable questions, always from the right document, and never answered a question its documents do not cover.
- Every hand-off it should have made, it made. One in six hand-offs was unnecessary, and three of those four come from the gate rejecting everyday wording.
- Cost is far below a cent per conversation at the paid price, and most of the time is the model call.

## Limits

- 50 questions over 6 short documents, written by one person for this purpose. A difference of one question moves a per-tenant rate by 0.07 or more.
- One run, one model, one prompt version, one threshold (BALANCED). STRICT was not measured with the model.
- Groundedness was reviewed by an AI assistant, not by a person.
- Citation accuracy is checked per document, not per passage.
- English only. No prompt-injection questions in this set; injection is covered by the AI service's unit tests with a stub model, not with this model.
- The playground path does not include the core API's escalation rules for widget conversations, or conversation history (which the AI does not use yet).

## Repeating the evaluation

```bash
# 1. The production profile with your provider in .env, mail links on (see docs/demo.md)
SERVORA_PROD_MAIL_LOG_LINKS=true docker compose -p servora-prod -f docker-compose.yml -f compose.prod.yml up -d --build --wait
# 2. Fresh demo workspaces
node scripts/seed-demo.mjs --compose-project servora-prod --api http://localhost:8000 --run eval1
# 3. The questions, with the provider's prices for the cost estimate
node tests/evaluation/run-answers.mjs --seed .demo/seed-eval1.json --label gemini --delay-ms 5000 \
  --input-price 0.30 --output-price 2.50
```

Each workspace may run 60 playground questions per hour; one run uses 25. With a real model the questions and retrieved demo passages are sent to the provider and count against its quota.
