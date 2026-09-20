import { useState, type FormEvent } from 'react';
import { useSearchParams } from 'react-router-dom';
import { chatWithAssistant } from '../api/client';
import ErrorBanner from '../components/ErrorBanner';

interface Exchange {
  question: string;
  answer: string;
}

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

  const ask = async (e: FormEvent) => {
    e.preventDefault();
    if (!question.trim()) return;
    setAsking(true);
    setError(null);
    const askedQuestion = question.trim();
    try {
      const result = await chatWithAssistant({ loanId: loanId.trim() || undefined, question: askedQuestion });
      setExchanges((prev) => [...prev, { question: askedQuestion, answer: result.answer }]);
      setQuestion('');
    } catch (err) {
      setError(err);
    } finally {
      setAsking(false);
    }
  };

  return (
    <div>
      <h2>AI Assistant</h2>
      <p className="page-subtitle">
        Ask about a specific loan (fill in its Loan ID to ground the answer in its known
        features, score, case status, and notes) or ask a general question. Each question is
        independent -- the assistant doesn't remember earlier turns in this conversation yet.
      </p>

      <div className="card">
        <form onSubmit={ask}>
          <div className="field" style={{ maxWidth: 260, marginBottom: '0.8rem' }}>
            <label htmlFor="assistantLoanId">Loan ID (optional)</label>
            <input
              id="assistantLoanId"
              value={loanId}
              onChange={(e) => setLoanId(e.target.value)}
              placeholder="e.g. L-10293"
            />
          </div>
          <div className="field" style={{ marginBottom: '0.8rem' }}>
            <label htmlFor="assistantQuestion">Question</label>
            <input
              id="assistantQuestion"
              value={question}
              onChange={(e) => setQuestion(e.target.value)}
              placeholder="e.g. Why might this loan be considered high risk?"
              autoFocus
            />
          </div>
          <div className="actions">
            <button type="submit" disabled={asking || !question.trim()}>
              {asking ? 'Asking...' : 'Ask'}
            </button>
          </div>
        </form>
        <ErrorBanner error={error} />
      </div>

      {exchanges.length > 0 && (
        <div className="card">
          <h3>Conversation</h3>
          {exchanges.map((ex, i) => (
            <div key={i} style={{ marginBottom: '1rem' }}>
              <p style={{ fontWeight: 600, marginBottom: '0.25rem' }}>Q: {ex.question}</p>
              <p className="page-subtitle" style={{ whiteSpace: 'pre-wrap' }}>{ex.answer}</p>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
