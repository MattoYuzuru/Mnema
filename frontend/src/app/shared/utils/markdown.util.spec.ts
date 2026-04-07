import { markdownToHtml } from './markdown.util';

describe('markdownToHtml', () => {
    it('escapes html before applying markdown transforms', () => {
        const html = markdownToHtml('<script>alert(1)</script> **safe**');

        expect(html).toContain('&lt;script&gt;alert(1)&lt;/script&gt;');
        expect(html).toContain('<strong>safe</strong>');
    });

    it('renders headings, lists and inline code', () => {
        const html = markdownToHtml('# Title\n- item\n- next\n`code`');

        expect(html).toContain('<h1>Title</h1>');
        expect(html).toContain('<ul>');
        expect(html).toContain('<li>item</li>');
        expect(html).toContain('<li>next</li>');
        expect(html).toContain('<code>code</code>');
    });

    it('renders fenced code blocks', () => {
        const html = markdownToHtml('```ts\nconst answer = 42;\n```');

        expect(html).toContain('<pre><code>const answer = 42;</code></pre>');
    });
});
