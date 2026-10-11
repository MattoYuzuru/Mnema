import { TestBed } from '@angular/core/testing';

import { ModerationNoticeComponent } from './moderation-notice.component';

describe('ModerationNoticeComponent', () => {
    function render(inputs: { hiddenFromCommunity?: boolean; materials?: number; exercises?: number }): HTMLElement {
        const fixture = TestBed.createComponent(ModerationNoticeComponent);
        for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
        fixture.detectChanges();
        return fixture.nativeElement as HTMLElement;
    }
    const lines = (root: HTMLElement): string[] => [...root.querySelectorAll('.what, .more')].map(line => line.textContent!.replace(/\s+/g, ' ').trim());

    it('draws nothing for a deck nobody complained about', () => {
        expect(render({}).querySelector('section')).toBeNull();
    });

    it('says the deck is hidden from the Community, in words, under its own heading', () => {
        const root = render({ hiddenFromCommunity: true });
        expect(root.querySelector('section.notice.warning h2')?.textContent).toBe('Модерация');
        expect(root.querySelector('section')!.getAttribute('aria-labelledby')).toBe(root.querySelector('h2')!.id);
        expect(lines(root)[0]).toBe('Колода скрыта из Сообщества');
        expect(lines(root)[1]).toContain('Вам она доступна как раньше');
        expect(root.textContent).not.toContain('Материалы сняты');
    });

    it('names the counts of the materials and exercises taken down, with the right plural', () => {
        expect(lines(render({ materials: 1, exercises: 0 }))[1]).toContain('Снято: 1 материал.');
        expect(lines(render({ materials: 2, exercises: 1 }))[1]).toContain('Снято: 2 материала и 1 упражнение.');
        expect(lines(render({ materials: 0, exercises: 5 }))[1]).toContain('Снято: 5 упражнений.');
        expect(lines(render({ materials: 11, exercises: 22 }))[1]).toContain('Снято: 11 материалов и 22 упражнения.');
        expect(lines(render({ materials: 1 }))[0]).toBe('Материалы сняты по жалобе');
    });

    it('says what the owner can do with each kind', () => {
        expect(lines(render({ materials: 1 }))[1]).toContain('Материал можно заменить или удалить.');
        expect(lines(render({ exercises: 1 }))[1]).toContain('Упражнение можно удалить или создать заново.');
        expect(lines(render({ materials: 1, exercises: 1 }))[1]).toContain('Материал можно заменить или удалить, упражнение удалить или создать заново.');
    });

    it('shows both states together and never mentions the complaint or who made it', () => {
        const root = render({ hiddenFromCommunity: true, materials: 2, exercises: 1 });
        expect(lines(root).filter((_, index) => index % 2 === 0)).toEqual(['Колода скрыта из Сообщества', 'Материалы сняты по жалобе']);
        expect(root.textContent).not.toMatch(/пожаловал|жалобщик|автор жалобы/iu);
    });

    it('moves the focus to the heading for the notification link', () => {
        const fixture = TestBed.createComponent(ModerationNoticeComponent);
        fixture.componentRef.setInput('hiddenFromCommunity', true);
        fixture.detectChanges();
        document.body.append(fixture.nativeElement as HTMLElement);
        expect(fixture.componentInstance.focusHeading()).toBe(true);
        expect(document.activeElement?.tagName).toBe('H2');
        (fixture.nativeElement as HTMLElement).remove();
        const clean = TestBed.createComponent(ModerationNoticeComponent);
        clean.detectChanges();
        expect(clean.componentInstance.focusHeading()).toBe(false);
    });
});
