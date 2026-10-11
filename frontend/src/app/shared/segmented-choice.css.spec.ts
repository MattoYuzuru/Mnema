/**
 * jsdom evaluates no media query, so the forced-colors contract of the segmented choice is pinned on the stylesheet source.
 * Real Chrome with forced colors showed the selected label as an unreadable white box when it was HighlightText on a Highlight
 * fill (Chrome paints a Canvas backplate behind the text); the selection is now a filled mark and an outline instead.
 */
interface NodeFs { readFileSync(path: string, encoding: 'utf8'): string }

describe('app-segmented-choice forced-colors stylesheet contract', () => {
    let css = '';
    beforeAll(async () => {
        const { readFileSync } = await import('node:fs' as string) as NodeFs;
        const root = (globalThis as unknown as { process: { cwd(): string } }).process.cwd();
        css = readFileSync(`${root}/src/app/shared/segmented-choice.component.css`, 'utf8');
    });

    it('keeps every label ButtonText on Canvas and marks the selection with system-colour mark and outline', () => {
        const start = css.indexOf('@media (forced-colors: active)');
        expect(start).toBeGreaterThanOrEqual(0);
        const forced = css.slice(start);
        expect(forced).not.toMatch(/HighlightText/u);
        const selected = /\.segment:has\(:checked\)\s*\{([^}]*)\}/u.exec(forced)![1];
        expect(selected).toContain('background: Canvas');
        expect(selected).toContain('color: ButtonText');
        expect(selected).toContain('outline: 2px solid Highlight');
        expect(/\.segment:has\(:checked\) \.mark\s*\{([^}]*)\}/u.exec(forced)![1]).toContain('background: Highlight');
    });
});
