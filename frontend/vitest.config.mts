import { defineConfig } from 'vitest/config';

// Runner config for `ng test` (@angular/build:unit-test, vitest runner).
// The Angular builder runs all spec files in one non-isolated worker, like the former Karma page, so
// spies and stubbed globals must be undone after every test (Jasmine did this implicitly).
export default defineConfig({
    test: {
        environment: 'jsdom',
        restoreMocks: true,
        unstubGlobals: true,
        unstubEnvs: true
    }
});
