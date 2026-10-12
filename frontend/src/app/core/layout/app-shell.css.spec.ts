/**
 * jsdom evaluates no media query and has no layout, so the masthead's reflow contract is pinned on the stylesheet source: the four nav
 * links, the book entry, the bell and «Выйти» must never force the page wider. How it looks from 320 px to wide is checked in a real browser.
 */
interface NodeFs { readFileSync(path: string, encoding: 'utf8'): string }

describe('masthead stylesheet contract', () => {
    let css = '';
    const rule = (selector: string): string => {
        const escaped = selector.replace(/[.*+?^${}()|[\]\\]/gu, '\\$&');
        const found = new RegExp(`(?:^|\\n)${escaped} \\{([^}]*)\\}`, 'u').exec(css);
        expect(found, selector).not.toBeNull();
        return found![1];
    };

    beforeAll(async () => {
        const { readFileSync } = await import('node:fs' as string) as NodeFs;
        const root = (globalThis as unknown as { process: { cwd(): string } }).process.cwd();
        css = readFileSync(`${root}/src/app/core/layout/app-shell.component.css`, 'utf8');
    });

    it('lets the nav and the session wrap inside their grid cells instead of overflowing', () => {
        const nav = rule('.primary-nav');
        expect(nav).toContain('flex-wrap: wrap');
        expect(nav).toContain('min-inline-size: 0');
        expect(css).toMatch(/\.session \{ justify-content: flex-end; flex-wrap: wrap; min-inline-size: 0; \}/u);
    });

    it('keeps the nav links on one line each, so wrapping moves a whole link', () => {
        expect(rule('.primary-nav a')).toContain('white-space: nowrap');
    });

    it('moves the nav to its own row at 56rem, well above the width where one row stops fitting', () => {
        const stacked = /@media \(max-width: ([\d.]+)rem\) \{\s*\.masthead \{\s*display: flex;\s*flex-wrap: wrap;/u.exec(css);
        expect(stacked).not.toBeNull();
        expect(Number(stacked![1])).toBeGreaterThanOrEqual(56);
        const row = /@media \(max-width: [\d.]+rem\) \{[^@]*?\.primary-nav \{\s*order: 3;\s*flex: 1 1 100%;/u.exec(css);
        expect(row).not.toBeNull();
    });

    it('has a three-column masthead for wide screens whose side columns can shrink to the brand and the session', () => {
        expect(css).toMatch(/grid-template-columns: minmax\(11rem, 1fr\) auto minmax\(11rem, 1fr\)/u);
    });
});
