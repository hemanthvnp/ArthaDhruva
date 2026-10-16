import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { askBorrowerAssistant, myLoans } from '../api/client';
import type { BorrowerContact } from '../api/types';
import ContactCard from './ContactCard';
import Icon from './Icon';

type Turn = { question: string; answer?: string; failed?: boolean };

/**
 * The panel behind the launcher. Single-turn like the backend: each question is answered on its own. The
 * loan officer card sits under the thread on every answer, found or not, so the way to a person never depends
 * on what the model said (design: escalation always present).
 */
export default function BorrowerAssistantPanel({ onClose }: { onClose: () => void }) {
  const [turns, setTurns] = useState<Turn[]>([]);
  const [question, setQuestion] = useState('');
  const [busy, setBusy] = useState(false);
  const [contact, setContact] = useState<BorrowerContact | null | undefined>(undefined);
  // Until the first answer arrives, the contact shown is the one on the borrower's own loan page.
  const loans = useQuery({ queryKey: ['my-loans'], queryFn: myLoans });
  const shownContact = contact !== undefined ? contact : (loans.data?.find((l) => l.contact)?.contact ?? null);
  const thread = useRef<HTMLDivElement>(null);
  const input = useRef<HTMLInputElement>(null);

  useEffect(() => input.current?.focus(), []);
  useEffect(() => {
    thread.current?.scrollTo({ top: thread.current.scrollHeight });
  }, [turns]);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose]);

  const send = async (e: FormEvent) => {
    e.preventDefault();
    const asked = question.trim();
    if (!asked || busy) return;
    setQuestion('');
    setBusy(true);
    setTurns((t) => [...t, { question: asked }]);
    try {
      const reply = await askBorrowerAssistant(asked);
      setContact(reply.contact);
      setTurns((t) => t.map((turn, i) => (i === t.length - 1 ? { ...turn, answer: reply.answer } : turn)));
    } catch (err) {
      setTurns((t) => t.map((turn, i) => (i === t.length - 1 ? { ...turn, answer: err instanceof Error ? err.message : String(err), failed: true } : turn)));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="fab-panel" role="dialog" aria-label="Assistant">
      <div className="assist-head">
        <h3>Assistant</h3>
        <button className="assist-close" type="button" aria-label="Close assistant" onClick={onClose}>
          <Icon name="close" size={14} />
        </button>
      </div>
      <div className="assist-safety">
        <Icon name="shieldcheck" size={15} />
        <span>Only sees your own loan. Never asks for your password or one-time code.</span>
      </div>
      <div className="assist-thread" ref={thread} aria-live="polite">
        {turns.length === 0 && (
          <p className="assist-intro">
            Got a message about your mortgage that doesn't feel right? Paste it here and ask whether it's real. You can also
            ask what a document or your loan status means.
          </p>
        )}
        {turns.map((turn, i) => (
          <div key={i} className="assist-turn">
            <div className="assist-bubble me">{turn.question}</div>
            {turn.answer === undefined
              ? <div className="assist-bubble bot pending">Checking...</div>
              : <div className={`assist-bubble bot${turn.failed ? ' failed' : ''}`}>{turn.answer}</div>}
          </div>
        ))}
        <div className="assist-escalate">
          <div className="assist-escalate-label">Not sure? Talk to a person</div>
          <ContactCard contact={shownContact} compact />
        </div>
      </div>
      <form className="assist-input" onSubmit={send}>
        <input
          ref={input}
          aria-label="Question"
          placeholder="Ask, or paste a suspicious message..."
          value={question}
          maxLength={2000}
          onChange={(e) => setQuestion(e.target.value)}
        />
        <button type="submit" disabled={busy || !question.trim()}>Send</button>
      </form>
    </div>
  );
}
