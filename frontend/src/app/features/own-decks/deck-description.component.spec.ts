import { renderDeckDescription } from './deck-description.component';

describe('deck description Markdown', () => {
    it('renders the supported marks and both list styles', () => {
        expect(renderDeckDescription('**Важное** и *новое* ~~старое~~\n\n- один\n- два\n\n1. шаг'))
            .toBe('<p><strong>Важное</strong> и <em>новое</em> <del>старое</del></p>'
                + '<ul><li>один</li><li>два</li></ul><ol><li>шаг</li></ol>');
    });

    it('escapes untrusted HTML and does not turn URLs into active links', () => {
        const html = renderDeckDescription('<img src=x onerror=alert(1)> **https://example.com**');
        expect(html).toContain('&lt;img');
        expect(html).not.toContain('<img');
        expect(html).not.toContain('<a');
    });
});
