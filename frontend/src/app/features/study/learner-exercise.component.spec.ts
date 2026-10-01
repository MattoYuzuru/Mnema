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
        for (const [key, value] of Object.entries(inputs)) fixture.componentRef.setInput(key, value);
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
        root.querySelector<HTMLButtonElement>('[data-answer-control]')!.click(); fixture.detectChanges();
        expect(root.textContent).toContain('Ядро, митохондрии и рибосомы.');
        root.querySelector<HTMLButtonElement>('[data-first-rating]')!.click();
        expect(answers).toEqual([{ kind: 'SELF_CHECK', rating: 'NOT_RECALLED' }]);
    });

    it('does not offer the transcript of a hidden self-check reference before the answer is shown', () => {
        const { fixture, root } = create('selfCheck');
        expect(root.querySelector('.transcript-offer')).toBeNull();
        root.querySelector<HTMLButtonElement>('[data-answer-control]')!.click(); fixture.detectChanges();
        expect(root.querySelector('.transcript-offer')).not.toBeNull();
    });

    it('does not submit anything in preview mode and says so', () => {
        const { fixture, root, answers, component } = create('selfCheck', { canSubmit: false });
        root.querySelector<HTMLButtonElement>('[data-answer-control]')!.click(); fixture.detectChanges();
        expect(root.querySelector('[data-first-rating]')).toBeNull();
        expect(root.textContent).toContain('В предпросмотре оценка не отправляется');
        component.rate('FULL'); component.submit();
        expect(answers).toEqual([]);
        const free = create('freeResponse', { canSubmit: false });
        expect(free.root.querySelector('button[data-submit]')).toBeNull();
    });

    it('reports whether it holds input, keeps the issued order of choices and ignores an empty choice', () => {
        const { fixture, root, answers, dirty, component } = create('choice');
        component.submit();
        expect(answers).toEqual([]);
        const boxes = root.querySelectorAll<HTMLInputElement>('input[type="checkbox"]');
        boxes[2].click(); boxes[0].click(); fixture.detectChanges();
        expect(dirty.at(-1)).toBeTrue();
        component.submit();
        expect(answers).toEqual([{ kind: 'CHOICE', optionIds: ['dddddddd-dddd-4ddd-8ddd-ddddddddddd1', 'dddddddd-dddd-4ddd-8ddd-ddddddddddd3'] }]);
        boxes[2].click(); boxes[0].click(); fixture.detectChanges();
        expect(dirty.at(-1)).toBeFalse();
    });

    it('discards input and a pending pair check when the reset key changes', () => {
        const pending = new Subject<boolean>();
        const checker = jasmine.createSpy('check').and.returnValue(pending);
        const { fixture, root, component } = create('match', { pairChecker: checker, resetKey: 'one' });
        root.querySelector<HTMLButtonElement>('button[data-side="left"]')!.click(); fixture.detectChanges();
        root.querySelector<HTMLButtonElement>('button[data-side="right"]')!.click(); fixture.detectChanges();
        expect(component.pairChecking()).toBeTrue();
        fixture.componentRef.setInput('resetKey', 'two'); fixture.detectChanges();
        expect(component.pairChecking()).toBeFalse();
        pending.next(true);
        expect(component.matches()).toEqual({});
        expect(component.dirty()).toBeFalse();
    });

    it('shows a retryable error when a pair check fails and refuses a second check while one is running', () => {
        const checker = jasmine.createSpy('check').and.returnValue(throwError(() => new Error('offline')));
        const { fixture, root, component } = create('match', { pairChecker: checker });
        const left = () => root.querySelectorAll<HTMLButtonElement>('button[data-side="left"]');
        const right = () => root.querySelectorAll<HTMLButtonElement>('button[data-side="right"]');
        left()[0].click(); right()[0].click(); fixture.detectChanges();
        expect(root.querySelector('[role="alert"]')?.textContent).toContain('Не удалось проверить пару');
        checker.and.returnValue(of(true));
        right()[0].click(); fixture.detectChanges();
        expect(root.querySelector('[role="alert"]')).toBeNull();
        expect(checker).toHaveBeenCalledTimes(2);
        expect(component.matches()['1e000000-0000-4000-8000-000000000003']).toBe('7e000000-0000-4000-8000-000000000002');
        const none = create('match').component;
        none.checkPair({ leftId: 'x', rightId: 'y' });
        expect(none.pairChecking()).toBeFalse();
    });

    it('offers the transcript button only while a transcript is available and not revealed', () => {
        const first = create('freeResponse');
        const requests: number[] = [];
        first.component.transcriptRequested.subscribe(() => requests.push(1));
        first.root.querySelector<HTMLButtonElement>('.transcript-offer button')!.click();
        expect(requests.length).toBe(1);
        first.fixture.componentRef.setInput('transcriptLoading', true); first.fixture.detectChanges();
        expect(first.root.querySelector<HTMLButtonElement>('.transcript-offer button')?.disabled).toBeTrue();
        first.fixture.componentRef.setInput('transcriptRevealed', true); first.fixture.detectChanges();
        expect(first.root.querySelector('.transcript-offer')).toBeNull();
        expect(create('choice').root.querySelector('.transcript-offer')).toBeNull();
    });

    it('locks the answer surface while busy', () => {
        const { fixture, root, answers, component } = create('freeResponse', { busy: true });
        expect(root.querySelector('textarea')?.readOnly).toBeTrue();
        expect(root.querySelector<HTMLButtonElement>('button[data-submit]')?.disabled).toBeTrue();
        component.submit();
        expect(answers).toEqual([]);
        fixture.componentRef.setInput('busy', false); fixture.detectChanges();
        const area = root.querySelector<HTMLTextAreaElement>('textarea')!;
        area.value = 'x'; area.dispatchEvent(new Event('input'));
        component.submit();
        expect(answers).toEqual([{ kind: 'TEXT', text: 'x' }]);
    });
});
