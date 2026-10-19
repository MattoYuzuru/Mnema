/**
 * jsdom evaluates no media query and paints nothing, so the contracts of the tour's elements that live in CSS are pinned on the
 * stylesheet sources: forced colours (the veil is not relied on, the frame becomes `Highlight`), reduced motion (no twinkle, a fill in
 * steps), the finite twinkle of the brand contract, and no colour that is not a token. How it looks is checked in a real browser.
 */
interface NodeFs { readFileSync(path: string, encoding: 'utf8'): string }

describe('stylesheet contract of the tour elements', () => {
    const sources = new Map<string, string>();
    const read = (name: string): string => sources.get(name)!;
    /** The text of the `@media (query)` block that starts the first time `query` appears. */
    const media = (source: string, query: string): string => {
        const start = source.indexOf(`@media (${query})`);
        expect(start, query).toBeGreaterThanOrEqual(0);
        let depth = 0;
        for (let index = source.indexOf('{', start); index < source.length; index++) {
            if (source[index] === '{') depth++;
            if (source[index] === '}' && --depth === 0) return source.slice(start, index + 1);
        }
        throw new Error(`unterminated @media (${query})`);
    };

    beforeAll(async () => {
        const { readFileSync } = await import('node:fs' as string) as NodeFs;
        const root = (globalThis as unknown as { process: { cwd(): string } }).process.cwd();
        for (const name of ['shared/tour-card.component.css', 'shared/hold-to-delete-button.component.css', 'shared/tour-scrim.component.ts', 'shared/night-sky.component.ts',
            'shared/tour-pause.component.ts', 'shared/reward-code.component.ts', 'shared/reward-card.component.ts', 'features/onboarding/reward-congratulation.component.css', '../theme/components.css']) {
            sources.set(name, readFileSync(`${root}/src/app/${name}`, 'utf8'));
        }
    });

    describe('the veil', () => {
        it('is not relied on in forced colours: it turns transparent and the frame becomes a Highlight outline', () => {
            const forced = media(read('shared/tour-scrim.component.ts'), 'forced-colors: active');
            expect(forced).toMatch(/\.veil\s*\{\s*fill:\s*transparent;?\s*\}/u);
            expect(forced).toMatch(/\.frame\s*\{[^}]*box-shadow:\s*none[^}]*outline:\s*3px solid Highlight/u);
        });

        it('lets a click pass through the cut-out and never closes anything: only the painted veil takes events', () => {
            const source = read('shared/tour-scrim.component.ts');
            expect(source).toMatch(/:host\s*\{[^}]*pointer-events:\s*none/u);
            expect(source).toMatch(/\.veil\s*\{[^}]*pointer-events:\s*auto/u);
            expect(source).toContain('fill-rule="evenodd"');
        });
    });

    describe('the night surface', () => {
        it('twinkles only when motion is welcome, three times for 3.6 seconds, and then rests', () => {
            const source = read('shared/night-sky.component.ts');
            const welcome = media(source, 'prefers-reduced-motion: no-preference');
            expect(welcome).toMatch(/animation:\s*twinkle 3\.6s ease-in-out var\(--delay, 0s\) 3;/u);
            expect(source.replace(welcome, '')).not.toContain('animation');
            expect(source).not.toMatch(/infinite/u);
        });

        it('is Canvas without stars in forced colours, and drops the side stars on a phone', () => {
            const source = read('shared/night-sky.component.ts');
            const forced = media(source, 'forced-colors: active');
            expect(forced).toContain('background: Canvas');
            expect(forced).toMatch(/\.sky\s*\{\s*display:\s*none/u);
            expect(media(source, 'max-width: 37.5rem')).toMatch(/\.star\.side\s*\{\s*display:\s*none/u);
        });

        it('turns the content into system colours in forced colours', () => {
            const forced = media(read('../theme/components.css').slice(read('../theme/components.css').indexOf('Content on the night surface')), 'forced-colors: active');
            expect(forced).toContain('CanvasText');
            expect(forced).toMatch(/app-night-sky \.content > \.actions \.button[^{]*\{[^}]*ButtonText[^}]*box-shadow:\s*none/u);
        });
    });

    describe('the finish gesture', () => {
        it('fills in steps without animation when calm: no clip-path, a width from the step, no transition', () => {
            expect(read('shared/hold-to-delete-button.component.css')).toMatch(/\.hold-button\.is-stepped \.hold-wave\s*\{[^}]*clip-path:\s*none;[^}]*inline-size:\s*var\(--hold-step, 0%\);[^}]*transition:\s*none/u);
        });

        it('keeps the deletion\'s hidden wave under reduced motion, and shows the wave only for the glyph variant', () => {
            const reduced = media(read('shared/hold-to-delete-button.component.css'), 'prefers-reduced-motion: reduce');
            expect(reduced).toMatch(/\.hold-wave\s*\{\s*display:\s*none/u);
            expect(reduced).toMatch(/\.hold-button\.has-glyph \.hold-wave\s*\{\s*display:\s*block/u);
        });

        it('fills with Highlight in forced colours, the ink variant included', () => {
            expect(media(read('shared/hold-to-delete-button.component.css'), 'forced-colors: active')).toContain('Highlight');
        });

        it('does not grow the button when the larger glyph replaces the label', () => {
            expect(read('shared/hold-to-delete-button.component.css')).toMatch(/\.hold-glyph\s*\{[^}]*--mn-glyph-size:\s*1\.75rem;[^}]*margin-block:\s*-\.5rem/u);
        });
    });

    describe('the card', () => {
        it('lets the veil do the dimming, shows the heading without a ring and underlines key words in forced colours', () => {
            const css = read('shared/tour-card.component.css');
            expect(css).toMatch(/\.tour-card::backdrop\s*\{\s*background:\s*transparent/u);
            expect(css).toMatch(/h2:focus-visible\s*\{\s*outline:\s*none/u);
            expect(css).toMatch(/\.tour-card\[open\]\s*\{\s*display:\s*grid/u);
            const forced = media(css, 'forced-colors: active');
            expect(forced).toMatch(/\.key\s*\{\s*text-decoration:\s*underline/u);
            expect(forced).toContain('CanvasText');
        });

        it('highlights key words by weight as well as colour', () => {
            expect(read('shared/tour-card.component.css')).toMatch(/\.key\s*\{\s*color:\s*var\(--mn-ink\);\s*font-weight:\s*700/u);
        });

        it('stays inside a 320px window: no fixed width wider than the window', () => {
            const css = read('shared/tour-card.component.css');
            expect(css).toMatch(/inline-size:\s*min\(24rem, calc\(100vw - 2 \* var\(--mn-page-gutter\)\)\)/u);
            expect(css).not.toMatch(/min-inline-size:\s*[1-9][\d.]*(?:rem|px|em)/u);
        });
    });

    describe('the reward surfaces (OB-402)', () => {
        const rewardSources = ['shared/reward-code.component.ts', 'shared/reward-card.component.ts', 'features/onboarding/reward-congratulation.component.css'];

        it.each(rewardSources)('%s moves nothing of its own: no animation, no transition, so reduced motion has nothing to switch off beyond the shared sky', name => {
            expect(read(name)).not.toMatch(/animation|transition|@keyframes/u);
        });

        it('keeps the congratulation readable in forced colours: Canvas ground, system text, the code outlined in CanvasText', () => {
            expect(media(read('features/onboarding/reward-congratulation.component.css'), 'forced-colors: active')).toMatch(/color:\s*CanvasText;\s*background:\s*Canvas/u);
            expect(media(read('shared/reward-code.component.ts'), 'forced-colors: active')).toContain('CanvasText');
            expect(media(read('shared/reward-card.component.ts'), 'forced-colors: active')).toContain('CanvasText');
        });

        it('makes the congratulation a full-screen night of its own, with no second veil, over the shared sky', () => {
            const css = read('features/onboarding/reward-congratulation.component.css');
            expect(css).toMatch(/\.reward-night::backdrop\s*\{\s*background:\s*var\(--mn-night\)/u);
            expect(css).toMatch(/\.reward-night app-night-sky\s*\{\s*min-block-size:\s*100dvh/u);
        });

        it('draws a lavender focus ring on the night for the code and the button, over the global ring of an input (which is !important), and Highlight in forced colours', () => {
            const source = read('shared/reward-code.component.ts');
            expect(source).toMatch(/:host\(\.night\) \.code:focus-visible\s*\{[^}]*outline:\s*3px solid var\(--mn-star-lavender\)\s*!important[^}]*outline-offset:\s*2px\s*!important/u);
            expect(source).toMatch(/:host\(\.night\) \.button:focus-visible\s*\{[^}]*outline:\s*3px solid var\(--mn-star-lavender\)/u);
            const forced = media(source, 'forced-colors: active');
            expect(forced).toMatch(/:host\(\.night\) \.code:focus-visible[^{]*\{[^}]*outline-color:\s*Highlight/u);
            expect(forced).toMatch(/:host\(\.night\) \.button:focus-visible[^{]*\{[^}]*outline-color:\s*Highlight/u);
        });

        it('lets the code be selected whole and keeps it inside a 320px window', () => {
            const source = read('shared/reward-code.component.ts');
            expect(source).toMatch(/user-select:\s*all/u);
            expect(source).toMatch(/max-inline-size:\s*100%/u);
            expect(source).not.toMatch(/min-inline-size:\s*[1-9][\d.]*(?:rem|px|em)/u);
        });
    });

    describe('every colour is a token', () => {
        it.each(['shared/reward-code.component.ts', 'shared/reward-card.component.ts', 'features/onboarding/reward-congratulation.component.css', 'shared/tour-card.component.css', 'shared/hold-to-delete-button.component.css', 'shared/tour-scrim.component.ts', 'shared/night-sky.component.ts', 'shared/tour-pause.component.ts'])(
            '%s has no hex colour and no rgb() literal', name => {
                expect(read(name)).not.toMatch(/#[0-9a-f]{3,8}\b/iu);
                expect(read(name)).not.toMatch(/\brgba?\(/u);
            });
    });
});
