import { ComponentFixture, TestBed } from '@angular/core/testing';
import { EventArticleComponent } from './event-article.component';

describe('public event article', () => {
    let fixture: ComponentFixture<EventArticleComponent>;
    const render = (inputs: Record<string, string>): HTMLElement => {
        fixture = TestBed.createComponent(EventArticleComponent);
        for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
        fixture.detectChanges();
        return fixture.nativeElement as HTMLElement;
    };

    it('renders the dated heading and the safe Markdown body, labelled by its own heading', () => {
        const root = render({ eventId: 'e1', title: 'Новый редактор', eventDate: '2026-10-07', markdown: '**важно** и <script>x</script>' });
        expect(root.querySelector('time')?.getAttribute('datetime')).toBe('2026-10-07');
        expect(root.querySelector('time')?.textContent).toContain('2026');
        expect(root.querySelector('article')?.getAttribute('aria-labelledby')).toBe('event-e1');
        expect(root.querySelector('#event-e1')?.textContent).toBe('Новый редактор');
        expect(root.querySelector('strong')?.textContent).toBe('важно');
        expect(root.querySelector('script')).toBeNull();
    });

    it('shows no date text for an unfinished draft instead of failing', () => {
        const root = render({ eventId: 'preview', title: 'Без заголовка', eventDate: '', markdown: 'текст' });
        expect(root.querySelector('time')?.textContent?.trim()).toBe('');
        expect(root.textContent).toContain('текст');
    });
});
