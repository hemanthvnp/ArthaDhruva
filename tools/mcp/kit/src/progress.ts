import type { RequestHandlerExtra } from '@modelcontextprotocol/sdk/shared/protocol.js';
import type { ServerNotification, ServerRequest } from '@modelcontextprotocol/sdk/types.js';

export type Extra = RequestHandlerExtra<ServerRequest, ServerNotification>;

/**
 * MCP progress for long tools. When the client sent a progress token, `report` sends `notifications/progress`
 * with a value that never goes backwards; without one it does nothing. `signal` is the request's own abort
 * signal: the client's cancellation, to hand to the subprocess runner and to check between steps.
 */
export interface Progress {
  report(message: string, progress?: number, total?: number): Promise<void>;
  readonly signal: AbortSignal;
  readonly enabled: boolean;
}

export function progressFor(extra: Extra | undefined): Progress {
  const token = extra?._meta?.progressToken;
  const signal = extra?.signal ?? new AbortController().signal;
  let last = 0;
  return {
    signal,
    enabled: token !== undefined,
    async report(message, progress, total) {
      if (token === undefined || !extra) return;
      last = Math.max(last, progress ?? last + 1);
      await extra
        .sendNotification({
          method: 'notifications/progress',
          params: { progressToken: token, progress: last, ...(total === undefined ? {} : { total }), message },
        })
        .catch(() => undefined); // a client that went away does not fail the work
    },
  };
}
