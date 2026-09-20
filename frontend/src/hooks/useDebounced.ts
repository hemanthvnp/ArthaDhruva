import { useEffect, useState } from 'react';

/** The value, but only after it has stopped changing for {@code delayMs}: typing in a filter box
 * then fires one request when the user pauses instead of one per keystroke. */
export function useDebounced<T>(value: T, delayMs = 300): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delayMs);
    return () => clearTimeout(timer);
  }, [value, delayMs]);
  return debounced;
}
