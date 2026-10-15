import { defineConfig } from 'vitest/config';

// Runner config for `ng test` (@angular/build:unit-test, vitest runner).
// `isolate: true` in angular.json gives every spec file its own environment: the builder's default
// (non-isolated workers, like the former Karma page) let one file's leftovers hang another file on CI.
// Spies and stubbed globals are still undone after every test (Jasmine did this implicitly).
export default defineConfig({
    test: {
        environment: 'jsdom',
        restoreMocks: true,
        unstubGlobals: true,
        unstubEnvs: true
    }
});
