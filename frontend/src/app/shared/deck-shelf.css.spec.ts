/**
 * jsdom evaluates no layout, so what keeps a shelf from widening the page is pinned on the stylesheet source: the sideways row is a scroll box AND the containing
 * block of the cards' absolutely positioned parts (their words for a screen reader are `position: absolute`; without a positioned row they sit outside the
 * scroller, at the far end of the row, and make the whole page scroll sideways at 390 px). The real browser harness (`--catalog`) measures the page itself.
 */
interface NodeFs { readFileSync(path: string, encoding: 'utf8'): string }

describe('deck-shelf stylesheet contract', () => {
    let css = '';

    beforeAll(async () => {
        const { readFileSync } = await import('node:fs' as string) as NodeFs;
        const root = (globalThis as unknown as { process: { cwd(): string } }).process.cwd();
        css = readFileSync(`${root}/src/app/shared/deck-shelf.component.css`, 'utf8');
    });

    it('makes the sideways row a scroll box and the containing block of what its cards position absolutely', () => {
        const rule = /\n\.row \{([^}]*)\}/.exec(css);
        expect(rule).not.toBeNull();
        expect(rule![1]).toContain('overflow-x: auto');
        expect(rule![1]).toMatch(/position: relative/);
    });
});
