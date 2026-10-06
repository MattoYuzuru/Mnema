import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';

import { LearnerContent } from '../../content/exercise/exercise-content.models';
import { MEDIA_PLAYBACK_RESOLVER } from './media-playback-resolver';
import { LearnerExerciseComponent } from './learner-exercise.component';
import { StudyResponse } from './study.models';
import { clone, fakePlayback, mechanics } from './study-test-data';

describe('LearnerExerciseComponent', () => {
    const presentations = mechanics['presentations'];
    const learner = (name: string): LearnerContent => ({ type: presentations[name].type, content: clone(presentations[name].content) });

    beforeEach(() => TestBed.configureTestingModule({ providers: [{ provide: MEDIA_PLAYBACK_RESOLVER, useValue: { resolve: fakePlayback } }] }));

    function create(name: string, inputs: Record<string, unknown> = {}) {
        const fixture = TestBed.createComponent(LearnerExerciseComponent);
        fixture.componentRef.setInput('exercise', learner(name));
        for (const [key, value] of Object.entries(inputs))
            fixture.componentRef.setInput(key, value);
        const answers: StudyResponse[] = [];
        const dirty: boolean[] = [];
        fixture.componentInstance.answered.subscribe(value => answers.push(value));
        fixture.componentInstance.dirtyChange.subscribe(value => dirty.push(value));
        fixture.detectChanges();
        return { fixture, root: fixture.nativeElement as HTMLElement, answers, dirty, component: fixture.componentInstance };
    }

    it('shows the self-check reference only after the explicit reveal and emits the chosen rating', () => {
        const { fixture, root, answers, component } = create('selfCheck');
        expect(root.textContent).not.toContain('Ядро, митохондрии и рибосомы.');
        component.rate('FULL');
        expect(answers).toEqual([]);
        root.querySelector<HTMLButtonElement>('[data-answer-control]')!.click();
        fixture.detectChanges();
        expect(root.textContent).toContain('Ядро, митохондрии и рибосомы.');
        root.querySelector<HTMLButtonElement>('[data-first-rating]')!.click();
        expect(answers).toEqual([{ kind: 'SELF_CHECK', rating: 'NOT_RECALLED' }]);
    });

    it('does not offer the transcript of a hidden self-check reference before the answer is shown', () => {
        const { fixture, root } = create('selfCheck');
        expect(root.querySelector('.transcript-offer')).toBeNull();
        root.querySelector<HTMLButtonElement>('[data-answer-control]')!.click();
        fixture.detectChanges();
        expect(root.querySelector('.transcript-offer')).not.toBeNull();
    });

    it('disables every submit and rating control with the reason while an author draft is unfinished', () => {
        const reason = 'Проверить ответ пока нельзя: Добавьте вопрос.';
        const { fixture, root, answers, component } = create('selfCheck', { blockedReason: reason });
        root.querySelector<HTMLButtonElement>('[data-answer-control]')!.click();
        fixture.detectChanges();
        expect(root.querySelector('fieldset')?.disabled).toBe(true);
        expect(root.querySelector('.blocked')?.textContent).toContain(reason);
        component.rate('FULL');
        component.submit();
        expect(answers).toEqual([]);
        const free = create('freeResponse', { blockedReason: reason });
        const submit = free.root.querySelector<HTMLButtonElement>('button[data-submit]')!;
        expect(submit.disabled).toBe(true);
        expect(submit.getAttribute('aria-describedby')).toBe(free.root.querySelector('.blocked')!.id);
        free.component.submit();
        expect(free.answers).toEqual([]);
        const choice = create('choice', { blockedReason: reason });
        choice.root.querySelector<HTMLInputElement>('input[type="checkbox"]')!.click();
        choice.fixture.detectChanges();
        expect(choice.root.querySelector<HTMLButtonElement>('button[data-submit]')!.disabled).toBe(true);
    });

    it('reports whether it holds input, keeps the issued order of choices and ignores an empty choice', () => {
        const { fixture, root, answers, dirty, component } = create('choice');
        component.submit();
        expect(answers).toEqual([]);
        const boxes = root.querySelectorAll<HTMLInputElement>('input[type="checkbox"]');
        boxes[2].click();
        boxes[0].click();
        fixture.detectChanges();
        expect(dirty.at(-1)).toBe(true);
        component.submit();
        expect(answers).toEqual([{ kind: 'CHOICE', optionIds: ['dddddddd-dddd-4ddd-8ddd-ddddddddddd1', 'dddddddd-dddd-4ddd-8ddd-ddddddddddd3'] }]);
        boxes[2].click();
        boxes[0].click();
        fixture.detectChanges();
        expect(dirty.at(-1)).toBe(false);
    });

    it('discards input and a pending pair check when the reset key changes', () => {
        const pending = new Subject<boolean>();
        const checker = vi.fn().mockName('check').mockReturnValue(pending);
        const { fixture, root, component } = create('match', { pairChecker: checker, resetKey: 'one' });
        root.querySelector<HTMLButtonElement>('button[data-side="left"]')!.click();
        fixture.detectChanges();
        root.querySelector<HTMLButtonElement>('button[data-side="right"]')!.click();
        fixture.detectChanges();
        expect(component.pairChecking()).toBe(true);
        fixture.componentRef.setInput('resetKey', 'two');
        fixture.detectChanges();
        expect(component.pairChecking()).toBe(false);
        pending.next(true);
        expect(component.matches()).toEqual({});
        expect(component.dirty()).toBe(false);
    });

    it('shows a retryable error when a pair check fails and refuses a second check while one is running', () => {
        const checker = vi.fn().mockName('check').mockReturnValue(throwError(() => new Error('offline')));
        const { fixture, root, component } = create('match', { pairChecker: checker });
        const left = () => root.querySelectorAll<HTMLButtonElement>('button[data-side="left"]');
        const right = () => root.querySelectorAll<HTMLButtonElement>('button[data-side="right"]');
        left()[0].click();
        right()[0].click();
        fixture.detectChanges();
        expect(root.querySelector('[role="alert"]')?.textContent).toContain('Не удалось проверить пару');
        checker.mockReturnValue(of(true));
        right()[0].click();
        fixture.detectChanges();
        expect(root.querySelector('[role="alert"]')).toBeNull();
        expect(checker).toHaveBeenCalledTimes(2);
        expect(component.matches()['1e000000-0000-4000-8000-000000000003']).toBe('7e000000-0000-4000-8000-000000000002');
        const none = create('match').component;
        none.checkPair({ leftId: 'x', rightId: 'y' });
        expect(none.pairChecking()).toBe(false);
    });

    it('offers the transcript button only while a transcript is available and not revealed', () => {
        const first = create('freeResponse');
        const requests: number[] = [];
        first.component.transcriptRequested.subscribe(() => requests.push(1));
        first.root.querySelector<HTMLButtonElement>('.transcript-offer button')!.click();
        expect(requests.length).toBe(1);
        first.fixture.componentRef.setInput('transcriptLoading', true);
        first.fixture.detectChanges();
        expect(first.root.querySelector<HTMLButtonElement>('.transcript-offer button')?.disabled).toBe(true);
        first.fixture.componentRef.setInput('transcriptRevealed', true);
        first.fixture.detectChanges();
        expect(first.root.querySelector('.transcript-offer')).toBeNull();
        expect(create('choice').root.querySelector('.transcript-offer')).toBeNull();
    });

    it('locks the answer surface while busy', () => {
        const { fixture, root, answers, component } = create('freeResponse', { busy: true });
        expect(root.querySelector('textarea')?.readOnly).toBe(true);
        expect(root.querySelector<HTMLButtonElement>('button[data-submit]')?.disabled).toBe(true);
        component.submit();
        expect(answers).toEqual([]);
        fixture.componentRef.setInput('busy', false);
        fixture.detectChanges();
        const area = root.querySelector<HTMLTextAreaElement>('textarea')!;
        area.value = 'x';
        area.dispatchEvent(new Event('input'));
        component.submit();
        expect(answers).toEqual([{ kind: 'TEXT', text: 'x' }]);
    });

    describe('voice answer (answerSource)', () => {
        const speechExercise = (responseInput: 'TEXT' | 'TEXT_OR_SPEECH'): LearnerContent => {
            const base = learner('freeResponse');
            return { ...base, content: { ...base.content, responseInput } } as LearnerContent;
        };
        beforeEach(() => TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] }));
        const open = (responseInput: 'TEXT' | 'TEXT_OR_SPEECH', speechAvailable: boolean) => {
            const made = create('freeResponse', { speechAvailable });
            made.fixture.componentRef.setInput('exercise', speechExercise(responseInput));
            made.fixture.detectChanges();
            return made;
        };
        const type = (root: HTMLElement, value: string) => {
            const area = root.querySelector<HTMLTextAreaElement>('textarea')!;
            area.value = value;
            area.dispatchEvent(new Event('input'));
        };

        it('offers «Ответить голосом» only for TEXT_OR_SPEECH while speech-to-text is available', () => {
            expect(open('TEXT_OR_SPEECH', true).root.querySelector('app-mic-button')).not.toBeNull();
            expect(open('TEXT_OR_SPEECH', true).root.textContent).toContain('Ответить голосом');
            expect(open('TEXT_OR_SPEECH', false).root.querySelector('app-mic-button')).toBeNull();
            expect(open('TEXT', true).root.querySelector('app-mic-button')).toBeNull();
        });

        it('sends TYPED for a typed answer of a TEXT_OR_SPEECH exercise', () => {
            const { component, root, answers } = open('TEXT_OR_SPEECH', true);
            type(root, 'ответ');
            component.submit();
            expect(answers).toEqual([{ kind: 'TEXT', text: 'ответ', answerSource: 'TYPED' }]);
        });

        it('sends SPEECH once a transcript was inserted, even after the learner edited it, and starts over when emptied', () => {
            const { component, root, answers } = open('TEXT_OR_SPEECH', true);
            type(root, 'распознанный текст');
            component.fromSpeech.set(true);
            type(root, 'распознанный текст, поправленный');
            component.submit();
            expect(answers).toEqual([{ kind: 'TEXT', text: 'распознанный текст, поправленный', answerSource: 'SPEECH' }]);
            type(root, '');
            expect(component.fromSpeech()).toBe(false);
            type(root, 'набрано руками');
            component.submit();
            expect(answers[1]).toEqual({ kind: 'TEXT', text: 'набрано руками', answerSource: 'TYPED' });
        });

        it('never sends answerSource for a TEXT exercise', () => {
            const { component, root, answers } = open('TEXT', true);
            type(root, 'x');
            component.submit();
            expect(answers).toEqual([{ kind: 'TEXT', text: 'x' }]);
        });

        it('forgets the transcript origin with the rest of the input when the presentation changes', () => {
            const { fixture, component } = open('TEXT_OR_SPEECH', true);
            component.fromSpeech.set(true);
            fixture.componentRef.setInput('resetKey', 'another');
            fixture.detectChanges();
            expect(component.fromSpeech()).toBe(false);
        });
    });

    describe('ORDER', () => {
        const ids = (...numbers: number[]) => numbers.map(n => `0d000000-0000-4000-8000-${n.toString().padStart(12, '0')}`);
        const order = (root: HTMLElement) => [...root.querySelectorAll('li.order-item')].map(row => row.getAttribute('data-item-id'));
        const move = (root: HTMLElement, itemId: string, kind: 'up' | 'down') => root.querySelector<HTMLButtonElement>(`[data-item-id="${itemId}"] [data-move="${kind}"]`)!.click();

        it('starts in the issued order and submits exactly the order on screen, without a client-side verdict', () => {
            const { fixture, root, answers, component } = create('order');
            expect(order(root)).toEqual(ids(4, 6, 2, 1, 5, 3));
            expect(root.querySelector<HTMLButtonElement>('button[data-submit]')?.textContent).toContain('Проверить порядок');
            move(root, ids(1)[0], 'up');
            fixture.detectChanges();
            move(root, ids(1)[0], 'up');
            fixture.detectChanges();
            expect(order(root)).toEqual(ids(4, 1, 6, 2, 5, 3));
            component.submit();
            expect(answers).toEqual([{ kind: 'ORDER', sequence: ids(4, 1, 6, 2, 5, 3) }]);
        });

        it('keeps the learner order when the same presentation is rendered again, but not for another presentation', () => {
            const { fixture, root, component } = create('order');
            move(root, ids(3)[0], 'up');
            fixture.detectChanges();
            const moved = order(root);
            fixture.componentRef.setInput('exercise', learner('order'));
            fixture.detectChanges();
            fixture.componentRef.setInput('hints', {});
            fixture.detectChanges();
            expect(order(root)).toEqual(moved);
            expect(component.orderMoved()).toBe(true);
            fixture.componentRef.setInput('resetKey', 'next');
            fixture.detectChanges();
            fixture.componentRef.setInput('resetKey', 'other');
            fixture.detectChanges();
            expect(order(root)).toEqual(ids(4, 6, 2, 1, 5, 3));
            expect(component.orderMoved()).toBe(false);
            // A different set of items is never mixed with a stale order.
            move(root, ids(3)[0], 'up');
            fixture.detectChanges();
            const full = learner('order');
            const other: LearnerContent = full.type === 'ORDER' ? { type: 'ORDER', content: { ...full.content, items: full.content.items.slice(0, 5) } } : full;
            fixture.componentRef.setInput('exercise', other);
            fixture.detectChanges();
            expect(order(root)).toEqual(ids(4, 6, 2, 1, 5));
        });

        it('reports a moved order as input and an order moved back as none', () => {
            const { fixture, root, dirty } = create('order');
            move(root, ids(6)[0], 'up');
            fixture.detectChanges();
            expect(dirty.at(-1)).toBe(true);
            move(root, ids(6)[0], 'down');
            fixture.detectChanges();
            expect(dirty.at(-1)).toBe(false);
        });

        it('is locked while busy and while an author draft is unfinished', () => {
            const { fixture, root, answers, component } = create('order', { blockedReason: 'Проверить ответ пока нельзя: Добавьте элемент.' });
            expect(root.querySelector<HTMLButtonElement>('button[data-submit]')?.disabled).toBe(true);
            component.submit();
            expect(answers).toEqual([]);
            fixture.componentRef.setInput('blockedReason', null);
            fixture.componentRef.setInput('busy', true);
            fixture.detectChanges();
            expect([...root.querySelectorAll<HTMLButtonElement>('button[data-move]')].every(button => button.disabled)).toBe(true);
            component.submit();
            expect(answers).toEqual([]);
        });
    });

    describe('CATEGORIZE', () => {
        const itemIds = [1, 2, 3, 4].map(n => `9a000000-0000-4000-8000-${n.toString().padStart(12, '0')}`);
        const groupIds = [1, 2, 3].map(n => `ca000000-0000-4000-8000-${n.toString().padStart(12, '0')}`);
        const put = (fixture: {
            detectChanges(): void;
        }, root: HTMLElement, itemId: string, groupId: string) => {
            root.querySelector<HTMLButtonElement>(`[data-item-id="${itemId}"] [data-select]`)!.click();
            fixture.detectChanges();
            root.querySelector<HTMLButtonElement>(`[data-category="${groupId}"] [data-place]`)!.click();
            fixture.detectChanges();
        };

        it('cannot be submitted until every item has a group, then submits the issued item order with the chosen groups', () => {
            const { fixture, root, answers, component } = create('categorize');
            const submit = () => root.querySelector<HTMLButtonElement>('button[data-submit]')!;
            expect(submit().disabled).toBe(true);
            put(fixture, root, itemIds[0], groupIds[0]);
            put(fixture, root, itemIds[1], groupIds[1]);
            put(fixture, root, itemIds[2], groupIds[0]);
            expect(submit().disabled).toBe(true);
            component.submit();
            expect(answers).toEqual([]);
            put(fixture, root, itemIds[3], groupIds[2]);
            expect(submit().disabled).toBe(false);
            // Change of mind before submit: the last choice counts.
            put(fixture, root, itemIds[3], groupIds[1]);
            submit().click();
            expect(answers).toEqual([{ kind: 'CATEGORIZE', assignments: [
                        { itemId: itemIds[2], categoryId: groupIds[0] }, { itemId: itemIds[3], categoryId: groupIds[1] },
                        { itemId: itemIds[0], categoryId: groupIds[0] }, { itemId: itemIds[1], categoryId: groupIds[1] }
                    ] }]);
            // The presentation lists items as issued (река, запись, дом, бежать), not in the key order.
            expect(root.querySelector('[data-item-id]')?.getAttribute('data-item-id')).toBe(itemIds[2]);
        });

        it('hands the keyboard learner to the submit button after the last assignment', async () => {
            const { fixture, root, component } = create('categorize');
            document.body.appendChild(root);
            try {
                const board = root.querySelector('app-categorize-board')!;
                const instance = fixture.debugElement.query(element => element.name === 'app-categorize-board').componentInstance as {
                    keyboard: boolean;
                };
                instance.keyboard = true;
                for (const [item, group] of [[0, 0], [1, 1], [2, 0], [3, 2]])
                    put(fixture, root, itemIds[item], groupIds[group]);
                await fixture.whenStable();
                expect(board).not.toBeNull();
                expect(document.activeElement).toBe(root.querySelector('button[data-submit]'));
                expect(component.categorizeComplete()).toBe(true);
            }
            finally {
                root.remove();
            }
        });

        it('discards the assignments when the reset key changes and reports them as input before', () => {
            const { fixture, root, dirty, component } = create('categorize', { resetKey: 'one' });
            put(fixture, root, itemIds[0], groupIds[0]);
            expect(dirty.at(-1)).toBe(true);
            fixture.componentRef.setInput('resetKey', 'two');
            fixture.detectChanges();
            expect(component.assignments()).toEqual({});
            expect(dirty.at(-1)).toBe(false);
        });
    });
});
