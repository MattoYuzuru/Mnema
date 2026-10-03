/**
 * jsdom evaluates no CSS animation or media query, so the contract of the ink reveal that blocks get while a material is
 * written (`.is-arriving`, set by `ProposalView`) is pinned on the stylesheet source: at most 600 ms, once, only on top-level
 * blocks of a document, and none at all for reduced motion. How it looks is checked in the real-browser harness.
 */
interface NodeFs { readFileSync(path: string, encoding: 'utf8'): string }

describe('.is-arriving stylesheet contract', () => {
    let css = '';

    beforeAll(async () => {
        const { readFileSync } = await import('node:fs' as string) as NodeFs;
        const root = (globalThis as unknown as { process: { cwd(): string } }).process.cwd();
        css = readFileSync(`${root}/src/global_styles.css`, 'utf8');
    });

    it('reveals a block once, in at most 600 ms, without looping', () => {
        const rule = /\.native-document > \.is-arriving \{\s*animation: mn-ink-in ([\d.]+)s [^;]*;/.exec(css);
        expect(rule).not.toBeNull();
        expect(Number(rule![1])).toBeLessThanOrEqual(0.6);
        expect(rule![0]).not.toContain('infinite');
        expect(css).toMatch(/@keyframes mn-ink-in \{[^}]*clip-path: inset\(0 0 100% 0\)[^}]*\}[^}]*clip-path: inset\(0 0 0 0\)/);
    });

    it('does not animate for reduced motion', () => {
        expect(css).toMatch(/@media \(prefers-reduced-motion: reduce\) \{\s*\.native-document > \.is-arriving \{ animation: none; \}/);
    });

    it('never fades the text: the reveal is a clip, not an opacity change', () => {
        const block = css.slice(css.indexOf('@keyframes mn-ink-in'), css.indexOf('@keyframes mn-ink-in') + 200);
        expect(block).not.toContain('opacity');
    });
});
