import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vitest/config';

// Workspace packages are tested from source: the kit is aliased to its TypeScript entry point, so a test
// never runs against a stale build.
export default defineConfig({
  resolve: {
    alias: { '@arthadhruva/mcp-kit': fileURLToPath(new URL('./kit/src/index.ts', import.meta.url)) },
  },
  test: {
    include: ['*/test/**/*.test.ts'],
    testTimeout: 30_000,
  },
});
