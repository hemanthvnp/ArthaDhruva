// Fails the build when the initial JavaScript a first-time visitor must download outgrows its
// budget. "Initial" = the entry chunk (index-*.js) plus everything the entry statically imports is
// already inside it; lazy page chunks are deliberately not counted, that is the point of splitting.
// Sizes are gzip, what actually crosses the network.
import { readdirSync, readFileSync } from 'node:fs';
import { gzipSync } from 'node:zlib';
import { join } from 'node:path';

const BUDGET_KB = Number(process.env.INITIAL_JS_BUDGET_KB ?? 120);
const dir = join(import.meta.dirname, 'dist', 'assets');
const entry = readdirSync(dir).find((f) => /^index-.*\.js$/.test(f));
if (!entry) {
  console.error('No entry chunk (index-*.js) found in dist/assets; did the build run?');
  process.exit(2);
}
const gzKb = gzipSync(readFileSync(join(dir, entry))).length / 1024;
console.log(`Initial JS: ${entry} = ${gzKb.toFixed(1)} KB gzip (budget ${BUDGET_KB} KB)`);
if (gzKb > BUDGET_KB) {
  console.error(`FAIL: initial JS ${gzKb.toFixed(1)} KB exceeds the ${BUDGET_KB} KB budget by ${(gzKb - BUDGET_KB).toFixed(1)} KB`);
  process.exit(1);
}
