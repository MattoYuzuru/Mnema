import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { QuestDeckOption, QuestNotice, QuestPhase } from '../core/onboarding/quest.models';
import { QuestChooserComponent } from './quest-chooser.component';

const DECKS: readonly QuestDeckOption[] = [{ deckId: 'a', title: 'Японский', sample: false }, { deckId: 'b', title: 'Учебная колода', sample: true }];

describe('QuestChooserComponent', () => {
    const render = (inputs: { phase: QuestPhase; decks?: readonly QuestDeckOption[]; selected?: string | null; notice?: QuestNotice | null }) => {
        TestBed.resetTestingModule();
        TestBed.configureTestingModule({ providers: [provideRouter([{ path: '**', children: [] }])] });
        const fixture = TestBed.createComponent(QuestChooserComponent);
        fixture.componentRef.setInput('title', 'Ответьте голосом');
        for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
        const outputs = { chosen: vi.fn(), started: vi.fn(), cancelled: vi.fn(), acted: vi.fn(), retried: vi.fn() };
        for (const [name, spy] of Object.entries(outputs)) (fixture.componentInstance as unknown as Record<string, { subscribe(f: unknown): void }>)[name].subscribe(spy);
        fixture.detectChanges();
        const root = fixture.nativeElement as HTMLElement;
        return { fixture, root, outputs, buttons: (): string[] => Array.from(root.querySelectorAll('button, a.button')).map(element => element.textContent!.trim()) };
    };

    it('is a section named by its heading, which takes the focus and is not a tab stop', () => {
        const { fixture, root } = render({ phase: 'loading' });
        const heading = root.querySelector('h2')!;
        expect(root.querySelector('section')?.getAttribute('aria-labelledby')).toBe(heading.id);
        expect(heading.textContent).toBe('Ответьте голосом');
        expect(heading.getAttribute('tabindex')).toBe('-1');
        document.body.append(root);
        fixture.componentInstance.focusHeading();
        expect(document.activeElement).toBe(heading);
        root.remove();
    });

    it('says it is preparing, in a status, with «Не сейчас»', () => {
        const { root, buttons } = render({ phase: 'loading' });
        expect(root.querySelector('[role="status"]')?.textContent).toBe('Готовим задание…');
        expect(buttons()).toEqual(['Не сейчас']);
        expect(render({ phase: 'checking' }).root.querySelector('[role="status"]')).not.toBeNull();
    });

    it('lists the decks as a group of radios, the sample copy stamped, and chooses and starts', () => {
        const { root, outputs, buttons } = render({ phase: 'choose', decks: DECKS, selected: 'a' });
        expect(root.querySelector('p')?.textContent).toBe('Выберите колоду, в которой пройдём это задание.');
        expect(root.querySelector('fieldset legend')?.textContent).toBe('Колода');
        const radios = Array.from(root.querySelectorAll<HTMLInputElement>('input[type="radio"]'));
        expect(radios.map(radio => [radio.value, radio.checked])).toEqual([['a', true], ['b', false]]);
        expect(new Set(radios.map(radio => radio.name)).size).toBe(1);
        expect(root.querySelectorAll('.stamp')).toHaveLength(1);
        expect(root.querySelector('.stamp')?.textContent).toBe('Учебная');
        radios[1].click();
        expect(outputs.chosen).toHaveBeenCalledWith('b');
        expect(buttons()).toEqual(['Не сейчас', 'Начать']);
        (root.querySelector('button.primary') as HTMLButtonElement).click();
        expect(outputs.started).toHaveBeenCalledTimes(1);
    });

    it('cannot start with nothing chosen, and names a sample deck without a title by its stamp', () => {
        const { root } = render({ phase: 'choose', decks: [{ deckId: 'b', title: '', sample: true }], selected: null });
        expect((root.querySelector('button.primary') as HTMLButtonElement).disabled).toBe(true);
        expect(root.querySelector('.name')?.textContent?.trim()).toBe('Учебная');
    });

    it('says why the quest cannot start and what to do instead: the sample deck as a button, a link as a link', () => {
        const { root, outputs, buttons } = render({ phase: 'blocked', notice: { text: 'Лимит ИИ исчерпан.', actions: [
            { kind: 'sample', label: 'Пройти в учебной колоде', deckId: 'b' }, { kind: 'link', label: 'Тарифы', commands: ['/plans'] }] } });
        expect(root.querySelector('p')?.textContent).toBe('Лимит ИИ исчерпан.');
        expect(buttons()).toEqual(['Не сейчас', 'Пройти в учебной колоде', 'Тарифы']);
        expect(root.querySelector('a.button')?.getAttribute('href')).toBe('/plans');
        (root.querySelector('button.primary') as HTMLButtonElement).click();
        expect(outputs.acted).toHaveBeenCalledWith({ kind: 'sample', label: 'Пройти в учебной колоде', deckId: 'b' });
        (root.querySelector('a.button') as HTMLAnchorElement).dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
        expect(outputs.acted).toHaveBeenCalledTimes(2);
    });

    it('always has «Не сейчас»: a refusal with no way on is not a dead end', () => {
        const { buttons, root, outputs } = render({ phase: 'blocked', notice: { text: 'Недоступно.', actions: [] } });
        expect(buttons()).toEqual(['Не сейчас']);
        (root.querySelector('button') as HTMLButtonElement).click();
        expect(outputs.cancelled).toHaveBeenCalledTimes(1);
    });

    it('says a failure in an alert with «Повторить»', () => {
        const { root, outputs, buttons } = render({ phase: 'error' });
        expect(root.querySelector('[role="alert"]')?.textContent).toBe('Не удалось подготовить задание. Попробуйте ещё раз.');
        expect(buttons()).toEqual(['Не сейчас', 'Повторить']);
        (root.querySelector('button.primary') as HTMLButtonElement).click();
        expect(outputs.retried).toHaveBeenCalledTimes(1);
    });
});
