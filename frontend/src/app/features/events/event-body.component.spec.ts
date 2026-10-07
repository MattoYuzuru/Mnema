import { TestBed } from '@angular/core/testing';
import { EventBodyComponent } from './event-body.component';
import { eventMarkdown } from './event-markdown';

describe('event Markdown', () => {
    it('renders the supported editorial subset with semantic HTML', async () => {
        const fixture = TestBed.createComponent(EventBodyComponent);
        fixture.componentRef.setInput('markdown', '# Подробности\n\nАбзац с **важным**, *курсивом* и `кодом`.\n\n- Первый\n- Второй\n\n> Цитата\n\n```\n<plain>\n```\n\n[Ссылка](https://mnema.app/events)');
        fixture.detectChanges(); await fixture.whenStable();
        const root: HTMLElement = fixture.nativeElement;
        expect(root.querySelector('h3')?.textContent).toContain('Подробности');
        expect(root.querySelector('strong')?.textContent).toBe('важным');
        expect(root.querySelector('em')?.textContent).toBe('курсивом');
        expect(root.querySelectorAll('li').length).toBe(2);
        expect(root.querySelector('blockquote')?.textContent).toContain('Цитата');
        expect(root.querySelector('pre')?.textContent).toBe('<plain>');
        expect(root.querySelector('a')?.getAttribute('href')).toBe('https://mnema.app/events');
    });

    it('keeps HTML and unsafe destinations inert, including protocol-relative and credential URLs', () => {
        const fixture = TestBed.createComponent(EventBodyComponent);
        fixture.componentRef.setInput('markdown', '<img src=x onerror=alert(1)>\n\n[x](javascript:alert) [y](data:text/html,x) [z](//evil.example) [q](https://user:pass@evil.example) [good](/events)');
        fixture.detectChanges();
        const root: HTMLElement = fixture.nativeElement;
        expect(root.querySelector('img, script, iframe')).toBeNull();
        expect(root.textContent).toContain('<img src=x onerror=alert(1)>');
        expect(root.querySelectorAll('a').length).toBe(1);
        expect(root.querySelector('a')?.getAttribute('href')).toBe('/events');
    });

    it('bounds oversized input and keeps an unfinished code fence as text', () => {
        const blocks = eventMarkdown('```\n' + 'x'.repeat(20000));
        expect(blocks[0].kind).toBe('code');
        expect(blocks[0].lines[0][0].text.length).toBeLessThanOrEqual(16000);
        expect(eventMarkdown('first\nsecond\n\n- item\nparagraph').map(block => block.kind)).toEqual(['paragraph', 'list', 'paragraph']);
    });
});
