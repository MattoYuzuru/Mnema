/**
 * jsdom lays nothing out, so the stylesheet contract of the hub's setting «Принимать безопасное автоматически» is pinned on the source.
 * The page-wide `input` rule (full width, padding, a field's border) is for text fields; without a reset it stretched the setting's
 * checkbox across the row and pushed the explanation off a 320 px screen.
 */
interface NodeFs { readFileSync(path: string, encoding: 'utf8'): string }

describe('own-decks-page.css: the hub setting', () => {
    let css = '';
    beforeAll(async () => {
        const { readFileSync } = await import('node:fs' as string) as NodeFs;
        const root = (globalThis as unknown as { process: { cwd(): string } }).process.cwd();
        css = readFileSync(`${root}/src/app/features/own-decks/own-decks-page.css`, 'utf8');
    });

    const rule = (selector: string): string => {
        const start = css.indexOf(`${selector} {`);
        expect(start, selector).toBeGreaterThanOrEqual(0);
        return css.slice(start, css.indexOf('}', start));
    };

    it('turns the checkbox back into a small box, like the study session does', () => {
        const box = rule('.auto-update .check-row input');
        for (const declaration of ['inline-size: 1.25rem', 'block-size: 1.25rem', 'padding: 0', 'margin: 0']) expect(box).toContain(declaration);
    });

    it('puts the explanation beside the label in its own column, so the two never overlap and the label may shrink and wrap', () => {
        const row = rule('.auto-update-row');
        expect(row).toContain('grid-template-columns: minmax(0, 1fr) auto');
        expect(rule('.auto-update-row .check-row')).toContain('min-inline-size: 0');
    });
});
