/**
 * jsdom evaluates no CSS animation, media query or gradient, so the `.generate-cta` contract is pinned on the
 * stylesheet source: a regression in the three-lap limit, the reduced-motion and forced-colors branches or the
 * hover/focus-only trigger fails here. How it looks and moves is checked in the real-browser harness.
 */
interface NodeFs { readFileSync(path: string, encoding: 'utf8'): string }

describe('.generate-cta stylesheet contract', () => {
    let css = '';

    beforeAll(async () => {
        const { readFileSync } = await import('node:fs' as string) as NodeFs;
        const root = (globalThis as unknown as { process: { cwd(): string } }).process.cwd();
        css = readFileSync(`${root}/src/global_styles.css`, 'utf8');
    });

    const block = (opening: string): string => {
        const start = css.indexOf(opening);
        expect(start, opening).toBeGreaterThanOrEqual(0);
        let depth = 0;
        for (let index = css.indexOf('{', start); index < css.length; index++) {
            if (css[index] === '{') depth++;
            if (css[index] === '}' && --depth === 0) return css.slice(start, index + 1);
        }
        throw new Error(`Unclosed block: ${opening}`);
    };

    it('registers the animated angle and the head and tail colours', () => {
        for (const name of ['--mn-snake-angle', '--mn-snake-head', '--mn-snake-tail']) {
            expect(css).toContain(`@property ${name}`);
        }
    });

    it('draws the snake as a conic gradient on the border-box layer', () => {
        const base = block('.generate-cta {');
        expect(base).toMatch(/conic-gradient\(from var\(--mn-snake-angle\)[\s\S]*\) border-box/);
        expect(base).toContain('padding-box');
        expect(base).not.toContain('animation');
    });

    it('runs exactly three laps within five seconds, only on hover or focus-visible and never when disabled', () => {
        const motion = block('@media (prefers-reduced-motion: no-preference)');
        expect(motion).toContain(':is(:hover, :focus-visible):not(:disabled');
        expect(motion).toContain("[aria-disabled='true']");
        const animation = /animation: mn-snake ([\d.]+)s linear (\d+) forwards/.exec(motion);
        expect(animation).not.toBeNull();
        expect(Number(animation![2])).toBe(3);
        expect(Number(animation![1]) * Number(animation![2])).toBeLessThanOrEqual(5);
        expect(css).not.toMatch(/animation:[^;]*infinite/);
    });

    it('falls back to a static highlight for reduced motion and to system colours for forced colors', () => {
        expect(block('/* Reduced motion: no lap')).toContain('--mn-snake-angle: 40deg');
        const forced = block('@media (forced-colors: active) {\n    .generate-cta');
        for (const token of ['ButtonText', 'ButtonFace', 'Highlight', 'animation: none']) expect(forced).toContain(token);
    });
});
