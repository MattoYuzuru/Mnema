import { ComponentFixture, TestBed } from '@angular/core/testing';

import mixedDocumentJson from '../../../../../contracts/content/native-v1/valid/mixed.json';
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
        fixture.componentRef.setInput('document', documentOf(Array.from({ length: 6 }, (_, index) =>
            nativeNode('heading', { level: index + 1 }, [nativeNode('text', { text: `Level ${index + 1}` })])
        )));
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

    it('contains long Russian and unbreakable content at reader widths, 2x root text, and CSS zoom', () => {
        const frame = document.createElement('div');
        const root = document.documentElement;
        const previousRootFontSize = root.style.fontSize;
        frame.style.position = 'fixed';
        frame.style.inset = '0 auto auto -10000px';
        document.body.appendChild(frame);
        frame.appendChild(fixture.nativeElement);
        fixture.componentRef.setInput('document', documentOf([
            nativeNode('heading', { level: 1, lang: 'ru' }, [
                nativeNode('text', { text: 'Длинный русский заголовок о памяти и осмысленном обучении' })
            ]),
            nativeNode('paragraph', {}, [nativeNode('text', { text: 'A'.repeat(512) })])
        ]));

        try {
            root.style.fontSize = '16px';
            fixture.detectChanges();
            const article = frame.querySelector('article') as HTMLElement;
            const baselineFontSize = Number.parseFloat(getComputedStyle(article).fontSize);

            for (const width of [320, 390, 1440]) {
                frame.style.width = `${width}px`;
                expect(frame.scrollWidth).withContext(`${width}px`).toBeLessThanOrEqual(frame.clientWidth);
            }

            frame.style.width = '320px';
            root.style.fontSize = '32px';
            const doubledFontSize = Number.parseFloat(getComputedStyle(article).fontSize);
            expect(doubledFontSize).toBeGreaterThanOrEqual(baselineFontSize * 1.9);
            expect(frame.scrollWidth).withContext('320px at 2x root text').toBeLessThanOrEqual(frame.clientWidth);

            root.style.fontSize = '16px';
            frame.style.setProperty('zoom', '2');
            const zoomedWidth = frame.getBoundingClientRect().width;
            expect(zoomedWidth).toBeGreaterThanOrEqual(frame.clientWidth * 1.9);
            expect(frame.scrollWidth).withContext('320px at 200% CSS zoom').toBeLessThanOrEqual(frame.clientWidth);
            console.info(
                `[native-renderer] font ${baselineFontSize.toFixed(2)}px -> ${doubledFontSize.toFixed(2)}px; `
                + `2x zoom width ${zoomedWidth.toFixed(2)}px`
            );
        } finally {
            root.style.fontSize = previousRootFontSize;
            frame.remove();
        }
    });

    it('renders a document at the 10,000-node boundary without truncation', () => {
        const document = documentOf(Array.from(
            { length: NATIVE_RENDER_LIMITS.maxNodes - 1 },
            () => nativeNode('paragraph')
        ));
        const started = performance.now();

        fixture.componentRef.setInput('document', document);
        fixture.detectChanges();

        const elapsed = performance.now() - started;
        expect((fixture.nativeElement as HTMLElement).querySelectorAll('p').length)
            .toBe(NATIVE_RENDER_LIMITS.maxNodes - 1);
        console.info(`[native-renderer] 10000-node prepare+DOM ${elapsed.toFixed(2)} ms`);
    });
});
