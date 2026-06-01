import { scopeAnkiCss } from './anki-css.util';

describe('scopeAnkiCss', () => {
    const scope = '.anki-card.mn-anki-scope-1';

    it('scopes selectors that could otherwise affect the review chrome', () => {
        const css = `
          .progress-bar { height: 24px; }
          header { margin-top: 2rem; font-weight: 800; }
          body { font-size: 22px; }
          .card { color: red; }
          .card1 .term, .term:hover { color: blue; }
        `;

        const scoped = scopeAnkiCss(css, scope);

        expect(scoped).toContain(`${scope} .progress-bar`);
        expect(scoped).toContain(`${scope} header`);
        expect(scoped).toContain(`${scope} { font-size: 22px; }`);
        expect(scoped).toContain(`${scope} { color: red; }`);
        expect(scoped).toContain(`${scope} .term`);
        expect(scoped).toContain(`${scope} .term:hover`);
        expect(scoped).not.toMatch(/(^|[,{]\s*)\.progress-bar\s*\{/);
        expect(scoped).not.toMatch(/(^|[,{]\s*)header\s*\{/);
    });

    it('keeps root card class selectors on the flashcard element', () => {
        const scoped = scopeAnkiCss('.card.nightMode .answer, .card1 #qa { color: white; }', scope);

        expect(scoped).toContain(`${scope}.nightMode .answer`);
        expect(scoped).toContain(`${scope} #qa`);
    });

    it('scopes nested selectors inside media rules', () => {
        const scoped = scopeAnkiCss('@media (max-width: 600px) { .progress-bar, body .answer { height: 1rem; } }', scope);

        expect(scoped).toContain('@media (max-width: 600px)');
        expect(scoped).toContain(`${scope} .progress-bar`);
        expect(scoped).toContain(`${scope} .answer`);
    });

    it('does not rewrite keyframes or font faces', () => {
        const css = `
          @font-face { font-family: Anki; src: url(anki.woff2); }
          @keyframes fade { from { opacity: 0; } to { opacity: 1; } }
          .front { animation: fade 1s; }
        `;

        const scoped = scopeAnkiCss(css, scope);

        expect(scoped).toContain('@font-face { font-family: Anki; src: url(anki.woff2); }');
        expect(scoped).toContain('@keyframes fade { from { opacity: 0; } to { opacity: 1; } }');
        expect(scoped).toContain(`${scope} .front`);
    });

    it('preserves top-level at-rule statements before scoped rules', () => {
        const scoped = scopeAnkiCss('@import url("anki.css");\n.progress-bar { height: 12px; }', scope);

        expect(scoped).toContain('@import url("anki.css");');
        expect(scoped).toContain(`${scope} .progress-bar`);
    });
});
