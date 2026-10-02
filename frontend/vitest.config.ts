import { defineConfig } from 'vitest/config'

// Unit tests for the parts of the console that are logic, not layout: the API client's session
// handling and the shared formatting. jsdom supplies localStorage and window for the client.
export default defineConfig({
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.ts'],
  },
})
