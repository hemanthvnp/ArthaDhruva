import { useEffect, useRef, useState } from 'react';
import { getPortfolioRun } from '../api/client';
import type { PortfolioRun } from '../api/types';

const FIRST_POLL_MS = 600;
const MAX_POLL_MS = 4000;

export const isActive = (run: PortfolioRun | null | undefined): boolean => run?.status === 'QUEUED' || run?.status === 'RUNNING';

/**
 * Watches a portfolio run until it finishes. The server does the work in the background and exposes
 * its progress as a row; this polls that row, quickly at first (a small portfolio is done in a second or
 * two) and then backing off to a steady four seconds, pausing while the tab is hidden. `onFinished`
 * fires once, when the run leaves the active states.
 */
export function usePortfolioRun(initial: PortfolioRun | null, onFinished: (run: PortfolioRun) => void): PortfolioRun | null {
  const [run, setRun] = useState<PortfolioRun | null>(initial);
  // A different run to watch (one just started, or the one a reload found): start over from it.
  const [watching, setWatching] = useState(initial);
  if (watching !== initial) {
    setWatching(initial);
    setRun(initial);
  }

  const finished = useRef(onFinished);
  useEffect(() => {
    finished.current = onFinished;
  });

  const id = run?.id;
  const active = isActive(run);
  useEffect(() => {
    if (!id || !active) return;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let delay = FIRST_POLL_MS;
    let stopped = false;

    const poll = async () => {
      if (document.visibilityState === 'visible') {
        try {
          const latest = await getPortfolioRun(id);
          if (stopped) return;
          setRun(latest);
          if (!isActive(latest)) {
            finished.current(latest);
            return;
          }
        } catch {
          // a failed poll is not a failed run: keep watching, more slowly
        }
      }
      delay = Math.min(MAX_POLL_MS, delay * 1.5);
      if (!stopped) timer = setTimeout(poll, delay);
    };
    timer = setTimeout(poll, delay);
    return () => {
      stopped = true;
      clearTimeout(timer);
    };
  }, [id, active]);

  return run;
}
