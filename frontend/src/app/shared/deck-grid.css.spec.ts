/**
 * jsdom evaluates no CSS animation or media query, so the motion contract of the result grid is pinned on the stylesheet source:
 * the arriving card uses the shared ink reveal `mn-ink-in` once, in at most 600 ms, hidden until its turn and leaving no clip behind,
 * and reduced motion removes the animation and with it the cascade. How it looks is checked in a real browser.
 */
interface NodeFs { readFileSync(path: string, encoding: 'utf8'): string }

describe('deck-grid stylesheet contract', () => {
    let css = '';
    let global = '';

    beforeAll(async () => {
        const { readFileSync } = await import('node:fs' as string) as NodeFs;
        const root = (globalThis as unknown as { process: { cwd(): string } }).process.cwd();
        css = readFileSync(`${root}/src/app/shared/deck-grid.component.css`, 'utf8');
        global = readFileSync(`${root}/src/global_styles.css`, 'utf8');
    });

    it('reveals an arriving card with the shared ink reveal, once, in at most 600 ms', () => {
        expect(global).toContain('@keyframes mn-ink-in');
        const rule = /\.cell \{ animation: mn-ink-in ([\d.]+)s [^;]*var\(--mn-cascade-delay[^;]*;/.exec(css);
        expect(rule).not.toBeNull();
        expect(Number(rule![1])).toBeLessThanOrEqual(0.6);
        expect(rule![0]).not.toContain('infinite');
        expect(rule![0]).toContain('backwards');
        expect(rule![0]).not.toMatch(/forwards|both/);
    });

    it('keeps the empty count region rendered: it collapses, it is never display: none', () => {
        const rule = /\.status:empty \{([^}]*)\}/.exec(css);
        expect(rule).not.toBeNull();
        expect(rule![1]).not.toContain('display');
        expect(rule![1]).toContain('block-size: 0');
    });

    it('does not redefine the keyframes: the component uses the global ones', () => {
        expect(css).not.toContain('@keyframes');
    });

    it('does not animate, and so does not cascade, for reduced motion', () => {
        expect(css).toMatch(/@media \(prefers-reduced-motion: reduce\) \{\s*\.cell \{ animation: none; \}/);
    });
});
