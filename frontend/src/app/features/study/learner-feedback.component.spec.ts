import { TestBed } from '@angular/core/testing';

import { LearnerContent } from '../../content/exercise/exercise-content.models';
import { LearnerFeedbackComponent, feedbackTitle } from './learner-feedback.component';
import { MEDIA_PLAYBACK_RESOLVER } from './media-playback-resolver';
import { AttemptFeedback, StudyResponse } from './study.models';
import { assessment, clone, fakePlayback, mechanics } from './study-test-data';

describe('LearnerFeedbackComponent', () => {
    const presentations = mechanics['presentations'];
    const learner = (name: string): LearnerContent => ({ type: presentations[name].type, content: clone(presentations[name].content) });
    const feedback = (name: string): AttemptFeedback => clone(mechanics['feedback'][name]);

    beforeEach(() => TestBed.configureTestingModule({ providers: [{ provide: MEDIA_PLAYBACK_RESOLVER, useValue: { resolve: fakePlayback } }] }));

    function create(content: LearnerContent, result: AttemptFeedback, submitted: StudyResponse | null) {
        const fixture = TestBed.createComponent(LearnerFeedbackComponent);
        fixture.componentRef.setInput('content', content);
        fixture.componentRef.setInput('feedback', result);
        fixture.componentRef.setInput('submitted', submitted);
        fixture.detectChanges();
        return fixture.nativeElement as HTMLElement;
    }

    it('titles every verdict in plain words', () => {
        expect(feedbackTitle({ result: 'CORRECT', appliedRules: [] })).toBe('Верно');
        expect(feedbackTitle({ result: 'PARTIAL', appliedRules: [] })).toBe('Частично');
        expect(feedbackTitle({ result: 'INCORRECT', appliedRules: [] })).toBe('Нужно повторить');
        expect(feedbackTitle({ result: 'UNSURE', appliedRules: [] })).toBe('Неуверенно');
        expect(feedbackTitle({ result: 'NOT_ASSESSED', reasonCodes: [] })).toBe('Без оценки');
        expect(feedbackTitle({ result: 'UNAVAILABLE', reasonCodes: [] })).toBe('Проверка недоступна');
    });

    it('shows the self-check rating and the free-response answer next to the reference', () => {
        const rating = create(learner('selfCheck'), feedback('selfCheck'), { kind: 'SELF_CHECK', rating: 'PARTIAL' });
        expect(rating.textContent).toContain('Вспомнил частично');
        const free = create(learner('freeResponse'), feedback('freeResponse'), { kind: 'TEXT', text: 'мой ответ' });
        expect(free.querySelector('.comparison')?.textContent).toContain('мой ответ');
        expect(free.querySelector('.comparison')?.textContent).toContain('Эталон');
    });

    it('marks cloze blanks, correct options and every match pair from the result', () => {
        const cloze = create(learner('cloze'), feedback('cloze'), { kind: 'CLOZE', blanks: [] });
        expect(cloze.querySelectorAll('.cloze-verdict').length).toBeGreaterThan(0);
        const choice = create(learner('choice'), feedback('choice'), { kind: 'CHOICE', optionIds: [] });
        expect(choice.textContent).toContain('(правильный ответ)');
        const match = create(learner('match'), feedback('match'), null);
        expect(match.querySelectorAll('.pair-feedback li').length).toBeGreaterThanOrEqual(2);
    });

    it('explains an unavailable check without blaming the learner and notes a retried pair', () => {
        const root = create(learner('selfCheck'), { result: 'UNAVAILABLE', reasonCodes: ['EVALUATOR_UNAVAILABLE'] }, null);
        expect(root.querySelector('.notice')?.textContent).toContain('Проверка сейчас недоступна');
        const media = create(learner('selfCheck'), { result: 'NOT_ASSESSED', reasonCodes: ['MEDIA_NOT_READY'] }, null);
        expect(media.querySelector('.notice')?.textContent).toContain('Запись стала недоступна');
        const retry = create(learner('match'), { result: 'PARTIAL', appliedRules: ['PAIR_RETRY'], pairs: [] }, null);
        expect(retry.textContent).toContain('Все пары найдены');
    });

    it('shows the correct sequence and a mark for every position of an ORDER answer, with a text mark besides the glyph', () => {
        const root = create(learner('order'), feedback('order'), null);
        const marks = [...root.querySelectorAll('.positions li')];
        expect(marks.length).toBe(6);
        expect(marks.map(row => row.classList.contains('is-wrong'))).toEqual([false, false, false, false, true, true]);
        expect(marks[0].querySelector('.mark')?.textContent).toContain('Верно');
        expect(marks[4].querySelector('.mark')?.textContent).toContain('Не на своём месте');
        expect(marks[1].textContent).toContain('очень');
        const correct = [...root.querySelectorAll('.correct-sequence li')].map(row => row.textContent?.replace(/\s+/g, ' ').trim());
        expect(correct.length).toBe(6);
        expect(correct[0]).toBe('Это');
        expect(correct[4]).toContain('for (int i = 0; i < n; i++) {');
        expect(root.querySelector('.correct-sequence img, .correct-sequence app-learner-media')).not.toBeNull(); // the image frame is shown again
    });

    it('treats swapped identical tiles as right: nothing is marked wrong when the server says so', () => {
        const root = create(learner('order'), feedback('orderEquivalent'), null);
        expect(root.querySelectorAll('.positions li.is-wrong').length).toBe(0);
    });

    it('shows every CATEGORIZE item with the chosen and the correct group, naming the correct one only for a mistake', () => {
        const root = create(learner('categorize'), feedback('categorize'), null);
        const rows = [...root.querySelectorAll('.pair-feedback li')];
        expect(rows.length).toBe(4);
        const wrong = rows.filter(row => row.classList.contains('is-wrong'));
        expect(wrong.length).toBe(1);
        expect(wrong[0].textContent).toContain('река');
        expect(wrong[0].textContent).toContain('Ваша группа: Наречие');
        expect(wrong[0].textContent).toContain('Правильная группа: Существительное');
        expect(rows.filter(row => row.textContent?.includes('Правильная группа')).length).toBe(1);
        expect(rows.some(row => row.querySelector('app-learner-media'))).toBe(true); // the audio item can be heard again
    });

    describe('AI assessment (#292)', () => {
        const free = (): LearnerContent => learner('freeResponse');
        const graded = (name: string): AttemptFeedback => clone(assessment[name].feedback);

        it('titles a model grade with the judgement in words and a disputed one with what happened', () => {
            expect(feedbackTitle(graded('resultComplete'))).toBe('Засчитано');
            expect(feedbackTitle(graded('resultPartialStrict'))).toBe('Частично');
            expect(feedbackTitle(graded('resultOffTopic'))).toBe('Пока не засчитано');
            expect(feedbackTitle(graded('disputeOutcome'))).toBe('Оценка снята');
            expect(feedbackTitle(graded('selfRatingOutcome'))).toBe('Частично');
        });

        it('shows covered and missing points beside the answer and the reference, in that order', () => {
            const root = create(free(), graded('resultComplete'), { kind: 'TEXT', text: 'мой ответ' });
            const order = [...root.querySelectorAll('app-assessment-result h3, .comparison')].map(node => node.id || node.className);
            expect(order).toEqual(['assessment-covered-title', 'assessment-missing-title', 'comparison']);
            expect(root.querySelector('.comparison')?.textContent).toContain('мой ответ');
        });

        it('keeps the reference of a disputed grade for comparing and says nothing about an unavailable check', () => {
            const root = create(free(), graded('disputeOutcome'), { kind: 'TEXT', text: 'мой ответ' });
            expect(root.querySelector('.comparison')?.textContent).toContain('Инерция — свойство тела');
            expect(root.querySelector('.notice')).toBeNull();
            expect(root.querySelector('app-assessment-result')).toBeNull();
        });

        it('tells an author that the preview does not run the AI, for a free response only', () => {
            const unavailable: AttemptFeedback = { result: 'UNAVAILABLE', reasonCodes: ['EVALUATOR_UNAVAILABLE'] };
            expect(create(free(), unavailable, { kind: 'TEXT', text: 'x' }).querySelector('.notice')?.textContent).toContain('В предпросмотре ИИ не проверяет ответ');
            expect(create(learner('selfCheck'), unavailable, null).querySelector('.notice')?.textContent).toContain('Проверка сейчас недоступна');
        });
    });
});
