import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { Redactor } from '@arthadhruva/mcp-kit';
import { RiskApi } from '../src/http.js';
import { loan } from '../src/schemas.js';
import { CONTRACT, tools, type Endpoint } from '../src/tools.js';

/**
 * Drift between the tools and the API. The backend publishes no OpenAPI document (adding one is a separate
 * backend change), so the API's schema is derived from its source: every @GetMapping/@PostMapping, and every
 * Java record with its components and which of them are @NotNull/@NotBlank/@NotEmpty. A tool fails here when
 * its endpoint is gone, when its request record gains a required field the tool does not send, or when a
 * response field the tool reads disappears.
 */
const JAVA = path.resolve(fileURLToPath(new URL('../../../../backend/risk-engine/src/main/java', import.meta.url)));

interface RecordInfo {
  name: string;
  components: Array<{ name: string; required: boolean }>;
}

export interface ApiIndex {
  mappings: Set<string>;
  records: RecordInfo[];
}

function javaFiles(dir: string): string[] {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((e) =>
    e.isDirectory() ? javaFiles(path.join(dir, e.name)) : e.name.endsWith('.java') ? [path.join(dir, e.name)] : []);
}

/** Splits a record header's components at top-level commas (generics and annotation arguments nest). */
function splitTop(s: string): string[] {
  const parts: string[] = [];
  let depth = 0;
  let current = '';
  for (const ch of s) {
    if (ch === '<' || ch === '(' || ch === '{' || ch === '[') depth++;
    if (ch === '>' || ch === ')' || ch === '}' || ch === ']') depth--;
    if (ch === ',' && depth === 0) {
      parts.push(current);
      current = '';
    } else current += ch;
  }
  if (current.trim()) parts.push(current);
  return parts;
}

export function indexJava(sources: string[]): ApiIndex {
  const mappings = new Set<string>();
  const records: RecordInfo[] = [];
  for (const src of sources) {
    for (const m of src.matchAll(/@(Get|Post|Put|Delete)Mapping\("([^"]+)"/g)) mappings.add(`${m[1]!.toUpperCase()} ${m[2]}`);
    for (const m of src.matchAll(/\brecord\s+(\w+)\s*\(/g)) {
      let depth = 1;
      let i = m.index! + m[0].length;
      const start = i;
      while (i < src.length && depth > 0) {
        if (src[i] === '(') depth++;
        if (src[i] === ')') depth--;
        i++;
      }
      const header = src.slice(start, i - 1).replace(/\/\/.*$/gm, '').replace(/\/\*[\s\S]*?\*\//g, '');
      const components = splitTop(header).map((c) => {
        const tokens = c.trim().replace(/@\w+(\([^)]*\))?/g, (a) => ` ${a.split('(')[0]} `).trim().split(/\s+/);
        return { name: tokens[tokens.length - 1]!, required: /@(NotNull|NotBlank|NotEmpty)\b/.test(c) };
      }).filter((c) => /^\w+$/.test(c.name));
      records.push({ name: m[1]!, components });
    }
  }
  return { mappings, records };
}

/** What each tool sends: the loan's fields for a LoanFeatures body, otherwise the tool's own input fields. */
function sentFields(tool: string, endpoint: Endpoint, inputKeys: Record<string, string[]>): Set<string> {
  return new Set(endpoint.request === 'LoanFeatures' ? Object.keys(loan.shape) : inputKeys[tool] ?? []);
}

export function drift(index: ApiIndex, contract: Record<string, Endpoint>, inputKeys: Record<string, string[]>): string[] {
  const problems: string[] = [];
  for (const [tool, e] of Object.entries(contract)) {
    if (!index.mappings.has(`${e.method} ${e.path}`)) {
      problems.push(`${tool}: ${e.method} ${e.path} no longer exists`);
      continue;
    }
    if (e.request) {
      const records = index.records.filter((r) => r.name === e.request);
      if (records.length === 0) problems.push(`${tool}: request record ${e.request} not found`);
      const sent = sentFields(tool, e, inputKeys);
      for (const r of records) {
        for (const c of r.components.filter((x) => x.required && !sent.has(x.name))) {
          problems.push(`${tool}: ${e.request} requires '${c.name}', which the tool does not send`);
        }
      }
    }
    if (e.response && e.reads?.length) {
      const candidates = index.records.filter((r) => r.name === e.response);
      const ok = candidates.some((r) => e.reads!.every((f) => r.components.some((c) => c.name === f)));
      if (!ok) {
        const best = candidates[0];
        const missing = best ? e.reads.filter((f) => !best.components.some((c) => c.name === f)) : e.reads;
        problems.push(`${tool}: response ${e.response} lacks ${missing.join(', ')}`);
      }
    }
  }
  return problems;
}

const toolInputs = Object.fromEntries(
  tools(new RiskApi({ baseUrl: 'http://x', session: 'a.b.c', timeoutMs: 1 }, new Redactor()), { maxRows: 10 })
    .map((t) => [t.name, Object.keys(t.input as object)]),
);

describe('API drift', () => {
  const index = indexJava(javaFiles(JAVA).map((f) => fs.readFileSync(f, 'utf8')));

  it('reads the controllers and records it needs', () => {
    expect(index.mappings.has('POST /score')).toBe(true);
    const features = index.records.find((r) => r.name === 'LoanFeatures')!;
    expect(features.components.find((c) => c.name === 'creditScore')).toEqual({ name: 'creditScore', required: true });
    expect(features.components.find((c) => c.name === 'loanId')).toEqual({ name: 'loanId', required: false });
  });

  it('has every tool mapped to a live endpoint with the fields it sends and reads', () => {
    expect(Object.keys(CONTRACT).sort()).toEqual(Object.keys(toolInputs).sort());
    expect(drift(index, CONTRACT, toolInputs)).toEqual([]);
  });

  it('names the tool and field when a controller adds a required request field', () => {
    const changed: ApiIndex = {
      mappings: index.mappings,
      records: index.records.map((r) => (r.name === 'TermStructureRequest' ? { ...r, components: [...r.components, { name: 'valuationDate', required: true }] } : r)),
    };
    expect(drift(changed, CONTRACT, toolInputs)).toEqual(["term_structure: TermStructureRequest requires 'valuationDate', which the tool does not send"]);
  });

  it('names the tool when an endpoint it maps to is removed, or a field it reads disappears', () => {
    const removed: ApiIndex = { mappings: new Set([...index.mappings].filter((m) => m !== 'POST /cvar')), records: index.records };
    expect(drift(removed, CONTRACT, toolInputs)).toEqual(['cvar: POST /cvar no longer exists']);
    const thinner: ApiIndex = {
      mappings: index.mappings,
      records: index.records.map((r) => (r.name === 'RegimeForecast' ? { ...r, components: r.components.filter((c) => c.name !== 'dataAgeMonths') } : r)),
    };
    expect(drift(thinner, CONTRACT, toolInputs)).toEqual(['regime_forecast: response RegimeForecast lacks dataAgeMonths']);
  });
});
