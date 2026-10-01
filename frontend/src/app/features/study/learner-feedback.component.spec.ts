import { TestBed } from '@angular/core/testing';

import { LearnerContent } from '../../content/exercise/exercise-content.models';
import { LearnerFeedbackComponent, feedbackTitle } from './learner-feedback.component';
import { MEDIA_PLAYBACK_RESOLVER } from './media-playback-resolver';
import { AttemptFeedback, StudyResponse } from './study.models';
import { clone, fakePlayback, mechanics } from './study-test-data';

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
});
