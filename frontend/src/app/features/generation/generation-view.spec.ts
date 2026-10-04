import { HttpErrorResponse } from '@angular/common/http';

import { isSendKey } from './implicit-submit';
import { readProblem } from './generation-problem';
import {
    EFFORT_OPTIONS, NBSP, NOTES_MODE_OPTIONS, artifactStatus, describeEstimate, describeNoteArchive, describePlanCost, describePlansForSplit, describeSessionProgress, describeUsageLimit, failureNote, failureReason,
    formatDay, formatWorkshopStart, positionLabel, problemMessage, promptExcerpt, slotCaption, summarize
} from './generation-view';
import {
    ARTIFACT_ERROR_CODES, ARTIFACT_STATES, ArtifactSummary, BlockingBucket, GenerationEstimate, SLOT_STATES, parseArtifactSummary, parseSessionSummary
} from './generation.models';
import { artifactWith, clone, examples, ids, problemResponse, usageContract } from './generation-test-data';
import { AuthoringProtocolError } from '../authoring/authoring.models';

const summary = (state: string, overrides: Record<string, unknown> = {}): ArtifactSummary =>
    parseArtifactSummary(artifactWith(ids.first, 0, state, overrides));
const bucket = (overrides: Partial<BlockingBucket> = {}): BlockingBucket => ({ bucket: 'CREDITS', window: 'MONTH', unit: 'CREDITS',
    limit: 360, used: 348, required: 44, offered: true, renewsAt: '2026-10-31T21:00:00Z', fitsAfterRenewal: true, plan: 'PLUS', ...overrides });

describe('Generation texts and helpers', () => {
    describe('the Enter key of the prompt field', () => {
        const key = (overrides: Partial<KeyboardEvent> = {}) => ({ key: 'Enter', shiftKey: false, isComposing: false, keyCode: 13, ...overrides });

        it('sends on Enter, never on Shift+Enter and never while an IME composes (isComposing or the legacy keyCode 229)', () => {
            expect(isSendKey(key())).toBe(true);
            expect(isSendKey(key({ shiftKey: true }))).toBe(false);
            expect(isSendKey(key({ isComposing: true }))).toBe(false);
            expect(isSendKey(key({ keyCode: 229 }))).toBe(false);
            expect(isSendKey(key({ key: 'a' }))).toBe(false);
        });
    });

    describe('preflight', () => {
        const estimate = (p95: number): GenerationEstimate => ({ credits: { p50: 1, p95: 1 }, percentOfPeriodAllowance: { p50: 0, p95 },
            balance: { remainingCredits: 5, renewsAt: null }, canStart: true, blockingBuckets: [], shortfallCredits: null, personalDataWarning: false, planCredits: null });

        it('says «≈ N % лимита» from p95, with a no-break space, and «менее 1 %» for a trace', () => {
            expect(describeEstimate(estimate(6))).toBe(`≈${NBSP}6${NBSP}% лимита`);
            expect(describeEstimate(estimate(0))).toBe(`менее 1${NBSP}% лимита`);
        });
    });

    describe('what to do when the budget is short', () => {
        it('suggests «Кратко», waiting for the renewal date and the plans when the request fits after the renewal', () => {
            const explanation = describeUsageLimit(bucket(), 'DETAILED');
            expect(explanation.headline).toBe('Не хватит лимита ИИ на этот запрос.');
            expect(explanation.options[0]).toContain('«Кратко»');
            expect(explanation.options[1]).toBe(`Подождите до ${formatDay('2026-10-31T21:00:00Z')}: лимит обновится, и запроса хватит.`);
            expect(explanation.plansLink).toBe(true);
        });

        it('does not suggest «Кратко» when it is already chosen, and says honestly when waiting will not help', () => {
            const explanation = describeUsageLimit(bucket({ fitsAfterRenewal: false, window: 'WEEK', renewsAt: '2026-10-04T21:00:00Z' }), 'SHORT');
            expect(explanation.options).toHaveLength(1);
            expect(explanation.options[0]).toContain('всё равно не хватит');
        });

        it('has a daily-burst wording, a no-date fallback, and a plan-gap wording when the bucket is not offered', () => {
            expect(describeUsageLimit(bucket({ window: 'DAY', bucket: 'DAILY_BURST' }), 'SHORT').headline).toBe('На сегодня лимит ИИ исчерпан.');
            expect(describeUsageLimit(bucket({ window: 'DAY' }), 'SHORT').options[0]).toContain('дневной лимит обновится');
            expect(describeUsageLimit(bucket({ renewsAt: null, fitsAfterRenewal: false }), 'SHORT').options).toEqual(['Сократите запрос или уберите медиа.']);
            const notOffered = describeUsageLimit(bucket({ offered: false, renewsAt: null, fitsAfterRenewal: false }), 'AUTO');
            expect(notOffered.headline).toBe('На вашем тарифе это недоступно.');
            expect(notOffered.plansLink).toBe(true);
        });
    });

    describe('failure of one material', () => {
        it('gives every artifact error code a reason in words, and never claims a charge for a failure except the user stopping it', () => {
            for (const code of ARTIFACT_ERROR_CODES) {
                expect(failureReason(code).length, code).toBeGreaterThan(10);
                expect(failureNote(code) === null, code).toBe(code === 'CANCELLED');
            }
            expect(failureReason(null)).toBe('Что-то пошло не так.');
            expect(failureReason('REFUSAL')).toContain('напишите материал сами');
            expect(failureNote('PROVIDER_UNAVAILABLE')).toBe('За этот материал лимит не списан.');
        });
    });

    describe('status shapes and the summary', () => {
        it('gives every artifact state a status, and a proposal with media on its way or failed its own', () => {
            for (const state of ARTIFACT_STATES) expect(artifactStatus(summary(state)).word.length, state).toBeGreaterThan(2);
            expect(artifactStatus(summary('PROPOSED'))).toEqual({ shape: 'ready', word: 'готов' });
            expect(artifactStatus(summary('PROPOSED', { mediaSlotCounts: { total: 2, ready: 1, failed: 0 } })))
                .toEqual({ shape: 'writing', word: 'готовятся медиа' });
            expect(artifactStatus(summary('PROPOSED', { mediaSlotCounts: { total: 2, ready: 1, failed: 1 } })))
                .toEqual({ shape: 'failed', word: 'медиа не удалось' });
            expect(artifactStatus(summary('FAILED')).shape).toBe('failed');
            expect(artifactStatus(summary('STALE')).shape).toBe('stale');
            expect(artifactStatus(summary('PUBLISHED')).shape).toBe('done');
        });

        it('reads «7 готово · 2 пишутся · 1 не удался» with Russian plural forms, and nothing for zero', () => {
            const many = (state: string, count: number) => Array.from({ length: count }, (_, position) => summary(state, { artifactId: `a7a70000-0000-4000-8000-${String(position).padStart(12, '0')}` }));
            expect(summarize([...many('PROPOSED', 7), ...many('GENERATING', 1), ...many('QUEUED', 1), ...many('FAILED', 1)]))
                .toBe(`7${NBSP}готово · 2${NBSP}пишутся · 1${NBSP}не удался`);
            expect(summarize([...many('GENERATING', 1)])).toBe(`1${NBSP}пишется`);
            expect(summarize([...many('FAILED', 2)])).toBe(`2${NBSP}не удались`);
            expect(summarize([...many('PUBLISHED', 3), ...many('REJECTED', 1), ...many('STALE', 1)]))
                .toBe(`1${NBSP}нужно решение · 3${NBSP}в колоде · 1${NBSP}отклонено`);
            expect(summarize([])).toBe('Пока ничего');
            expect(summarize(many('GENERATING', 5))).toBe(`5${NBSP}пишутся`);
            expect(summarize(many('GENERATING', 11))).toBe(`11${NBSP}пишутся`);
        });

        it('describes a session of the deck list from the server counts', () => {
            const session = parseSessionSummary(examples['sessionSummary']);
            expect(describeSessionProgress(session)).toBe(`6${NBSP}готово · 2${NBSP}пишутся · 1${NBSP}не удался`);
            expect(describeSessionProgress({ ...session, state: 'PLANNING' })).toBe('Мнема составляет план');
            expect(describeSessionProgress({ ...session, state: 'PLAN_READY' })).toBe('План ждёт вашего решения');
        });

        it('says what the plan costs apart as «План: ≈ N % лимита», from the plan line and its share of the estimate (#295)', () => {
            const planned = (percent: number, total: number, plan: number | null): GenerationEstimate => ({ credits: { p50: total, p95: total },
                percentOfPeriodAllowance: { p50: 0, p95: percent }, balance: { remainingCredits: 5, renewsAt: null }, canStart: true, blockingBuckets: [],
                shortfallCredits: null, personalDataWarning: false, planCredits: plan });
            expect(describePlanCost(planned(11, 40, 20))).toBe(`План: ≈${NBSP}6${NBSP}% лимита`);
            expect(describePlanCost(planned(100, 400, 20))).toBe(`План: ≈${NBSP}5${NBSP}% лимита`);
            expect(describePlanCost(planned(0, 40, 20))).toBe(`План: менее 1${NBSP}% лимита`);
            expect(describePlanCost(planned(6, 0, 20))).toBe(`План: менее 1${NBSP}% лимита`);
            expect(describePlanCost(planned(6, 40, null))).toBeNull();
            // With the whole allowance of the period the share is exact, whatever the estimate rounded.
            expect(describePlanCost(planned(11, 40, 20), 360)).toBe(`План: ≈${NBSP}5,6${NBSP}% лимита`);
            expect(describePlanCost(planned(11, 40, 20), 0)).toBe(`План: ≈${NBSP}6${NBSP}% лимита`);
            expect(describePlansForSplit(planned(11, 40, 20), 360, 3)).toBe(`План составляется для каждой мастерской — всего 3${NBSP}раза, ≈${NBSP}17${NBSP}% лимита.`);
            expect(describePlansForSplit(planned(11, 40, 20), null, 3)).toBe(`План составляется для каждой мастерской — всего 3${NBSP}раза, ≈${NBSP}18${NBSP}% лимита.`);
            expect(describePlansForSplit(planned(6, 40, null), 360, 5)).toBe(`План составляется для каждой мастерской — всего 5${NBSP}раз.`);
        });

        it('explains the refusal of a plan apart from the refusal of the work: the plan is optional (#295)', () => {
            const plans = (overrides: Partial<BlockingBucket> = {}): BlockingBucket => bucket({ bucket: 'SMART_PLAN', unit: 'COUNT', limit: 4, used: 4, required: 1, ...overrides });
            const over = describeUsageLimit(plans(), 'AUTO');
            expect(over.headline).toBe('Планы на этот период закончились.');
            expect(over.options[0]).toContain('Снимите «Сначала показать план»');
            expect(over.options).toContain(`Или подождите до ${formatDay('2026-10-31T21:00:00Z')}: лимит планов обновится.`);
            expect(over.options.join(' ')).not.toContain('Кратко');
            expect(describeUsageLimit(plans({ offered: false, limit: null }), 'AUTO').headline).toBe('Планы недоступны на вашем тарифе.');
            expect(describeUsageLimit(plans({ fitsAfterRenewal: false }), 'AUTO').options).toHaveLength(1);
            expect(describeUsageLimit(bucket(), 'AUTO').headline).toBe('Не хватит лимита ИИ на этот запрос.');
        });

        it('labels positions, shortens the prompt to its first line and formats dates', () => {
            expect(positionLabel(2, 10)).toBe('Материал 3 из 10');
            expect(promptExcerpt('  первая строка\nвторая ')).toBe('первая строка');
            expect(promptExcerpt('x'.repeat(200), 10)).toBe('xxxxxxxxx…');
            expect(promptExcerpt('   ')).toBeNull();
            expect(promptExcerpt(null)).toBeNull();
            expect(formatDay(null)).toBeNull();
            expect(formatDay('soon')).toBeNull();
            expect(formatDay('2026-10-04T12:00:00Z')).toBe('4 октября');
            // Russian month names, never the English ones of Angular's default `date` locale.
            expect(formatWorkshopStart('2026-10-03T08:53:00Z')).toMatch(/^3 октября/u);
            expect(formatWorkshopStart('soon')).toBe('');
        });

        it('describes every media slot state in words', () => {
            for (const state of SLOT_STATES) {
                for (const kind of ['AUDIO', 'IMAGE', 'VIDEO'] as const) expect(slotCaption(kind, state).length).toBeGreaterThan(5);
            }
            expect(slotCaption('IMAGE', 'GENERATING')).toBe('Подбираем изображение…');
            expect(slotCaption('AUDIO', 'GENERATING')).toBe('Озвучиваем…');
            expect(slotCaption('VIDEO', 'GENERATING')).toBe('Готовим видео…');
        });
    });

    describe('notes (#290)', () => {
        const noteId = (n: number) => `20700000-0000-4000-8000-00000000000${n}`;

        it('offers one material per note first, and merging second', () => {
            expect(NOTES_MODE_OPTIONS.map(option => [option.value, option.label])).toEqual([
                ['ONE_PER_NOTE', 'Материал на заметку'], ['MERGE_INTO_ONE', 'Объединить в один']]);
        });

        it('tells what archiving did: archived, skipped and why, never blaming a note it did not skip', () => {
            expect(describeNoteArchive({ archived: [], skipped: [] })).toBe('Архивировать нечего.');
            expect(describeNoteArchive({ archived: [noteId(1), noteId(2)], skipped: [] })).toBe('Архивировано: 2');
            expect(describeNoteArchive({ archived: [noteId(1)], skipped: [{ noteId: noteId(2), reason: 'CHANGED' }] }))
                .toBe('Архивировано: 1, пропущено: 1 — заметка изменилась');
            expect(describeNoteArchive({ archived: [], skipped: [{ noteId: noteId(1), reason: 'ALREADY_ARCHIVED' }] }))
                .toBe('Архивировано: 0, пропущено: 1 — уже в архиве');
            expect(describeNoteArchive({ archived: [noteId(1)], skipped: [{ noteId: noteId(2), reason: 'CHANGED' }, { noteId: noteId(3), reason: 'DELETED' },
                { noteId: noteId(4), reason: 'CHANGED' }] })).toBe('Архивировано: 1, пропущено: 3 — заметка изменилась: 2, заметка удалена: 1');
        });
    });

    describe('effort options', () => {
        it('are Авто, Кратко, Средне, Подробно in that order, each with a live explanation', () => {
            expect(EFFORT_OPTIONS.map(option => option.label)).toEqual(['Авто', 'Кратко', 'Средне', 'Подробно']);
            expect(EFFORT_OPTIONS.every(option => option.hint?.startsWith(option.label))).toBe(true);
        });
    });

    describe('problems', () => {
        const message = (status: number, body: Record<string, unknown> = {}) => problemMessage(readProblem(problemResponse(status, body)));

        it('reads the members of a problem and drops what is malformed', () => {
            const problem = readProblem(problemResponse(409, { code: 'GENERATION_STATE_CONFLICT', reason: 'MEDIA_NOT_READY',
                artifactIds: [ids.first, 'nope'], activeSessionIds: 'not-a-list', limit: 12, capability: 'textToSpeech' }));
            expect(problem).toMatchObject({ status: 409, code: 'GENERATION_STATE_CONFLICT', reason: 'MEDIA_NOT_READY', artifactIds: [ids.first],
                activeSessionIds: [], limit: null, capability: 'textToSpeech', uncertain: false });
        });

        it('treats no answer, a server error and an unreadable answer as an unknown outcome to retry with the same command', () => {
            expect(readProblem(new HttpErrorResponse({ status: 0 })).uncertain).toBe(true);
            expect(readProblem(new HttpErrorResponse({ status: 503 })).uncertain).toBe(true);
            expect(readProblem(new AuthoringProtocolError('x'))).toMatchObject({ status: -1, uncertain: true });
            expect(readProblem(new Error('boom'))).toMatchObject({ status: 0, uncertain: true });
            expect(readProblem(new HttpErrorResponse({ status: 409, error: 'text' })).code).toBeNull();
            expect(problemMessage(readProblem(new HttpErrorResponse({ status: 0 })))).toContain('будет отправлена та же команда');
        });

        it('ignores usage members that are incomplete or of the wrong type', () => {
            const full = clone(usageContract['errors'].USAGE_LIMIT_REACHED.example);
            expect(readProblem(problemResponse(409, full)).usage).not.toBeNull();
            expect(readProblem(problemResponse(409, { ...full, plan: 'GOLD' })).usage).toBeNull();
            expect(readProblem(problemResponse(409, { ...full, used: -1 })).usage).toBeNull();
            expect(readProblem(problemResponse(409, { ...full, limit: 'many' })).usage).toBeNull();
            expect(readProblem(problemResponse(409, { ...full, offered: 'yes' })).usage).toBeNull();
            expect(readProblem(problemResponse(409, { ...full, renewsAt: 'whenever' })).usage?.renewsAt).toBeNull();
            expect(readProblem(problemResponse(409, { ...full, limit: null })).usage?.limit).toBeNull();
            expect(readProblem(problemResponse(409, { ...full, code: 'OTHER' })).usage).toBeNull();
        });

        it('explains each definitive failure in words', () => {
            expect(message(400, { code: 'INVALID_REQUEST' })).toContain('Запрос не принят');
            expect(message(404)).toContain('больше недоступны');
            expect(message(412, { code: 'VERSION_CONFLICT' })).toContain('изменились');
            expect(message(428)).toContain('Обновите страницу');
            expect(message(418)).toContain('Не удалось выполнить действие');
            const conflict = (reason: string) => message(409, { code: 'GENERATION_STATE_CONFLICT', reason });
            expect(conflict('MEDIA_NOT_READY')).toContain('Медиа ещё не готовы');
            expect(conflict('SOURCE_STALE')).toContain('Заметка изменилась');
            expect(conflict('NOT_RETRYABLE')).toContain('повтор не поможет');
            expect(conflict('ILLEGAL_STATE')).toContain('уже недоступно');
            expect(message(409, { code: 'USAGE_LIMIT_REACHED' })).toContain('ИИ-бюджет');
            expect(message(409, { code: 'CAPABILITY_UNAVAILABLE' })).toContain('ИИ сейчас недоступен');
            expect(message(409, { code: 'SOURCE_UNAVAILABLE' })).toContain('изменилась или удалена');
            expect(message(409, { code: 'IDEMPOTENCY_CONFLICT' })).toContain('другими данными');
            expect(message(409, { code: 'SOMETHING' })).toContain('невозможно');
            expect(message(422, { code: 'SPEC_NOT_SUPPORTED' })).toContain('пока не поддерживается');
            expect(message(422, { code: 'RESOURCE_LIMIT_EXCEEDED', limit: 'ACTIVE_SESSIONS' })).toContain('три мастерские');
            expect(message(422, { code: 'RESOURCE_LIMIT_EXCEEDED', limit: 'EDITING_DRAFTS' })).toContain('черновиков');
            expect(message(422, { code: 'RESOURCE_LIMIT_EXCEEDED', limit: 'SOURCES' })).toContain('Разделите запрос');
            expect(message(422, { code: 'RESOURCE_LIMIT_EXCEEDED', limit: 'EXERCISES_PER_SESSION' })).toContain('Достигнут предел');
        });
    });
});
