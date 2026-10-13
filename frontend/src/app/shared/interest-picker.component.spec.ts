import { TestBed } from '@angular/core/testing';
import { InterestPickerComponent } from './interest-picker.component';
describe('InterestPickerComponent', () => {
    it('keeps native checkbox semantics, an optional skip and a maximum of 20 choices', () => {
        const fixture = TestBed.createComponent(InterestPickerComponent);
        const topics = Array.from({ length: 21 }, (_, i) => ({ id: `topic-${i}`, name: `Тема ${i}`, parentId: null }));
        fixture.componentRef.setInput('topics', topics); fixture.componentRef.setInput('selected', topics.slice(0,20).map(t => t.id)); fixture.componentRef.setInput('skippable', true);
        fixture.detectChanges(); const root = fixture.nativeElement as HTMLElement;
        expect(root.querySelector('legend')?.textContent).toBe('Что хотите изучать?');
        const boxes = topics.map(topic => [...root.querySelectorAll<HTMLLabelElement>('label')].find(label => label.textContent === topic.name)!.querySelector<HTMLInputElement>('input')!); expect(boxes).toHaveLength(21);
        expect(boxes[20].disabled).toBe(true); expect(boxes[0].disabled).toBe(false);
        const changed = vi.fn(); fixture.componentInstance.changed.subscribe(changed);
        boxes[0].checked = false; boxes[0].dispatchEvent(new Event('change')); expect(changed.mock.calls[0][0]).toHaveLength(19);
        const save = vi.fn(); fixture.componentInstance.save.subscribe(save);
        [...root.querySelectorAll<HTMLButtonElement>('button')].find(b => b.textContent === 'Пока пропустить')!.click(); expect(save).toHaveBeenCalledWith([]);
        fixture.componentRef.setInput('busy', true); fixture.detectChanges(); expect(root.querySelector('fieldset')?.disabled).toBe(true);
    });
    it('keeps keyboard focus when a selected disclosed topic moves into the visible shortlist', async () => {
        const fixture = TestBed.createComponent(InterestPickerComponent);
        const topics = Array.from({ length: 21 }, (_, i) => ({ id: `topic-${i}`, name: `Тема ${i}`, parentId: 'one' }));
        fixture.componentRef.setInput('topics', topics); fixture.componentRef.setInput('selected', []); fixture.detectChanges();
        fixture.componentInstance.changed.subscribe(selected => fixture.componentRef.setInput('selected', selected));
        const root = fixture.nativeElement as HTMLElement; root.querySelector('details')!.open = true;
        const input = root.querySelector<HTMLInputElement>('[data-interest-topic="topic-20"]')!; input.focus(); input.checked = true; input.dispatchEvent(new Event('change'));
        fixture.detectChanges(); await fixture.whenStable(); fixture.detectChanges();
        expect(document.activeElement).toBe(root.querySelector('[data-interest-topic="topic-20"]'));
        expect(root.querySelector('fieldset > .topics')?.textContent).toContain('Тема 20');
    });
    it('shows a short cross-topic first choice and discloses the rest while preserving already selected topics', () => {
        const fixture = TestBed.createComponent(InterestPickerComponent);
        const topics = Array.from({ length: 65 }, (_, i) => ({ id: `topic-${i}`, name: `Тема ${i}`, parentId: `parent-${Math.floor(i / 10)}` }));
        fixture.componentRef.setInput('topics', topics); fixture.componentRef.setInput('selected', ['topic-64']); fixture.componentRef.setInput('skippable', true); fixture.detectChanges();
        const root = fixture.nativeElement as HTMLElement;
        expect(root.querySelectorAll('fieldset > .topics input')).toHaveLength(13);
        expect(root.querySelector('summary')?.textContent).toBe('Все темы (65)'); expect(root.querySelector('details')?.open).toBe(false);
        expect(root.querySelector('fieldset > .topics')?.textContent).toContain('Тема 10'); expect(root.querySelector('fieldset > .topics')?.textContent).toContain('Тема 64');
        expect(root.querySelectorAll('.actions button')).toHaveLength(2);
    });
});
