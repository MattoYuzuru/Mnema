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
        expect(html).toContain('<code class="mn-inline-code">');
        expect(html).toContain('>code<');
    });

    it('renders fenced code blocks with language metadata and syntax tokens', () => {
        const html = markdownToHtml('```ts\nconst answer = 42;\n```');

        expect(html).toContain('<pre class="mn-code-block mn-code--typescript" data-language="typescript">');
        expect(html).toContain('mn-code-token--keyword');
        expect(html).toContain('mn-code-token--number');
        expect(html).toContain('const');
        expect(html).toContain('42');
    });

    it('does not apply text emphasis inside fenced code blocks', () => {
        const html = markdownToHtml('```ts\nconst value = "**literal**";\n```');

        expect(html).not.toContain('<strong>literal</strong>');
        expect(html).toContain('**literal**');
    });

    it('highlights json properties and values', () => {
        const html = markdownToHtml('```json\n{"title":"Mnema","count":2,"published":true}\n```');

        expect(html).toContain('mn-code-token--property');
        expect(html).toContain('mn-code-token--string');
        expect(html).toContain('mn-code-token--number');
        expect(html).toContain('mn-code-token--boolean');
    });
});
