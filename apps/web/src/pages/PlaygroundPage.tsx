import { useState, type FormEvent } from "react";
import { Button, Card, FormError, Notice } from "../components/ui";
import { api, type ApiError } from "../lib/api";
import { canConfigureAssistant } from "../lib/permissions";
import type { AnswerOutcome, PlaygroundResult } from "../lib/types";
import { toApiError } from "../lib/useAsync";
import { useWorkspace } from "./WorkspaceLayout";

const MAX_QUESTION = 2000;

const OUTCOME_LABELS: Record<AnswerOutcome, string> = {
  ANSWERED: "Answered",
  ESCALATED: "Handed to a person",
  NO_EVIDENCE: "No answer found",
  PROVIDER_UNAVAILABLE: "AI model unavailable",
  INVALID_OUTPUT: "Answer rejected",
  INVALID_CITATION: "Answer rejected",
  UNGROUNDED: "Answer rejected",
  REFUSED: "AI model declined",
  SERVICE_UNAVAILABLE: "AI service unavailable",
};

const OUTCOME_EXPLANATIONS: Record<AnswerOutcome, string> = {
  ANSWERED: "The assistant answered from your documents. Check the sources below.",
  ESCALATED: "The assistant replied but asked for a colleague to follow up.",
  NO_EVIDENCE:
    "Nothing in your knowledge base matched closely enough, so the assistant gave your fallback message instead of guessing.",
  PROVIDER_UNAVAILABLE: "The AI model could not be reached, so the customer would see your fallback message.",
  INVALID_OUTPUT: "The AI model gave an unusable reply, so the customer would see your fallback message.",
  INVALID_CITATION:
    "The AI model's reply could not be traced to your documents, so the customer would see your fallback message.",
  UNGROUNDED: "The AI model answered without a source, so the customer would see your fallback message.",
  REFUSED: "The AI model declined to answer, so the customer would see your fallback message.",
  SERVICE_UNAVAILABLE: "The AI service could not be reached, so the customer would see your fallback message.",
};

/** Similarity as a whole percentage, for people who do not think in cosine scores. */
export function formatScore(score: number): string {
  return `${Math.round(Math.max(0, Math.min(1, score)) * 100)}%`;
}

function Result({ result }: { result: PlaygroundResult }) {
  const answered = result.outcome === "ANSWERED";
  return (
    <>
      <Card title="Assistant's reply">
        <div className="space-y-3">
          <p>
            <span
              className={`rounded-full px-2 py-0.5 text-xs font-medium ${
                answered ? "bg-emerald-100 text-emerald-800" : "bg-amber-100 text-amber-800"
              }`}
            >
              {OUTCOME_LABELS[result.outcome] ?? result.outcome}
            </span>
          </p>
          <p className="whitespace-pre-wrap rounded-md bg-slate-50 p-3 text-sm">{result.answer}</p>
          <p className="text-sm text-slate-600">{OUTCOME_EXPLANATIONS[result.outcome] ?? ""}</p>
          {result.provider === "fake" && result.model !== null && (
            <Notice tone="info">
              This reply came from the built-in test model, which only quotes the best matching source. Connect a real AI
              model to see real answers.
            </Notice>
          )}
        </div>
      </Card>

      <Card title="Sources cited">
        {result.citations.length === 0 ? (
          <p className="text-sm text-slate-500">No sources were cited.</p>
        ) : (
          <ul className="space-y-3">
            {result.citations.map((citation) => (
              <li key={citation.chunk_id} className="text-sm">
                <p className="font-medium">
                  {citation.filename}
                  {citation.page_number !== null && (
                    <span className="ml-2 text-xs text-slate-500">page {citation.page_number}</span>
                  )}
                  {citation.heading && <span className="ml-2 text-xs text-slate-500">{citation.heading}</span>}
                </p>
                <p className="mt-1 text-slate-600">{citation.snippet}</p>
              </li>
            ))}
          </ul>
        )}
      </Card>

      <Card title="What was found in your knowledge base">
        {result.retrieval.length === 0 ? (
          <p className="text-sm text-slate-500">No indexed documents matched. Upload documents on the Knowledge page.</p>
        ) : (
          <>
            <table className="w-full text-left text-sm">
              <thead>
                <tr className="text-xs text-slate-500">
                  <th scope="col" className="py-1 font-medium">
                    Document
                  </th>
                  <th scope="col" className="py-1 font-medium">
                    Match
                  </th>
                  <th scope="col" className="py-1 font-medium">
                    Given to the assistant
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-200">
                {result.retrieval.map((hit) => (
                  <tr key={hit.chunk_id}>
                    <td className="py-1.5">{hit.filename}</td>
                    <td className="py-1.5 tabular-nums">{formatScore(hit.score)}</td>
                    <td className="py-1.5">{hit.used ? "Yes" : "No, too weak"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p className="mt-2 text-xs text-slate-500">
              Passages need a match of at least {formatScore(result.evidence_threshold)} to be used.
            </p>
          </>
        )}
        <p className="mt-3 text-xs text-slate-500">
          Model: {result.model ?? "not called"} · {result.latency_ms} ms · {result.input_tokens + result.output_tokens}{" "}
          tokens
        </p>
      </Card>
    </>
  );
}

export function PlaygroundPage() {
  const { workspace } = useWorkspace();
  const [question, setQuestion] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [result, setResult] = useState<PlaygroundResult | null>(null);

  if (!canConfigureAssistant(workspace.role)) {
    return <Notice tone="error">You do not have permission to test the assistant.</Notice>;
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    setResult(null);
    try {
      setResult(
        await api<PlaygroundResult>(`/workspaces/${workspace.id}/assistant/test`, {
          method: "POST",
          body: { question: question.trim() },
        }),
      );
    } catch (caught) {
      setError(toApiError(caught));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <>
      <h1 className="text-xl font-bold">Test your assistant</h1>
      <Card>
        <form onSubmit={submit} className="space-y-3" noValidate>
          <FormError error={error} />
          <label htmlFor="playground-question" className="block text-sm font-medium text-slate-700">
            Ask a question as a customer would
          </label>
          <textarea
            id="playground-question"
            rows={3}
            maxLength={MAX_QUESTION}
            value={question}
            onChange={(e) => setQuestion(e.target.value)}
            placeholder="What is your refund policy?"
            aria-describedby="playground-hint"
            className="block w-full rounded-md border border-slate-300 bg-white px-3 py-2 text-sm"
          />
          <p id="playground-hint" className="text-xs text-slate-500">
            Only you see this. The assistant answers from the documents on the Knowledge page.
          </p>
          <Button type="submit" disabled={submitting || !question.trim()}>
            {submitting ? "Thinking…" : "Ask"}
          </Button>
        </form>
      </Card>
      {result && <Result result={result} />}
    </>
  );
}
