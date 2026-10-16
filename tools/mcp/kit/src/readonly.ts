import { ToolError } from './errors.js';

/**
 * Marks which adapter methods change something. A server declared read-only gets an adapter whose mutating
 * methods fail with Forbidden before doing any I/O, and the tool registry uses the same marks to refuse a tool
 * that calls a mutating method while claiming to be read-only.
 */
export const MUTATING = Symbol('mutating');

export interface Adapter {
  readonly [MUTATING]?: readonly string[];
}

/** Declares the mutating methods of an adapter object (returns the same object). */
export function markMutating<T extends object>(adapter: T, methods: ReadonlyArray<keyof T & string>): T & Adapter {
  Object.defineProperty(adapter, MUTATING, { value: [...methods], enumerable: false });
  return adapter as T & Adapter;
}

export function mutatingMethods(adapter: object): readonly string[] {
  return (adapter as Adapter)[MUTATING] ?? [];
}

/** Wraps an adapter so its mutating methods throw Forbidden when the server is read-only. */
export function guard<T extends object>(adapter: T, readOnly: boolean): T {
  if (!readOnly) return adapter;
  const blocked = new Set(mutatingMethods(adapter));
  return new Proxy(adapter, {
    get(target, prop, receiver) {
      const value = Reflect.get(target, prop, receiver) as unknown;
      if (typeof prop === 'string' && blocked.has(prop) && typeof value === 'function') {
        return () => {
          throw new ToolError('Forbidden', `${prop} changes state and this server is read-only.`);
        };
      }
      return typeof value === 'function' ? (value as (...a: unknown[]) => unknown).bind(target) : value;
    },
  });
}
