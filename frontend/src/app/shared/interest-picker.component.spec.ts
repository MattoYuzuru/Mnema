import { TestBed } from '@angular/core/testing';
import { InterestPickerComponent } from './interest-picker.component';
import { DEMO_INTEREST_TOPICS } from '../styleguide/sg-interest-topics-demo';
import { CommunityTopic } from '../features/community/community.models';

describe('InterestPickerComponent', () => {
    function create(selected: readonly string[] = [], topics: readonly CommunityTopic[] = DEMO_INTEREST_TOPICS) {
        const fixture = TestBed.createComponent(InterestPickerComponent);
        fixture.componentRef.setInput('topics', topics);
        fixture.componentRef.setInput('selected', selected);
        fixture.componentRef.setInput('skippable', true);
        fixture.componentInstance.changed.subscribe(selected => fixture.componentRef.setInput('selected', selected));
        fixture.detectChanges();
        const root = fixture.nativeElement as HTMLElement;
        const topic = (id: string) => root.querySelector<HTMLButtonElement>(`[data-interest-topic="${id}"]`)!;
        const category = (id: string) => root.querySelector<HTMLButtonElement>(`[data-interest-category="${id}"]`)!;
        return { fixture, root, topic, category };
    }

    it('starts with broad directions and reveals a short group without saving a parent id', () => {
        const { fixture, root, category } = create();
        const changed = vi.fn(); fixture.componentInstance.changed.subscribe(changed);
        expect(root.querySelector('legend')?.textContent).toBe('Что вы хотите изучать в Мнеме?');
        expect(root.querySelectorAll('[data-interest-category]')).toHaveLength(9);
        expect(root.querySelectorAll('.branches [data-interest-topic]')).toHaveLength(0);
        expect(root.querySelector('input, summary')).toBeNull();
        expect(root.textContent).not.toContain('0 из 20');
        category('languages').click(); fixture.detectChanges();
        expect(category('languages').getAttribute('aria-expanded')).toBe('true');
        expect(root.querySelectorAll('.branches [data-interest-topic]')).toHaveLength(5);
        expect(root.querySelector('.branches')?.textContent).toContain('Английский');
        expect(changed).not.toHaveBeenCalled();
    });

    it('reveals related topics after choosing one, preserves its selected button and keyboard focus', async () => {
        const { fixture, root, topic, category } = create();
        category('languages').click(); fixture.detectChanges();
        topic('english').focus(); topic('english').click(); fixture.detectChanges();
        await fixture.whenStable(); fixture.detectChanges();
        expect(root.querySelector('.chosen [data-interest-topic="english"]')?.getAttribute('aria-pressed')).toBe('true');
        expect(document.activeElement).toBe(topic('english'));
        expect(root.querySelectorAll('.branches [data-interest-topic]')).toHaveLength(5);
        expect(root.querySelector('.branch-heading')?.textContent).toContain('Английский');
        category('medicine').click(); fixture.detectChanges();
        expect(root.querySelector('.chosen')?.textContent).toContain('Английский');
        topic('english').focus(); topic('english').click(); fixture.detectChanges(); await fixture.whenStable();
        expect(root.querySelector('.chosen')).toBeNull();
        expect(document.activeElement).toBe(category('languages'));
    });

    it('makes every existing leaf reachable without exposing one long list', () => {
        const { fixture, root, category } = create();
        category('languages').click(); fixture.detectChanges();
        const seen = new Set<string>();
        for (let i = 0; i < 3; i++) {
            root.querySelectorAll<HTMLElement>('.branches [data-interest-topic]').forEach(button => seen.add(button.dataset['interestTopic']!));
            root.querySelector<HTMLButtonElement>('.more')!.click(); fixture.detectChanges();
        }
        expect([...seen].sort()).toEqual(DEMO_INTEREST_TOPICS.filter(topic => topic.parentId === 'languages').map(topic => topic.id).sort());
    });

    it('keeps selected topics removable at the limit, disables new choices and explains recovery', () => {
        const { fixture, root, topic, category } = create(DEMO_INTEREST_TOPICS.slice(0, 20).map(topic => topic.id));
        category('it').click(); fixture.detectChanges();
        expect(topic('programming').disabled).toBe(true);
        expect(topic('english').disabled).toBe(false);
        const description = document.getElementById(topic('programming').getAttribute('aria-describedby')!);
        expect(description?.textContent).toContain('Уберите одну');
        topic('english').click(); fixture.detectChanges();
        expect(topic('programming').disabled).toBe(false);
        expect(root.querySelector('.chosen [data-interest-topic="english"]')).toBeNull();
        const save = vi.fn(); fixture.componentInstance.save.subscribe(save);
        [...root.querySelectorAll<HTMLButtonElement>('.actions button')].find(button => button.textContent === 'Пока пропустить')!.click();
        expect(save).toHaveBeenCalledWith([]);
        fixture.componentRef.setInput('busy', true); fixture.detectChanges();
        expect(root.querySelector('fieldset')?.disabled).toBe(true);
    });

    it('supports a leaf without a parent and does not replace focus moved by the reader', async () => {
        const { fixture, root, topic } = create();
        topic('other').click(); fixture.detectChanges();
        expect(root.querySelector('.chosen [data-interest-topic="other"]')?.getAttribute('aria-pressed')).toBe('true');
        topic('other').focus(); topic('other').click();
        const save = root.querySelector<HTMLButtonElement>('.actions button')!; save.focus();
        fixture.detectChanges(); await fixture.whenStable();
        expect(document.activeElement).toBe(save);
    });
});
