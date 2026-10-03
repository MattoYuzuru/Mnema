import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';

import metadataFixture from '../../../../../contracts/decks/metadata.json';
import { QuietZone } from '../../core/notifications/quiet-zone';
import { OwnDecksApiService } from '../own-decks/own-decks-api.service';
import { OwnDeck } from '../own-decks/own-deck.models';
import { StudyApiService } from './study-api.service';
import { AttemptCommand, AttemptOutcome, AttemptState, ReadyStudySession, StudyPresentation } from './study.models';
import { StudyRecoveryService } from './study-recovery.service';
import { StudySessionPageComponent } from './study-session-page.component';
import { MEDIA_PLAYBACK_RESOLVER } from './media-playback-resolver';
import { assessment, clone, fakePlayback, ids } from './study-test-data';
import { spyObj, type SpyObj, lastCall } from '../../../testing/mocks';

/** The learner's side of an `ai-semantic` answer: waiting, «Оценить себя», the graded result, self-check mode and dispute. */
describe('StudySessionPageComponent, AI assessment', () => {
    const deck = metadataFixture.detail as unknown as OwnDeck;
    const sessionId = ids.sessionId;
    const attemptId = assessment['accepted'].attemptId as string;
    const answerText = 'Тело продолжает двигаться с той же скоростью, пока на него что-то не подействует';
    let api: SpyObj<StudyApiService>;
    let recovery: SpyObj<StudyRecoveryService>;
    let fixture: ComponentFixture<StudySessionPageComponent>;
    let confirm: ReturnType<typeof vi.spyOn>;

    const presentation = (): Record<string, unknown> => {
        const value = clone(assessment['presentationInFlight']);
        delete value.assessment;
        return value;
    };
    const resultOf = (name: string): AttemptOutcome => ({ ...clone(assessment[name]), canonicalEffects: assessment[name].mode === 'SCHEDULED', disputed: false });
    const polled = (name: string): AttemptState => clone(assessment[name]);

    beforeEach(() => {
        vi.useFakeTimers();
        const decks = { detail: vi.fn().mockName('OwnDecksApiService.detail') };
        decks.detail.mockReturnValue(of(deck));
        api = spyObj<StudyApiService>({
            start: vi.fn().mockName('start'), read: vi.fn().mockName('read'), refill: vi.fn().mockName('refill'),
            submit: vi.fn().mockName('submit'), progress: vi.fn().mockName('progress'), replaySources: vi.fn().mockName('replaySources'),
            restart: vi.fn().mockName('restart'), revealTranscript: vi.fn().mockName('revealTranscript'),
            checkPair: vi.fn().mockName('checkPair'), hint: vi.fn().mockName('hint'), attempt: vi.fn().mockName('attempt'),
            selfCheck: vi.fn().mockName('selfCheck'), selfRate: vi.fn().mockName('selfRate'), dispute: vi.fn().mockName('dispute')
        });
        api.progress.mockReturnValue(of({ asOf: '2026-10-01T10:00:00Z', items: [], nextCursor: null }));
        api.replaySources.mockReturnValue(of({ asOf: '2026-10-01T10:00:00Z', localStudyDate: '2026-10-01', items: [] }));
        recovery = spyObj<StudyRecoveryService>({
            restore: vi.fn().mockName('restore'), save: vi.fn().mockName('save'), clear: vi.fn().mockName('clear'), now: vi.fn().mockName('now')
        });
        recovery.restore.mockReturnValue(null);
        recovery.now.mockImplementation(() => Date.now());
        const router = { navigate: vi.fn().mockName('navigate') };
        router.navigate.mockResolvedValue(true);
        confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
        TestBed.configureTestingModule({ providers: [
            { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ deckId: deck.deckId }) } } },
            { provide: Router, useValue: router }, { provide: OwnDecksApiService, useValue: decks },
            { provide: StudyApiService, useValue: api }, { provide: StudyRecoveryService, useValue: recovery },
            { provide: MEDIA_PLAYBACK_RESOLVER, useValue: { resolve: fakePlayback } }
        ] });
    });
    afterEach(() => vi.useRealTimers());

    const page = () => fixture.nativeElement as HTMLElement;
    const refresh = () => fixture.detectChanges();
    const tick = (ms: number) => { vi.advanceTimersByTime(ms); refresh(); };
    const button = (label: string) => [...page().querySelectorAll<HTMLButtonElement>('button')].find(candidate => candidate.textContent?.trim() === label);
    const click = (label: string) => {
        const target = button(label);
        if (target === undefined) throw new Error(`Missing button ${label}`);
        target.click();
        refresh();
    };

    function sessionOf(presentations: readonly unknown[]): ReadyStudySession {
        return { sessionId, deckId: deck.deckId, mode: 'SCHEDULED', status: 'ACTIVE', timezone: 'Europe/Moscow',
            localStudyDate: '2026-10-01', deckRevisionId: '33333333-3333-4333-8333-333333333333',
            exerciseGenerationId: '99999999-9999-4999-8999-999999999993', selectionPolicyVersion: 'deck-due-new-v2',
            budget: { maxPresentations: 20, maxNewObjectives: 5 }, issuedCount: presentations.length,
            reducer: { id: 'mnema-baseline', version: '1', configId: ids.reducerConfigId, configHash: `sha256:${'a'.repeat(64)}` },
            seed: '42', nextCursor: null, expiresAt: '2026-10-02T10:00:00Z',
            presentations: clone(presentations) as StudyPresentation[] };
    }

    /** Opens a session whose only card is an AI-checked explanation and answers it. */
    function answer(submitted: AttemptState = polled('accepted')): void {
        api.start.mockReturnValue(of({ value: sessionOf([presentation()]), replayed: false }));
        api.submit.mockReturnValue(of({ value: submitted, replayed: false }));
        fixture = TestBed.createComponent(StudySessionPageComponent);
        refresh();
        fixture.componentInstance.startScheduled('STANDARD');
        refresh();
        const field = page().querySelector<HTMLTextAreaElement>('textarea')!;
        field.value = answerText;
        field.dispatchEvent(new Event('input'));
        refresh();
        click('Проверить ответ');
    }

    const text = () => page().textContent!.replace(/\s+/g, ' ');

    it('shows a polite waiting card with the answer kept, read-only, and keeps the command for a reload', async () => {
        answer();
        expect(lastCall(api.submit)[2].response).toEqual({ kind: 'TEXT', text: answerText });
        expect(Object.keys(lastCall(api.submit)[2].response)).toEqual(['kind', 'text']);
        const title = page().querySelector<HTMLElement>('#assessing-title')!;
        expect(title.textContent).toBe('Мнема проверяет ответ…');
        expect(page().querySelector('app-assessment-waiting [role="status"]')?.textContent).toContain('Обычно это несколько секунд');
        expect(page().querySelector('app-assessment-waiting .answer-text')?.textContent).toBe(answerText);
        expect(page().querySelector('textarea')).toBeNull();
        expect(button('Оценить себя')).toBeUndefined();
        expect(button('Проверить ответ')).toBeUndefined();
        await Promise.resolve();
        expect(lastCall(recovery.save)[0].pending).not.toBeNull();
        expect(page().textContent).not.toContain('Инерция —');
    });

    it('offers «Оценить себя» only after five seconds of waiting, and polling follows the server delay', () => {
        api.attempt.mockReturnValue(of(polled('polledAssessing')));
        answer();
        tick(700);
        expect(api.attempt).toHaveBeenCalledTimes(1);
        tick(1500);
        expect(api.attempt).toHaveBeenCalledTimes(2);
        tick(2700);
        expect(button('Оценить себя')).toBeUndefined();
        tick(100);
        const offer = button('Оценить себя')!;
        expect(offer).toBeDefined();
        expect(page().querySelector('app-assessment-waiting [role="status"]')?.textContent).toContain('дольше обычного');
        expect(offer.closest('.offer')?.textContent).toContain('Можно не ждать');
        expect(offer.classList.contains('primary')).toBe(false);
    });

    it('turns «Оценить себя» into the self-check mode: why, the answer, the reference, the points and the four ratings', () => {
        api.attempt.mockReturnValue(of(polled('polledAssessing')));
        answer();
        tick(5000);
        const view = clone(assessment['polledSelfCheck']);
        view.reason = 'LEARNER_CHOICE';
        api.selfCheck.mockReturnValue(of(view));
        click('Оценить себя');
        expect(api.selfCheck).toHaveBeenCalledWith(deck.deckId, sessionId, attemptId);
        expect(page().querySelector('#self-check-title')?.textContent).toBe('Сверьтесь с эталоном');
        expect(text()).toContain('Вы решили оценить себя сами');
        expect(page().querySelector('app-assessment-self-check .comparison')?.textContent).toContain(answerText);
        expect(page().querySelector('app-assessment-self-check .comparison')?.textContent).toContain('Инерция — свойство тела сохранять скорость');
        expect([...page().querySelectorAll('app-assessment-self-check .points li')].map(item => item.textContent)).toEqual(
            view.selfCheck.criteria.map((point: { description: string }) => point.description));
        expect([...page().querySelectorAll('app-assessment-self-check .ratings button')].map(item => item.textContent?.trim())).toEqual(
            ['Не вспомнил', 'Вспомнил с подсказкой', 'Вспомнил частично', 'Вспомнил полностью']);
        expect(page().querySelector('app-assessment-waiting')).toBeNull();
        const polls = api.attempt.mock.calls.length;
        tick(60_000);
        expect(api.attempt).toHaveBeenCalledTimes(polls);
    });

    it('completes the same attempt with the learner rating and shows the reference without an AI result', () => {
        answer(polled('selfCheckUsageLimit'));
        expect(text()).toContain('проверки ответов с ИИ закончились');
        api.selfRate.mockReturnValue(of({ value: resultOf('selfRatingOutcome'), replayed: false }));
        click('Вспомнил частично');
        expect(api.selfRate).toHaveBeenCalledWith(deck.deckId, sessionId, attemptId, 'PARTIAL');
        expect(page().querySelector('#feedback-title')?.textContent).toBe('Частично');
        expect(page().querySelector('app-assessment-result')).toBeNull();
        expect(page().querySelector('.comparison')?.textContent).toContain(answerText);
        expect(button('Оспорить оценку')).toBeUndefined();
        expect(lastCall(recovery.save)[0].pending).toBeNull();
    });

    it('explains every reason in words and never shows provider internals', () => {
        const reasons: Record<string, string> = { PROVIDER_UNCERTAIN: 'не уверена', PROVIDER_UNAVAILABLE: 'Проверка сейчас недоступна',
            USAGE_LIMIT: 'закончились', CAPABILITY_UNAVAILABLE: 'отключена', DEADLINE: 'слишком много времени', BUSY: 'уже проверяет несколько' };
        for (const [reason, words] of Object.entries(reasons)) {
            answer({ ...polled('polledSelfCheck'), reason } as AttemptState);
            expect(text(), reason).toContain(words);
            expect(text()).not.toMatch(/DeepSeek|GigaChat|модель вернула|провайдер|timeout/i);
            expect(text()).not.toContain(reason);
            fixture.destroy();
        }
    });

    it('shows the graded result in words: what is there, with the learner own quote, what is missing, the reference and the next-time note', () => {
        api.attempt.mockReturnValue(of(resultOf('resultComplete')));
        answer();
        tick(700);
        expect(page().querySelector('#feedback-title')?.textContent).toBe('Засчитано');
        const covered = [...page().querySelectorAll('app-assessment-result section:nth-of-type(1) li')];
        expect(page().querySelector('app-assessment-result h3#assessment-covered-title')?.textContent).toBe('Есть');
        expect(covered.length).toBe(2);
        expect(covered[0].textContent).toContain('Вы написали:');
        expect(covered[0].querySelector('q')?.textContent).toBe('продолжает двигаться с той же скоростью');
        expect(covered[1].textContent).toContain('сказано не до конца');
        expect(page().querySelector('app-assessment-result h3#assessment-missing-title')?.textContent).toBe('Не хватает');
        expect(page().querySelector('app-assessment-result .missing')?.textContent).toContain('Приводит следствие или пример');
        expect(page().querySelector('app-assessment-result [data-next-stricter]')?.textContent).toContain('В следующий раз я попрошу точнее:');
        expect(page().querySelector('.comparison')?.textContent).toContain('Эталон');
        expect(page().querySelector('.comparison')?.textContent).toContain('Инерция — свойство тела сохранять скорость');
        expect(page().querySelector('.progress-note')?.textContent).toBeTruthy();
        expect(button('Оценить себя')).toBeUndefined();
        expect(button('Оспорить оценку')).toBeDefined();
        expect(lastCall(recovery.save)[0].pending).toBeNull();
        // Every mark has words beside it, not only a colour or a glyph.
        for (const mark of page().querySelectorAll('app-assessment-result .mark')) expect(mark.getAttribute('aria-hidden')).toBe('true');
    });

    it('titles a partial and an insufficient grade, and lists nothing as present for an off-topic answer', () => {
        api.attempt.mockReturnValue(of(resultOf('resultPartialStrict')));
        answer();
        tick(700);
        expect(page().querySelector('#feedback-title')?.textContent).toBe('Частично');
        expect(page().querySelector('app-assessment-result [data-next-stricter]')).toBeNull();
        fixture.destroy();
        api.attempt.mockReturnValue(of(resultOf('resultOffTopic')));
        answer();
        tick(700);
        expect(page().querySelector('#feedback-title')?.textContent).toBe('Пока не засчитано');
        expect(page().querySelector('#assessment-covered-title')).toBeNull();
        expect(page().querySelector('#assessment-missing-title')).not.toBeNull();
    });

    describe('«Оспорить оценку»', () => {
        function graded(): void {
            api.attempt.mockReturnValue(of(resultOf('resultComplete')));
            answer();
            tick(700);
        }

        it('asks inline, without a modal confirm, and can be cancelled', () => {
            graded();
            click('Оспорить оценку');
            expect(confirm).not.toHaveBeenCalled();
            const group = page().querySelector('.dispute')!;
            expect(group.getAttribute('role')).toBe('group');
            expect(group.textContent).toContain('Снять эту оценку?');
            expect(group.textContent).toContain('вернётся к состоянию до ответа');
            expect(button('Оспорить оценку')).toBeUndefined();
            click('Не снимать');
            expect(page().querySelector('.dispute')).toBeNull();
            expect(button('Оспорить оценку')).toBeDefined();
            expect(api.dispute).not.toHaveBeenCalled();
        });

        it('takes the grade back with one command id, keeps the answer and the reference, and says the progress did not change', () => {
            graded();
            api.dispute.mockReturnValue(of({ value: { ...resultOf('disputeOutcome'), disputed: true }, replayed: false }));
            click('Оспорить оценку');
            click('Да, снять оценку');
            const [deckId, session, attempt, commandId] = lastCall(api.dispute);
            expect([deckId, session, attempt]).toEqual([deck.deckId, sessionId, attemptId]);
            expect(commandId).toMatch(/^[0-9a-f-]{36}$/u);
            expect(page().querySelector('#feedback-title')?.textContent).toBe('Оценка снята');
            expect(page().querySelector('.progress-note')?.textContent).toBe('Оценка снята, прогресс не изменился.');
            expect(page().querySelector('.comparison')?.textContent).toContain(answerText);
            expect(page().querySelector('.comparison')?.textContent).toContain('Инерция — свойство тела сохранять скорость');
            expect(page().querySelector('app-assessment-result')).toBeNull();
            expect(button('Оспорить оценку')).toBeUndefined();
            expect(page().querySelector('.dispute')).toBeNull();
            expect(page().querySelector('app-learner-feedback .notice')).toBeNull();
            expect(button('Продолжить')).toBeDefined();
        });

        it('keeps the question and retries with the same command id when the request fails', () => {
            graded();
            api.dispute.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 500 })));
            click('Оспорить оценку');
            click('Да, снять оценку');
            const first = lastCall(api.dispute)[3];
            expect(page().querySelector('[role="alert"]')?.textContent).toContain('Не удалось снять оценку');
            expect(page().querySelector('.dispute')).not.toBeNull();
            api.dispute.mockReturnValueOnce(of({ value: { ...resultOf('disputeOutcome'), disputed: true }, replayed: true }));
            click('Да, снять оценку');
            expect(lastCall(api.dispute)[3]).toBe(first);
            expect(page().querySelector('#feedback-title')?.textContent).toBe('Оценка снята');
        });

        it('withdraws the offer when the server says the grade can no longer be taken back', () => {
            graded();
            api.dispute.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 409, error: { code: 'DISPUTE_NOT_ALLOWED' } })));
            click('Оспорить оценку');
            click('Да, снять оценку');
            expect(page().querySelector('[role="alert"]')?.textContent).toContain('уже нельзя снять');
            expect(button('Оспорить оценку')).toBeUndefined();
            expect(page().querySelector('#feedback-title')?.textContent).toBe('Засчитано');
        });
    });

    describe('after a reload', () => {
        const command = (): AttemptCommand => ({ attemptId, presentationId: assessment['presentationInFlight'].presentationId, nonce: 'c3R1ZHktbm9uY2UtdjY',
            response: { kind: 'TEXT', text: answerText }, confidence: null, durationMs: 21000 });

        function reload(pending: AttemptCommand | null): void {
            recovery.restore.mockReturnValue({ deckId: deck.deckId, sessionId, pending });
            api.read.mockReturnValue(of(sessionOf([clone(assessment['presentationInFlight'])])));
            fixture = TestBed.createComponent(StudySessionPageComponent);
            refresh();
        }

        it('resumes the answer in assessment with its text instead of asking again, and shows the result', () => {
            api.attempt.mockReturnValue(of(resultOf('resultComplete')));
            reload(command());
            expect(page().querySelector('#assessing-title')).not.toBeNull();
            expect(page().querySelector('app-assessment-waiting .answer-text')?.textContent).toBe(answerText);
            expect(page().querySelector('textarea')).toBeNull();
            expect(api.submit).not.toHaveBeenCalled();
            tick(0);
            expect(api.attempt).toHaveBeenCalledWith(deck.deckId, sessionId, attemptId);
            expect(page().querySelector('#feedback-title')?.textContent).toBe('Засчитано');
            expect(page().querySelector('.comparison')?.textContent).toContain(answerText);
        });

        it('says honestly that the text is not in this tab when only the server knows the answer, and still offers the way out', () => {
            api.attempt.mockReturnValue(of(polled('polledAssessing')));
            reload(null);
            expect(page().querySelector('app-assessment-waiting .answer-text')?.textContent).toContain('Его текст не сохранился в этой вкладке');
            tick(5000);
            expect(button('Оценить себя')).toBeDefined();
        });

        it('shows the self-check view of an answer the server already moved to self-check', () => {
            api.attempt.mockReturnValue(of(polled('polledSelfCheck')));
            reload(command());
            tick(0);
            expect(page().querySelector('#self-check-title')).not.toBeNull();
            expect(text()).toContain('не уверена');
        });

        it('does not treat an answer in assessment as a lost reply', () => {
            api.attempt.mockReturnValue(new Subject<AttemptState>());
            reload(command());
            expect(page().querySelector('.notice.error')).toBeNull();
            expect(text()).not.toContain('Результат предыдущего ответа пока неизвестен');
        });
    });

    it('releases the quiet zone while the learner waits and holds it again for the self-rating', () => {
        const quiet = TestBed.inject(QuietZone);
        api.attempt.mockReturnValue(of(polled('polledAssessing')));
        answer();
        expect(quiet.active()).toBe(false);
        tick(0);
        api.selfCheck.mockReturnValue(of(polled('polledSelfCheck')));
        tick(5000);
        click('Оценить себя');
        refresh();
        expect(quiet.active()).toBe(true);
    });

    it('moves on to the next card from a graded result and starts it clean', () => {
        api.attempt.mockReturnValue(of(resultOf('resultComplete')));
        api.start.mockReturnValue(of({ value: sessionOf([presentation(), { ...presentation(), presentationId: 'dddddddd-dddd-4ddd-8ddd-ddddddddddd1', ordinal: 1 }]), replayed: false }));
        api.submit.mockReturnValue(of({ value: polled('accepted'), replayed: false }));
        fixture = TestBed.createComponent(StudySessionPageComponent);
        refresh();
        fixture.componentInstance.startScheduled('STANDARD');
        refresh();
        const field = page().querySelector<HTMLTextAreaElement>('textarea')!;
        field.value = answerText;
        field.dispatchEvent(new Event('input'));
        click('Проверить ответ');
        tick(700);
        click('Продолжить');
        expect(page().querySelector('textarea')?.value).toBe('');
        expect(page().querySelector('app-assessment-result')).toBeNull();
        expect(button('Оспорить оценку')).toBeUndefined();
        expect(page().querySelector('.folio')?.textContent).toContain('Задание 2');
    });
});
