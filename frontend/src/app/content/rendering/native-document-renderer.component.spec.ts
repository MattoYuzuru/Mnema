import { ComponentFixture, TestBed } from '@angular/core/testing';

import codeDocumentJson from '../../../../../contracts/content/native-v1/valid/code.json';
import mixedDocumentJson from '../../../../../contracts/content/native-v1/valid/mixed.json';
import richDocumentJson from '../../../../../contracts/content/native-v1/valid/rich.json';
import youtubeDocumentJson from '../../../../../contracts/content/native-v1/valid/youtube.json';
import { NativeDocument } from '../native-document';
import { NativeDocumentRendererComponent } from './native-document-renderer.component';
import { NATIVE_RENDER_LIMITS } from './native-render-state';
import { documentOf, mixedNativeDocumentFixture, nativeNode } from './native-renderer.fixtures';

describe('NativeDocumentRendererComponent', () => {
    let fixture: ComponentFixture<NativeDocumentRendererComponent>;

    beforeEach(async () => {
        await TestBed.configureTestingModule({
            imports: [NativeDocumentRendererComponent]
        }).compileComponents();
        fixture = TestBed.createComponent(NativeDocumentRendererComponent);
    });

    it('renders rich blocks semantically without deriving a URL from an asset ID', () => {
        fixture.componentRef.setInput('document', richDocumentJson as unknown as NativeDocument);
        fixture.detectChanges();
        const host = fixture.nativeElement as HTMLElement;

        expect(host.querySelector('figure img.native-image')).toBeNull();
        expect(host.querySelector('audio, video')).toBeNull();
        expect(host.querySelectorAll('.native-media-pending').length).toBe(3);
        expect(host.querySelector('table caption')?.textContent).toBe('Сравнение хранилищ');
        expect(host.querySelectorAll('table thead th[scope="col"]').length).toBe(2);
        expect(host.querySelectorAll('table tbody tr').length).toBe(2);
        expect(host.querySelector('app-native-mermaid figcaption')?.textContent).toContain('Клиент передаёт запрос');
        expect(host.querySelector('script, iframe')).toBeNull();
    });

    it('uses only separately supplied authorized URLs for assets', () => {
        fixture.componentRef.setInput('document', richDocumentJson as unknown as NativeDocument);
        fixture.componentRef.setInput('assetSources', {
            '31901995-16ea-4f8b-8301-5d8e03004c72': 'https://example.test/authorized-image',
            '948ef76d-68ab-4a79-9f83-f3a45ffb3eda': 'https://example.test/authorized-audio'
        });
        fixture.detectChanges();
        const host = fixture.nativeElement as HTMLElement;

        expect(host.querySelector('app-native-media-image .image-open img')?.getAttribute('alt'))
            .toBe('Схема пути запроса');
        expect(host.querySelector('audio')?.getAttribute('src')).toBe('https://example.test/authorized-audio');
        expect(host.querySelector('video')).toBeNull();
    });

    it('keeps a YouTube block inert until a reader opens the provider player', () => {
        fixture.componentRef.setInput('document', youtubeDocumentJson as unknown as NativeDocument);
        fixture.detectChanges();
        const host = fixture.nativeElement as HTMLElement;
        expect(host.querySelector('iframe')).toBeNull();
        expect(host.querySelector('app-native-youtube a')?.getAttribute('href'))
            .toBe('https://www.youtube.com/watch?v=M7lc1UVf-VE');
        (host.querySelector('app-native-youtube button') as HTMLButtonElement).click();
        fixture.detectChanges();
        expect(host.querySelector('iframe')?.getAttribute('src'))
            .toBe('https://www.youtube-nocookie.com/embed/M7lc1UVf-VE');
    });

    it('renders the complete baseline fixture with semantic structure and original metadata', () => {
        fixture.componentRef.setInput('document', mixedNativeDocumentFixture());
        fixture.detectChanges();
        const host = fixture.nativeElement as HTMLElement;

        expect(host.querySelector('[data-native-render-state="ready"]')).not.toBeNull();
        expect(host.querySelector('main')).toBeNull();
        expect(host.querySelector('h1')).toBeNull();

        const heading = host.querySelector('h2');
        expect(heading?.textContent?.trim()).toBe('Память, письмо и проверяемые знания');
        expect(heading?.getAttribute('lang')).toBe('ru');
        expect(heading?.getAttribute('dir')).toBe('ltr');

        const marked = host.querySelector('p[lang="ja"] strong > em > code');
        expect(marked?.textContent?.trim()).toBe('Порядок отметок');
        expect(host.querySelector('ruby')?.textContent).toContain('漢字');
        expect(host.querySelector('ruby rt')?.textContent).toBe('かんじ');

        const rtlParagraph = host.querySelector('p[lang="ar"]');
        expect(rtlParagraph?.getAttribute('dir')).toBe('rtl');
        const link = host.querySelector('a') as HTMLAnchorElement | null;
        expect(host.querySelectorAll('a').length).toBe(1);
        expect(link?.getAttribute('href')).toBe('https://example.test/source');
        expect(link?.textContent?.replace(/\s+/g, ' ').trim()).toBe('مصدر آمن ومتابعة');

        expect(host.querySelector('blockquote p')?.textContent).toContain('Цитата');
        expect(host.querySelector('ul > li')?.textContent).toContain('Первый пункт');
        expect(host.querySelector('ol')?.getAttribute('start')).toBe('7');
        expect(host.querySelector('ol > li')?.textContent).toContain('Седьмой пункт');
        expect(host.querySelector('hr')).not.toBeNull();

        expect(host.querySelectorAll('.native-unsupported:not(.native-unsupported--inline)').length).toBe(2);
        expect(host.querySelector('.native-unsupported--inline')?.textContent).not.toContain('onerror');
        expect(host.querySelector('script, img, iframe, form')).toBeNull();
        expect(host.textContent).not.toContain('сохран');
    });

    it('renders the canonical mixed native-v1 corpus', () => {
        fixture.componentRef.setInput('document', mixedDocumentJson as unknown as NativeDocument);
        fixture.detectChanges();
        const host = fixture.nativeElement as HTMLElement;

        expect(host.querySelector('[data-native-render-state="ready"]')).not.toBeNull();
        expect(host.querySelector('h3')?.textContent).toBe('Память, письмо и проверяемые знания');
        expect(host.querySelectorAll('ul > li').length).toBe(2);
        expect(host.querySelector('ul > li')?.textContent).toBe('Первый пункт');
        expect(host.querySelector('script, img, iframe, form')).toBeNull();
    });

    it('maps document headings beneath the host h1 without collapsing level six', () => {
        fixture.componentRef.setInput('document', documentOf(Array.from({ length: 6 }, (_, index) => nativeNode('heading', { level: index + 1 }, [nativeNode('text', { text: `Level ${index + 1}` })]))));
        fixture.detectChanges();
        const host = fixture.nativeElement as HTMLElement;

        expect(host.querySelector('h2')?.textContent).toContain('Level 1');
        expect(host.querySelector('h3')?.textContent).toContain('Level 2');
        expect(host.querySelector('h4')?.textContent).toContain('Level 3');
        expect(host.querySelector('h5')?.textContent).toContain('Level 4');
        expect(host.querySelector('h6')?.textContent).toContain('Level 5');
        expect(host.querySelector('[role="heading"][aria-level="7"]')?.textContent).toContain('Level 6');
    });

    it('renders HTML-looking text literally and never creates executable elements', () => {
        const payload = '<img src=x onerror="globalThis.pwned=1"><script>globalThis.pwned=2</script>';
        fixture.componentRef.setInput('document', documentOf([
            nativeNode('paragraph', {}, [nativeNode('text', { text: payload })])
        ]));
        fixture.detectChanges();
        const host = fixture.nativeElement as HTMLElement;

        expect(host.querySelector('p')?.textContent?.trim()).toBe(payload);
        expect(host.querySelector('img, script')).toBeNull();
        expect(host.innerHTML).toContain('&lt;img');
    });

    it('fails closed without reflecting an invalid URL or partially rendering content', () => {
        const payload = 'javascript:globalThis.pwned=1';
        fixture.componentRef.setInput('document', documentOf([
            nativeNode('paragraph', {}, [
                nativeNode('link', { href: payload }, [nativeNode('text', { text: 'unsafe' })])
            ])
        ]));
        fixture.detectChanges();
        const host = fixture.nativeElement as HTMLElement;

        expect(host.querySelector('[data-native-render-state="invalid"]')).not.toBeNull();
        expect(host.querySelector('article, a')).toBeNull();
        expect(host.textContent).not.toContain(payload);
    });

    it('shows one fixed placeholder for a future root and hides all descendants', () => {
        const document = documentOf([
            nativeNode('paragraph', {}, [nativeNode('text', { text: 'private future payload' })])
        ], 2);
        fixture.componentRef.setInput('document', document);
        fixture.detectChanges();
        const host = fixture.nativeElement as HTMLElement;

        expect(host.querySelector('[data-native-render-state="unsupported"]')).not.toBeNull();
        expect(host.querySelector('article')).toBeNull();
        expect(host.textContent).not.toContain('private future payload');
        expect(host.textContent).not.toContain('сохран');
    });

    it('preserves exact adjacent text, authored whitespace and ruby base boundaries', () => {
        fixture.componentRef.setInput('document', documentOf([
            nativeNode('paragraph', {}, [
                nativeNode('text', { text: 'a' }),
                nativeNode('text', { text: 'b' })
            ]),
            nativeNode('paragraph', {}, [nativeNode('text', { text: 'one  two\nthree' })]),
            nativeNode('paragraph', {}, [
                nativeNode('ruby', { base: '漢字', reading: 'かんじ' }),
                nativeNode('text', { text: '.' })
            ])
        ]));
        fixture.detectChanges();
        const host = fixture.nativeElement as HTMLElement;
        const paragraphs = host.querySelectorAll('p');
        const ruby = host.querySelector('ruby');

        expect(paragraphs[0]?.textContent).toBe('ab');
        expect(paragraphs[1]?.textContent).toBe('one  two\nthree');
        expect(getComputedStyle(paragraphs[1]!).whiteSpace).toBe('pre-wrap');
        expect(ruby?.firstChild?.textContent).toBe('漢字');
        expect(ruby?.textContent).toBe('漢字(かんじ)');
        expect(paragraphs[2]?.textContent).toBe('漢字(かんじ).');
    });

    it('uses semantic list items for opaque list children and preserves ordered numbering', () => {
        fixture.componentRef.setInput('document', documentOf([
            nativeNode('bullet_list', {}, [nativeNode('future_item', {}, [], { version: 4 })]),
            nativeNode('ordered_list', { order: 7 }, [nativeNode('list_item', {}, [], { version: 2 })])
        ]));
        fixture.detectChanges();
        const host = fixture.nativeElement as HTMLElement;

        expect(host.querySelector('ul > li.native-unsupported')).not.toBeNull();
        const orderedPlaceholder = host.querySelector('ol[start="7"] > li.native-unsupported');
        expect(orderedPlaceholder).not.toBeNull();
        expect(orderedPlaceholder?.getAttribute('role')).toBeNull();
        expect(host.querySelector('ul > div, ol > div')).toBeNull();
        expect(host.textContent).not.toContain('сохран');
    });

    it('keeps allowed links in the native keyboard order with a visible focus target', () => {
        fixture.componentRef.setInput('document', mixedNativeDocumentFixture());
        fixture.detectChanges();
        const link = (fixture.nativeElement as HTMLElement).querySelector('a') as HTMLAnchorElement;

        link.focus();

        expect(document.activeElement).toBe(link);
        expect(link.tabIndex).toBe(0);
        expect(getComputedStyle(link).textDecorationLine).toContain('underline');
    });

    it('keeps the original spelling of an allowed persisted href', () => {
        const hrefs = [
            'HTTPS://example.test/%D1%8F?q=%3Cscript%3E#Source',
            'https://[2001:0db8:0000:0000:0000:ff00:0042:8329]/source',
            'https://[::ffff:192.0.2.128]:443/source',
            'https://example.tld1/source'
        ];
        for (const href of hrefs) {
            fixture.componentRef.setInput('document', documentOf([
                nativeNode('paragraph', {}, [
                    nativeNode('link', { href }, [nativeNode('text', { text: 'Source' })])
                ])
            ]));
            fixture.detectChanges();

            expect((fixture.nativeElement as HTMLElement).querySelector('a')?.getAttribute('href')).toBe(href);
        }
    });

    // Containment of long Russian and unbreakable content at 320 px, 2x root text and 200% zoom is geometry jsdom cannot
    // measure; the browser harness owns it (scripts/browser-identity, scenario mechanics_renderer_reflow).

    // The 10,000-node limit itself is asserted on the render model (native-render-state.spec.ts, `exactCount`).
    // Here the template must render every prepared node; jsdom's DOM insertion grows superlinearly with
    // sibling count (≈40 s locally and >120 s on CI for 10,000), so a large but bounded document is used.
    it('renders every prepared node of a large document without truncation', () => {
        const count = 1_000;
        const document = documentOf(Array.from({ length: count }, () => nativeNode('paragraph')));

        fixture.componentRef.setInput('document', document);
        fixture.detectChanges();

        expect((fixture.nativeElement as HTMLElement).querySelectorAll('p').length).toBe(count);
    }, 20_000); // about 1–2 s alone, but over the default 5 s on a loaded machine (seen 5.08 s while the backend suite ran)

    it('renders code as inert text in a focusable, horizontally scrollable region', () => {
        fixture.componentRef.setInput('document', codeDocumentJson as unknown as NativeDocument);
        fixture.detectChanges();
        const host = fixture.nativeElement as HTMLElement;

        const blocks = Array.from(host.querySelectorAll<HTMLElement>('figure.native-code'));
        expect(blocks.length).toBe(4);
        expect(blocks.every(block => block.getAttribute('dir') === 'ltr')).toBe(true);
        const first = blocks[0]!;
        expect(first.querySelector('figcaption')?.textContent).toBe('sql');
        expect(first.querySelector('pre > code.language-sql')?.textContent)
            .toBe('EXPLAIN (ANALYZE, BUFFERS)\nSELECT *\n  FROM orders\n WHERE customer_id = 42;');
        const region = first.querySelector<HTMLElement>('.native-code-scroll')!;
        expect(region.getAttribute('tabindex')).toBe('0');
        expect(region.getAttribute('role')).toBe('region');
        expect(region.getAttribute('aria-label')).toContain('Код, язык sql');
        expect(blocks[1]!.querySelector('code')?.className).toContain('language-c++');
        expect(blocks[1]!.querySelector('code')?.textContent).toBe('int main() {\n\treturn 0;   \n}\n');
        // no language: no label and no language class, but still a named region
        expect(blocks[2]!.querySelector('figcaption')).toBeNull();
        expect(blocks[2]!.querySelector('code')?.className).not.toContain('language-');
        expect(blocks[2]!.querySelector('.native-code-scroll')?.getAttribute('aria-label')).toBe('Код: прокрутка по горизонтали при необходимости');
        // markup in the source is text, never elements
        expect(blocks[3]!.querySelector('code')?.textContent).toContain('<script>alert(1)</script>');
        expect(host.querySelector('script')).toBeNull();
    });

    it('shows a retained code_block that is not valid version one as an unsupported placeholder', () => {
        fixture.componentRef.setInput('document', documentOf([
            nativeNode('code_block', { language: 'kotlin', source: 'println("secret")', wrap: true })
        ]));
        fixture.detectChanges();
        const host = fixture.nativeElement as HTMLElement;
        expect(host.querySelector('.native-unsupported')?.textContent).toContain('не поддерживается');
        expect(host.textContent).not.toContain('secret');
        expect(host.querySelector('pre')).toBeNull();
    });
});
