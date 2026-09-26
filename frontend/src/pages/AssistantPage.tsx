import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useSearchParams } from 'react-router-dom';
import { chatWithAssistant } from '../api/client';
import ErrorBanner from '../components/ErrorBanner';

interface Exchange {
  question: string;
  answer: string | null; // null while the answer is still on its way
}

const SUGGESTIONS = [
  'Why might this loan be considered high risk?',
  'What should I check first before escalating this case?',
  'Summarize the notes on this loan.',
];

/**
 * A single-turn Q&A panel over an LLM reachable via the LiteLLM proxy (see docker-compose.yml) --
 * each question is independent server-side (no persisted conversation), the visible back-and-forth
 * below is kept in this component's own state only. Optionally grounded in one loan's known
 * features/score/case/notes when a Loan ID is given (see assistant.AssistantService on the
 * backend for exactly what context that assembles).
 */
export default function AssistantPage() {
  const [searchParams] = useSearchParams();
  const [loanId, setLoanId] = useState(searchParams.get('loanId') ?? '');
  const [question, setQuestion] = useState('');
  const [exchanges, setExchanges] = useState<Exchange[]>([]);
  const [error, setError] = useState<unknown>(null);
  const [asking, setAsking] = useState(false);
  const endRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
  }, [exchanges]);

  const ask = async (text: string) => {
    const askedQuestion = text.trim();
    if (!askedQuestion || asking) return;
    setAsking(true);
    setError(null);
    setQuestion('');
    setExchanges((prev) => [...prev, { question: askedQuestion, answer: null }]);
    try {
      const result = await chatWithAssistant({ loanId: loanId.trim() || undefined, question: askedQuestion });
      setExchanges((prev) => prev.map((x, i) => (i === prev.length - 1 ? { ...x, answer: result.answer } : x)));
    } catch (err) {
      setError(err);
      setExchanges((prev) => prev.slice(0, -1));
      setQuestion(askedQuestion);
    } finally {
      setAsking(false);
    }
  };

  const submit = (e: FormEvent) => {
    e.preventDefault();
    void ask(question);
  };

  return (
    <div>
      <div className="page-head-row">
        <div>
          <h2>AI Assistant</h2>
          <p className="page-subtitle">
            Ask about a specific loan or a general question. Each question is independent: the assistant does not remember earlier
            turns yet.
          </p>
        </div>
      </div>

      <div className="chat">
        <div className="chat-context">
          <label htmlFor="assistantLoanId">Grounded in loan</label>
          <input id="assistantLoanId" value={loanId} onChange={(e) => setLoanId(e.target.value)} placeholder="Loan ID (optional), e.g. L-10293" />
          <span className="chat-hint">
            {loanId.trim() ? 'Answers use this loan’s features, score, case status and notes.' : 'No loan selected: general questions only.'}
          </span>
        </div>

        <div className="chat-thread" aria-live="polite">
          {exchanges.length === 0 && (
            <div className="chat-empty">
              <p>Try one of these, or ask your own.</p>
              <div className="chips">
                {SUGGESTIONS.map((s) => (
                  <button key={s} type="button" className="chip" onClick={() => void ask(s)}>{s}</button>
                ))}
              </div>
            </div>
          )}
          {exchanges.map((ex, i) => (
            <div key={i}>
              <div className="bubble me">{ex.question}</div>
              {ex.answer === null ? (
                <div className="bubble bot typing" aria-label="Assistant is thinking"><span /><span /><span /></div>
              ) : (
                <div className="bubble bot">{ex.answer}</div>
              )}
            </div>
          ))}
          <div ref={endRef} />
        </div>

        <ErrorBanner error={error} />
        <form className="chat-input" onSubmit={submit}>
          <input
            id="assistantQuestion"
            aria-label="Question"
            value={question}
            onChange={(e) => setQuestion(e.target.value)}
            placeholder="Ask a question…"
            autoFocus
          />
          <button type="submit" disabled={asking || !question.trim()}>{asking ? 'Asking…' : 'Send'}</button>
        </form>
      </div>
    </div>
  );
}
