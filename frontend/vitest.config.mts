import { defineConfig } from 'vitest/config';

const testWorkers = Number(process.env['MNEMA_TEST_WORKERS'] ?? '2');
if (!Number.isInteger(testWorkers) || testWorkers < 1 || testWorkers > 4) {
    throw new Error('MNEMA_TEST_WORKERS must be 1, 2, 3 or 4');
}

// Runner config for `ng test` (@angular/build:unit-test, vitest runner).
// `isolate: true` in angular.json gives every spec file its own environment: the builder's default
// (non-isolated workers, like the former Karma page) let one file's leftovers hang another file on CI.
// Spies and stubbed globals are still undone after every test (Jasmine did this implicitly).
export default defineConfig({
    test: {
        environment: 'jsdom',
        // Bound memory on the shared Mac; hosted jobs have 4 CPUs / 16 GiB.
        maxWorkers: testWorkers,
        restoreMocks: true,
        unstubGlobals: true,
        unstubEnvs: true
    }
});
