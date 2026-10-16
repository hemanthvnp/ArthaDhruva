import http from 'node:http';
import type { AddressInfo } from 'node:net';

/**
 * A stand-in for the risk API that records every request and answers from per-route handlers, so contract
 * tests see exactly which headers, cookies and keys the server sent.
 */
export interface Seen {
  method: string;
  path: string;
  headers: http.IncomingHttpHeaders;
  body: unknown;
}

export type Reply = { status?: number; body?: unknown; headers?: Record<string, string | string[]>; delayMs?: number };
export type Handler = (req: Seen, n: number) => Reply;

export class FakeApi {
  readonly seen: Seen[] = [];
  private readonly routes = new Map<string, Handler>();
  private readonly counts = new Map<string, number>();
  private server!: http.Server;
  url = '';

  on(method: string, path: string, handler: Handler | Reply): this {
    this.routes.set(`${method} ${path}`, typeof handler === 'function' ? handler : () => handler);
    return this;
  }

  requests(method: string, path: string): Seen[] {
    return this.seen.filter((s) => s.method === method && s.path.split('?')[0] === path);
  }

  async start(): Promise<this> {
    this.server = http.createServer((req, res) => {
      let raw = '';
      req.on('data', (c: Buffer) => (raw += c.toString()));
      req.on('end', () => {
        const path = (req.url ?? '').split('?')[0]!;
        const seen: Seen = { method: req.method ?? '', path: req.url ?? '', headers: req.headers, body: raw ? JSON.parse(raw) : undefined };
        this.seen.push(seen);
        const key = `${req.method} ${path}`;
        const n = (this.counts.get(key) ?? 0) + 1;
        this.counts.set(key, n);
        const handler = this.routes.get(key) ?? (path === '/v1/csrf' ? () => ({ status: 401, headers: { 'Set-Cookie': 'XSRF-TOKEN=csrf-api; Path=/' } }) : undefined);
        const reply = typeof handler === 'function' ? handler(seen, n) : { status: 404, body: { error: 'Not Found' } };
        const send = () => {
          if (res.destroyed) return;
          res.writeHead(reply.status ?? 200, { 'Content-Type': 'application/json', ...(reply.headers ?? {}) });
          res.end(reply.body === undefined ? '' : JSON.stringify(reply.body));
        };
        if (reply.delayMs) setTimeout(send, reply.delayMs);
        else send();
      });
    });
    await new Promise<void>((r) => this.server.listen(0, '127.0.0.1', r));
    this.url = `http://127.0.0.1:${(this.server.address() as AddressInfo).port}`;
    return this;
  }

  async stop(): Promise<void> {
    this.server.closeAllConnections();
    await new Promise<void>((r) => this.server.close(() => r()));
  }
}

export const LOAN = {
  loanId: 'F19Q20102655', creditScore: 640, originalDti: 36, originalUpb: 300000, originalCltv: 80, originalLtv: 80,
  originalInterestRate: 6.5, originalLoanTerm: 360, numberOfBorrowers: 2, numberOfUnits: 1, miPercent: 0, occupancyStatus: 'P',
  propertyType: 'SF', loanPurpose: 'P', channel: 'R', firstTimeHomebuyerFlag: 'N', propertyState: 'FL', originationMonth: '2021-06',
};

export const SESSION = 'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhbmEifQ.session-signature-abc';

export const RUN = {
  id: '6f1c1e2a-4d7b-4c84-9a8e-2b5b4cf0a111', status: 'QUEUED', scenarios: ['BASELINE', 'ADVERSE'], loansTotal: 400, loansDone: 0,
  requestedBy: 'ana', requestedAt: '2026-10-14T10:00:00Z', startedAt: null, finishedAt: null, error: null, attempt: 1, deferred: false,
};
