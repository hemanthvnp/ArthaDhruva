import { lazy, Suspense, useState } from 'react';
import Icon from './Icon';

const BorrowerAssistantPanel = lazy(() => import('./BorrowerAssistantPanel'));

/** The borrower safety assistant's launcher: a round button fixed to the bottom-right corner, on every
 * borrower page, opening a panel over the page rather than navigating to one (design decision 3). The panel
 * is loaded the first time it opens, so it costs the initial bundle nothing. */
export default function AssistantLauncher() {
  const [open, setOpen] = useState(false);
  return (
    <>
      {open && (
        <Suspense fallback={<div className="fab-panel" role="dialog" aria-label="Assistant"><p className="popover-empty">Loading...</p></div>}>
          <BorrowerAssistantPanel onClose={() => setOpen(false)} />
        </Suspense>
      )}
      <button
        type="button"
        className="fab"
        onClick={() => setOpen((o) => !o)}
        aria-label={open ? 'Close assistant' : 'Open assistant'}
        aria-expanded={open}
      >
        <Icon name={open ? 'close' : 'assistant'} size={24} />
      </button>
    </>
  );
}
