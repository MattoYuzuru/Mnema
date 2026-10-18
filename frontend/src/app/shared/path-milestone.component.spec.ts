import { TestBed } from '@angular/core/testing';

import { MilestoneKey, MilestoneState } from '../core/onboarding/path.models';
import { PathMilestoneComponent } from './path-milestone.component';

describe('PathMilestoneComponent', () => {
    const render = (inputs: { key: MilestoneKey; state: MilestoneState; progress?: number; target?: number; href?: string | null }) => {
        const fixture = TestBed.createComponent(PathMilestoneComponent);
        for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
        const followed = vi.fn();
        fixture.componentInstance.follow.subscribe(followed);
        fixture.detectChanges();
        const root = fixture.nativeElement as HTMLElement;
        return {
            fixture, root, followed,
            word: (): string => root.querySelector('.word')!.textContent!.trim(),
            title: (): string => root.querySelector('h3')!.textContent!.trim(),
            benefit: (): string => root.querySelector('.benefit')!.textContent!.trim(),
            link: (): HTMLAnchorElement | null => root.querySelector('a.go'),
            bar: (): HTMLElement | null => root.querySelector('[role="progressbar"]')
        };
    };

    it('says «Сделано» in a word and a glyph, with no chevron', () => {
        const done = render({ key: 'GOAL_TOLD', state: 'DONE', progress: 1, target: 1, href: '/profile' });
        expect(done.word()).toBe('Сделано');
        expect(done.title()).toBe('Расскажите о цели');
        expect(done.benefit()).toBe('Подсказки и примеры подстроятся под неё.');
        expect(done.link()).toBeNull();
        expect(done.root.querySelector('svg')?.getAttribute('aria-hidden')).toBe('true');
        expect(done.root.querySelector('.milestone')?.getAttribute('data-state')).toBe('DONE');
        expect(done.bar()).toBeNull();
    });

    it('says «Не начато» for a milestone not yet started, with the chevron named «Перейти: …»', () => {
        const todo = render({ key: 'MEDIA_IN_MATERIAL', state: 'TODO', progress: 0, target: 1, href: '/decks' });
        expect(todo.word()).toBe('Не начато');
        expect(todo.link()?.getAttribute('aria-label')).toBe('Перейти: Добавьте картинку или звук');
        expect(todo.link()?.getAttribute('href')).toBe('/decks');
        expect(todo.bar()).toBeNull();
    });

    it('shows a counted milestone under way as «2 из 3» with a named progress bar that has valuenow, valuemax and a text', () => {
        const partial = render({ key: 'OWN_EXERCISES', state: 'PARTIAL', progress: 2, target: 3, href: '/decks' });
        expect(partial.word()).toBe('2 из 3');
        const bar = partial.bar()!;
        expect(bar.getAttribute('aria-label')).toBe('Создайте три своих упражнения');
        expect(bar.getAttribute('aria-valuenow')).toBe('2');
        expect(bar.getAttribute('aria-valuemax')).toBe('3');
        expect(bar.getAttribute('aria-valuetext')).toBe('2 из 3');
        expect(partial.root.querySelector('.bar small')?.textContent).toBe('2 из 3');
        expect(partial.benefit()).toBe('Проверяйте себя так, как удобно вам.');
        expect(partial.link()).not.toBeNull();
    });

    it('says the stage of «На потом» in place of the benefit, with the word «Начато» and no count', () => {
        const stage = render({ key: 'CAPTURE_NOTE', state: 'PARTIAL', progress: 1, target: 2, href: '/decks' });
        expect(stage.word()).toBe('Начато');
        expect(stage.benefit()).toBe('Заметка сохранена, осталось её разобрать.');
        expect(stage.bar()).toBeNull();
        // Before the first note the ordinary line is shown.
        const todo = render({ key: 'CAPTURE_NOTE', state: 'TODO', progress: 0, target: 2, href: '/decks' });
        expect(todo.benefit()).toBe('Мысль не потеряется: разберёте её, когда будет время.');
    });

    it('says «Начато» for a milestone with one step that is under way and no count (target 1)', () => {
        expect(render({ key: 'GOAL_TOLD', state: 'PARTIAL', progress: 1, target: 1, href: '/profile' }).word()).toBe('Начато');
    });

    it('has no chevron without a place to go', () => {
        const none = render({ key: 'RHYTHM_WEEK', state: 'TODO', progress: 0, target: 1, href: null });
        expect(none.link()).toBeNull();
        expect(none.word()).toBe('Не начато');
    });

    it('turns a plain click on the chevron into `follow`, and leaves a modified click to the browser', () => {
        const todo = render({ key: 'FIRST_DECK', state: 'TODO', progress: 0, target: 1, href: '/decks' });
        const click = new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 });
        todo.link()!.dispatchEvent(click);
        expect(click.defaultPrevented).toBe(true);
        expect(todo.followed).toHaveBeenCalledTimes(1);
        for (const modifier of [{ ctrlKey: true }, { metaKey: true }, { shiftKey: true }, { altKey: true }, { button: 1 }]) {
            const other = new MouseEvent('click', { bubbles: true, cancelable: true, ...modifier });
            todo.link()!.dispatchEvent(other);
            expect(other.defaultPrevented, JSON.stringify(modifier)).toBe(false);
        }
        expect(todo.followed).toHaveBeenCalledTimes(1);
    });

    it('never relies on colour alone: the word is in the text of every state', () => {
        const words = (['DONE', 'PARTIAL', 'TODO'] as const).map(state => render({ key: 'FIRST_DECK', state, progress: state === 'DONE' ? 1 : 0, target: 1, href: '/decks' }).word());
        expect(new Set(words).size).toBe(3);
        expect(words.every(word => word.length > 0)).toBe(true);
    });
});
