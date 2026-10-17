/**
 * jsdom evaluates no CSS animation or media query, so the motion contract of the open book is pinned on the stylesheet
 * source: the opening lasts at most 600 ms, plays once and leaves no clip-path behind (a focus ring must never be cut), and
 * reduced motion removes it. How it looks is checked in a real browser.
 */
interface NodeFs { readFileSync(path: string, encoding: 'utf8'): string }

describe('book-search stylesheet contract', () => {
    let css = '';

    beforeAll(async () => {
        const { readFileSync } = await import('node:fs' as string) as NodeFs;
        const root = (globalThis as unknown as { process: { cwd(): string } }).process.cwd();
        css = readFileSync(`${root}/src/app/shared/book-search.component.css`, 'utf8');
    });

    it('opens in at most 600 ms, once, and keeps no clip afterwards', () => {
        const rule = /\.spread\.is-opening \{ animation: mn-book-open ([\d.]+)s [^;]*;/.exec(css);
        expect(rule).not.toBeNull();
        expect(Number(rule![1])).toBeLessThanOrEqual(0.6);
        expect(rule![0]).not.toContain('infinite');
        expect(rule![0]).toContain('backwards');
        expect(rule![0]).not.toMatch(/forwards|both/);
    });

    it('does not animate for reduced motion', () => {
        expect(css).toMatch(/@media \(prefers-reduced-motion: reduce\) \{\s*\.spread\.is-opening \{ animation: none; \}/);
    });

    it('never fades the text: the opening is a clip, not an opacity change', () => {
        const keyframes = css.slice(css.indexOf('@keyframes mn-book-open'), css.indexOf('@media (prefers-reduced-motion'));
        expect(keyframes).toContain('clip-path');
        expect(keyframes).not.toContain('opacity');
    });
});
