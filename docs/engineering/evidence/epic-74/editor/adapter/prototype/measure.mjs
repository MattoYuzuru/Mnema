import assert from "node:assert/strict";
import { exportNativeDocument, importNativeDocument } from "./src/adapter.js";
import { createScaleFixture } from "./src/fixtures.js";

const fixture = createScaleFixture();
const serializedBytes = new TextEncoder().encode(JSON.stringify(fixture)).byteLength;
const iterations = 12;
const samplesMs = [];
if (globalThis.gc) globalThis.gc();
const heapBefore = process.memoryUsage().heapUsed;
for (let index = 0; index < iterations; index += 1) {
  const before = performance.now();
  const session = importNativeDocument(fixture);
  const output = exportNativeDocument(session.doc);
  samplesMs.push(performance.now() - before);
  assert.deepEqual(output, fixture);
}
if (globalThis.gc) globalThis.gc();
const heapAfter = process.memoryUsage().heapUsed;
const sorted = [...samplesMs].sort((a, b) => a - b);
const percentile = (fraction) => sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * fraction))];
console.log(JSON.stringify({
  classification: "synthetic local adapter measurement; not a production SLO or browser-render benchmark",
  runtime: process.version,
  architecture: process.arch,
  platform: process.platform,
  fixture: { serializedBytes, nodes: 7_001, paragraphs: 3_500, depth: 3 },
  iterations,
  roundTripMs: { min: sorted[0], median: percentile(0.5), p95: percentile(0.95), max: sorted.at(-1) },
  heapUsedDeltaBytesAfterGc: heapAfter - heapBefore,
  gcExposed: Boolean(globalThis.gc),
}, null, 2));
