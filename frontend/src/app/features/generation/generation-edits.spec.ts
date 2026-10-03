import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';

import { AuthoringProtocolError } from '../authoring/authoring.models';
import { GenerationApiService } from './generation-api.service';
import { readProblem } from './generation-problem';
import {
    NBSP, describeEditCost, describeEditLimit, describeTurnAsk, describeTurnStatus, editOutcomeNote, editProblemMessage, mediaActionReason, turnFailureReason
} from './generation-view';
import {
    EDIT_ACTIONS, EDIT_PRESETS, RequestValidationError, TURN_STATES, parseArtifactDetail, parseEditAccepted, parseEstimate, parseTurn, serializeEdit,
    serializeEditEstimate
} from './generation.models';
import { clone, errorsContract, examples, httpContract, ids, pathOf, privateHeaders, problemResponse, statesContract, usageContract } from './generation-test-data';

const node = '00000000-0000-4000-8000-000000000004';
const second = '00000000-0000-4000-8000-000000000006';
const command = ids.command;
const edit = { expectedRevisionId: ids.revision, action: 'REWRITE' as const, nodeIds: [node], preset: 'SIMPLER' as const, instruction: 'Проще, с примером' };

describe('edits on the wire (contracts/generation, AI-11)', () => {
    describe('turns', () => {
        it('reads every turn example of the contract', () => {
            expect(parseTurn(examples['turnQueued'])).toMatchObject({ status: 'QUEUED', action: 'REWRITE', preset: 'SIMPLER', resultRevisionId: null, targetNodeIds: [node] });
            expect(parseTurn(examples['turnApplied'])).toMatchObject({ status: 'APPLIED', resultRevisionId: '4e700000-0000-4000-8000-000000000002' });
            expect(parseTurn(examples['turnFailed'])).toMatchObject({ status: 'FAILED', errorCode: 'INVALID_OUTPUT', preset: null, instruction: 'Сделай подробнее' });
            expect(parseTurn(examples['turnRemoveMedia'])).toMatchObject({ status: 'APPLIED', action: 'REMOVE_MEDIA', instruction: null });
        });

        it('knows exactly the turn states, actions and presets of the contract', () => {
            expect([...TURN_STATES].sort()).toEqual(Object.keys(statesContract['turn'].states).sort());
            const actions = (httpContract['schemas'].turn.action as string).split('|').map(entry => entry.trim());
            expect([...EDIT_ACTIONS].sort()).toEqual([...actions].sort());
            const presets = (httpContract['schemas'].turn.preset as string).match(/[A-Z]{5,}/gu)!.filter(word => word !== 'REWRITE');
            expect([...EDIT_PRESETS].sort()).toEqual([...presets].sort());
        });

        it('refuses a turn with an unknown member, state or action, a bad id or a timestamp that is not one', () => {
            const turn = (change: Record<string, unknown>) => ({ ...clone(examples['turnApplied']), ...change });
            expect(() => parseTurn(turn({ extra: 1 }))).toThrow(AuthoringProtocolError);
            expect(() => parseTurn(turn({ status: 'LATER' }))).toThrow(AuthoringProtocolError);
            expect(() => parseTurn(turn({ action: 'POLISH' }))).toThrow(AuthoringProtocolError);
            expect(() => parseTurn(turn({ preset: 'FANCIER' }))).toThrow(AuthoringProtocolError);
            expect(() => parseTurn(turn({ turnId: 'x' }))).toThrow(AuthoringProtocolError);
            expect(() => parseTurn(turn({ targetNodeIds: ['x'] }))).toThrow(AuthoringProtocolError);
            expect(() => parseTurn(turn({ createdAt: 'yesterday' }))).toThrow(AuthoringProtocolError);
            expect(parseTurn(turn({ errorCode: 'SOMETHING_NEW', status: 'FAILED', resultRevisionId: null })).errorCode).toBeNull();
        });

        it('lists the turns and the revisions of an artifact, and reads one exact revision without moving the pointer', () => {
            const detail = { ...clone(examples['artifactDetailItem']), turns: [examples['turnApplied']],
                revisions: [{ revisionId: ids.revision, cause: 'INITIAL', createdAt: '2026-10-02T09:00:42Z' },
                    { revisionId: '4e700000-0000-4000-8000-000000000002', cause: 'EDIT', createdAt: '2026-10-02T09:01:10Z' }] };
            const parsed = parseArtifactDetail(detail);
            expect(parsed.turns).toHaveLength(1);
            expect(parsed.revisions.map(revision => revision.cause)).toEqual(['INITIAL', 'EDIT']);
            // `?revisionId=`: the payload is the asked revision, the pointer stays where it was.
            const old = { ...clone(detail), currentRevisionId: '4e700000-0000-4000-8000-000000000002' };
            expect(() => parseArtifactDetail(old)).toThrow(AuthoringProtocolError);
            expect(parseArtifactDetail(old, ids.revision).currentRevisionId).toBe('4e700000-0000-4000-8000-000000000002');
            expect(() => parseArtifactDetail(old, '4e700000-0000-4000-8000-000000000009')).toThrow(AuthoringProtocolError);
        });

        it('reads the answer of an accepted edit: the turn and the artifact', () => {
            const accepted = parseEditAccepted({ turn: examples['turnQueued'], artifact: httpContract['endpoints'].find((e: any) => e.operationId === 'editArtifact').success.body.artifact },
                true);
            expect(accepted).toMatchObject({ replayed: true, turn: { status: 'QUEUED' }, artifact: { state: 'REVISING' } });
            expect(() => parseEditAccepted({ turn: examples['turnQueued'] }, false)).toThrow(AuthoringProtocolError);
        });
    });

    describe('the request', () => {
        it('serializes the contract example exactly', () => {
            const example = httpContract['endpoints'].find((e: any) => e.operationId === 'editArtifact').requestBody.example;
            const body = serializeEdit({ expectedRevisionId: example.expectedRevisionId, action: example.action, nodeIds: example.target.nodeIds,
                preset: example.preset, instruction: example.instruction }, example.commandId);
            expect(body).toEqual(example);
            expect(Object.keys(body)).toEqual(['commandId', 'expectedRevisionId', 'action', 'target', 'preset', 'instruction']);
        });

        it('leaves out a preset or an instruction that is not there, ignores the instruction of REMOVE_MEDIA and trims the text', () => {
            expect(serializeEdit({ expectedRevisionId: ids.revision, action: 'REWRITE', nodeIds: [node] }, command))
                .toEqual({ commandId: command, expectedRevisionId: ids.revision, action: 'REWRITE', target: { nodeIds: [node] } });
            expect(serializeEdit({ ...edit, action: 'FREE', preset: null, instruction: '  проще  ' }, command)['instruction']).toBe('проще');
            expect(serializeEdit({ expectedRevisionId: ids.revision, action: 'REMOVE_MEDIA', nodeIds: [second], instruction: 'ignored' }, command))
                .not.toHaveProperty('instruction');
        });

        it('refuses what the server would: no blocks, repeats, more than 50, a preset without a rewrite, a free edit without text, a blank or long text', () => {
            const refuse = (change: Record<string, unknown>) => expect(() => serializeEdit({ ...edit, ...change } as never, command)).toThrow(RequestValidationError);
            refuse({ nodeIds: [] });
            refuse({ nodeIds: [node, node] });
            refuse({ nodeIds: Array.from({ length: 51 }, (_, index) => `00000000-0000-4000-8000-${String(index + 1).padStart(12, '0')}`) });
            refuse({ action: 'FREE', preset: 'SIMPLER' });
            refuse({ action: 'FREE', preset: null, instruction: null });
            refuse({ action: 'FREE', preset: null, instruction: '   ' });
            refuse({ preset: 'FANCIER' });
            refuse({ instruction: 'я'.repeat(2001) });
            expect(() => serializeEdit({ ...edit, nodeIds: ['nope'] }, command)).toThrow(AuthoringProtocolError);
            expect(() => serializeEdit({ ...edit, instruction: '😀'.repeat(2000) }, command)).not.toThrow();
        });

        it('builds the edit form of the estimate request as the contract shows it', () => {
            const example = usageContract['estimateRequest'].forms.edit;
            expect(serializeEditEstimate({ sessionId: example.edit.sessionId, artifactId: example.edit.artifactId, action: example.edit.action,
                targetNodeCount: example.edit.targetNodeCount })).toEqual(example);
            expect(serializeEditEstimate({ sessionId: ids.sessionId, artifactId: ids.first, action: 'REMOVE_MEDIA' }))
                .toEqual({ edit: { sessionId: ids.sessionId, artifactId: ids.first, action: 'REMOVE_MEDIA' } });
            expect(() => serializeEditEstimate({ sessionId: ids.sessionId, artifactId: ids.first, action: 'REWRITE', targetNodeCount: 0 })).toThrow(RequestValidationError);
            expect(() => serializeEditEstimate({ sessionId: ids.sessionId, artifactId: ids.first, action: 'REWRITE', targetNodeCount: 51 })).toThrow(RequestValidationError);
        });
    });

    describe('refusals', () => {
        it('reads the members the edit refusals carry: the code, the reason and the limits', () => {
            expect(readProblem(problemResponse(409, { code: 'EDIT_IN_PROGRESS', turnId: ids.first }))).toMatchObject({ code: 'EDIT_IN_PROGRESS' });
            expect(readProblem(problemResponse(400, { code: 'INVALID_REQUEST', reason: 'TARGET_PERSONAL_DATA' }))).toMatchObject({ reason: 'TARGET_PERSONAL_DATA' });
            expect(readProblem(problemResponse(422, { code: 'RESOURCE_LIMIT_EXCEEDED', limit: 'EDIT_TARGET_SIZE',
                limits: { maxTurnsPerArtifact: 50, maxRevisionsPerArtifact: 30, maxEditTargetTokens: 2050 } })).limits).toEqual({ maxTurnsPerArtifact: 50,
                maxRevisionsPerArtifact: 30, maxEditTargetTokens: 2050 });
        });

        it('has a clear Russian message for every reason of a refused target the contract lists', () => {
            const listed = ((httpContract['endpoints'].find((e: any) => e.operationId === 'editArtifact').errors.find((e: any) => e.status === 400).reason) as string)
                .split('(')[0]!.split('|').map(reason => reason.trim());
            expect(listed).toEqual(['TARGET_NOT_CONTIGUOUS', 'TARGET_UNSUPPORTED_BLOCK', 'TARGET_PERSONAL_DATA', 'TARGET_MEDIA_ONLY', 'TARGET_NO_AUDIO']);
            const messages = listed.map(reason => editProblemMessage(readProblem(problemResponse(400, { code: 'INVALID_REQUEST', reason }))));
            expect(new Set(messages).size).toBe(5);
            expect(messages[2]).toBe('В выделении есть e-mail или телефон — Мнема не переписывает такие фрагменты.');
            for (const message of messages) expect(message).toMatch(/[А-Яа-я]/u);
            expect(editProblemMessage(readProblem(problemResponse(400, { code: 'INVALID_REQUEST' })))).toBe('Этот фрагмент нельзя переписать с помощью Мнемы.');
            expect(errorsContract['codes']['INVALID_REQUEST'].extensionMembers.reason).toContain('TARGET_MEDIA_ONLY');
        });

        it('explains a second edit while the first runs, the limits, the capability, the state and a stale revision in words', () => {
            const say = (status: number, body: Record<string, unknown>) => editProblemMessage(readProblem(problemResponse(status, body)));
            expect(say(409, { code: 'EDIT_IN_PROGRESS', turnId: ids.first })).toBe('Мнема ещё переписывает предыдущий фрагмент — дождитесь окончания.');
            expect(say(409, { code: 'USAGE_LIMIT_REACHED' })).toContain('Не хватает лимита ИИ на эту правку');
            expect(say(409, { code: 'CAPABILITY_UNAVAILABLE', capability: 'textToSpeech' })).toBe('Озвучивание пока недоступно.');
            expect(say(409, { code: 'CAPABILITY_UNAVAILABLE', capability: 'imageSearch' })).toBe('Поиск изображений пока недоступен.');
            expect(say(409, { code: 'CAPABILITY_UNAVAILABLE', capability: 'imageGeneration' })).toBe('Создание изображений пока недоступно.');
            expect(say(409, { code: 'CAPABILITY_UNAVAILABLE' })).toContain('ИИ сейчас недоступен');
            expect(say(409, { code: 'GENERATION_STATE_CONFLICT', reason: 'ILLEGAL_STATE' })).toContain('нельзя править');
            expect(say(409, { code: 'GENERATION_STATE_CONFLICT', reason: 'MEDIA_NOT_READY' })).toContain('Медиа ещё не готовы');
            expect(say(409, { code: 'IDEMPOTENCY_CONFLICT' })).toContain('уже использована');
            expect(say(412, { code: 'VERSION_CONFLICT' })).toContain('Выделите его заново');
            expect(say(422, { code: 'RESOURCE_LIMIT_EXCEEDED', limit: 'EDIT_TARGET_SIZE' })).toContain('Выделите меньше');
            expect(say(422, { code: 'RESOURCE_LIMIT_EXCEEDED', limit: 'TURNS_PER_ARTIFACT' })).toContain('(50)');
            expect(say(422, { code: 'RESOURCE_LIMIT_EXCEEDED', limit: 'REVISIONS_PER_ARTIFACT' })).toContain('версий');
            expect(say(422, { code: 'RESOURCE_LIMIT_EXCEEDED' })).toContain('Достигнут предел');
            expect(editProblemMessage(readProblem(new HttpErrorResponse({ status: 0 })))).toContain('будет отправлена та же команда');
        });
    });

    describe('words about turns, cost and media', () => {
        it('says what a turn asked for, how it stands and why it failed', () => {
            expect(describeTurnAsk({ action: 'REWRITE', preset: 'SIMPLER', instruction: null })).toBe('Проще');
            expect(describeTurnAsk({ action: 'REWRITE', preset: 'SHORTER', instruction: 'без примеров' })).toBe('Короче: без примеров');
            expect(describeTurnAsk({ action: 'FREE', preset: null, instruction: 'Сделай подробнее' })).toBe('Сделай подробнее');
            expect(describeTurnAsk({ action: 'REWRITE', preset: null, instruction: null })).toBe('Переписано заново');
            expect(describeTurnAsk({ action: 'REMOVE_MEDIA', preset: null, instruction: null })).toBe('Убрано медиа');
            expect(describeTurnAsk({ action: 'IMAGE_SEARCH', preset: null, instruction: null })).toContain('изображения');
            expect(describeTurnAsk({ action: 'IMAGE_GENERATE', preset: null, instruction: null })).toContain('изображения');
            expect(describeTurnAsk({ action: 'AUDIO_REGENERATE', preset: null, instruction: null })).toContain('Озвучка');
            expect(['QUEUED', 'RUNNING', 'APPLIED', 'FAILED', 'CANCELLED'].map(status => describeTurnStatus({ status: status as never, action: 'REWRITE' })))
                .toEqual(['ждёт очереди', 'пишется', 'применено', 'не удалось', 'остановлено']);
            expect(describeTurnStatus({ status: 'APPLIED', action: 'REMOVE_MEDIA' })).toBe('сделано');
            for (const code of ['INVALID_OUTPUT', 'REFUSAL', 'SOURCE_UNAVAILABLE', 'PROVIDER_UNAVAILABLE', 'ESTIMATE_EXCEEDED', 'DEADLINE_EXCEEDED', null] as const) {
                expect(turnFailureReason(code)).toMatch(/[А-Яа-я]/u);
            }
            expect(editOutcomeNote('APPLIED', 'REWRITE')).toBe('Мнема переписала фрагмент.');
            expect(editOutcomeNote('APPLIED', 'REMOVE_MEDIA')).toBe('Медиа убрано.');
            expect(editOutcomeNote('FAILED', 'REWRITE')).toContain('текст не изменился');
            expect(editOutcomeNote('CANCELLED', 'REWRITE')).toContain('остановлена');
            expect(editOutcomeNote('RUNNING', 'REWRITE')).toBe('');
        });

        it('writes the cost line finer than the integer percent of the estimate', () => {
            const estimate = parseEstimate(usageContract['estimateResponse']);
            expect(describeEditCost({ ...estimate, credits: { p50: 3, p95: 4 } }, 1780)).toBe(`≈${NBSP}0,2${NBSP}% лимита`);
            expect(describeEditCost({ ...estimate, credits: { p50: 3, p95: 4 } }, 1000)).toBe(`≈${NBSP}0,4${NBSP}% лимита`);
            expect(describeEditCost({ ...estimate, credits: { p50: 30, p95: 40 } }, 100)).toBe(`≈${NBSP}40${NBSP}% лимита`);
            expect(describeEditCost({ ...estimate, credits: { p50: 0, p95: 1 } }, 5000)).toBe(`менее 0,1${NBSP}% лимита`);
            expect(describeEditCost(estimate, null)).toBe(`≈${NBSP}${estimate.percentOfPeriodAllowance.p95}${NBSP}% лимита`);
            expect(describeEditCost({ ...estimate, percentOfPeriodAllowance: { p50: 0, p95: 0 } }, 0)).toBe(`менее 1${NBSP}% лимита`);
        });

        it('says why an edit does not fit the budget: the day is over, the plan lacks it, or the limit renews', () => {
            const bucket = { bucket: 'credits.day', window: 'DAY' as const, unit: 'CREDITS' as const, limit: 100, used: 98, required: 4, offered: true,
                renewsAt: '2026-10-04T00:00:00Z', fitsAfterRenewal: true, plan: 'FREE' as const };
            expect(describeEditLimit(bucket)).toMatch(/^На сегодня лимит ИИ исчерпан\. Лимит обновится .*: тогда правки снова будут доступны\.$/u);
            expect(describeEditLimit({ ...bucket, window: 'MONTH', fitsAfterRenewal: false })).toBe('Не хватит лимита ИИ на эту правку. Подробности — в профиле, в блоке «ИИ-бюджет».');
            expect(describeEditLimit({ ...bucket, offered: false })).toContain('недоступны на вашем тарифе');
            expect(describeEditLimit(undefined)).toContain('Не хватит лимита ИИ на эту правку');
        });

        it('explains why a media action is not offered, by the capability the server reports', () => {
            expect(mediaActionReason('search', { available: false, reason: 'DISABLED' })).toContain('Подбор изображений появится позже');
            expect(mediaActionReason('generate', { available: false, reason: 'PROVIDER_NOT_CONFIGURED' })).toContain('Создание изображений появится позже');
            expect(mediaActionReason('speech', { available: false, reason: 'TEMPORARILY_UNAVAILABLE' })).toBe('Озвучивание сейчас временно недоступно. Попробуйте позже.');
            expect(mediaActionReason('speech', { available: true, reason: null })).toContain('пока недоступно');
        });
    });
});

describe('GenerationApiService: edits and reverts', () => {
    let api: GenerationApiService;
    let http: HttpTestingController;

    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
        api = TestBed.inject(GenerationApiService);
        http = TestBed.inject(HttpTestingController);
    });
    afterEach(() => http.verify());

    const accepted = (body: object = { turn: examples['turnQueued'], artifact: httpContract['endpoints'].find((e: any) => e.operationId === 'editArtifact').success.body.artifact },
                      headers: Record<string, string> = privateHeaders) => ({ body, init: { status: 202, statusText: 'Accepted', headers } });

    it('posts the edit to the artifact path of http.json and reads the 202', async () => {
        const result = firstValueFrom(api.editArtifact(ids.deckId, ids.sessionId, ids.first, edit, command));
        const request = http.expectOne(candidate => candidate.url === pathOf('editArtifact'));
        const contract = httpContract['endpoints'].find((e: any) => e.operationId === 'editArtifact');
        expect(request.request.method).toBe(contract.method);
        expect(request.request.headers.has('If-Match')).toBe(false);
        expect(request.request.body).toEqual({ commandId: command, expectedRevisionId: ids.revision, action: 'REWRITE', target: { nodeIds: [node] },
            preset: 'SIMPLER', instruction: 'Проще, с примером' });
        const { body, init } = accepted();
        request.flush(body, init);
        expect(await result).toMatchObject({ replayed: false, turn: { status: 'QUEUED' }, artifact: { state: 'REVISING' } });
    });

    it('accepts a stored replay, and refuses another status, a cacheable answer, another artifact or another action', async () => {
        const attempt = async (flush: (request: ReturnType<typeof http.expectOne>) => void, outcome: 'ok' | 'refused') => {
            const result = firstValueFrom(api.editArtifact(ids.deckId, ids.sessionId, ids.first, edit, command));
            flush(http.expectOne(candidate => candidate.url === pathOf('editArtifact')));
            if (outcome === 'ok') expect(await result).toMatchObject({ replayed: true });
            else await expect(result).rejects.toBeInstanceOf(AuthoringProtocolError);
        };
        const { body, init } = accepted();
        await attempt(request => request.flush(body, { ...init, headers: { ...privateHeaders, 'Idempotency-Replayed': 'true' } }), 'ok');
        await attempt(request => request.flush(body, { ...init, status: 200, statusText: 'OK' }), 'refused');
        await attempt(request => request.flush(body, { ...init, headers: {} }), 'refused');
        await attempt(request => request.flush(body, { ...init, headers: { ...privateHeaders, 'Idempotency-Replayed': 'maybe' } }), 'refused');
        const other = clone(body) as any;
        other.artifact.artifactId = ids.second;
        await attempt(request => request.flush(other, init), 'refused');
        const action = clone(body) as any;
        action.turn.action = 'FREE';
        await attempt(request => request.flush(action, init), 'refused');
    });

    it('refuses an edit that breaks a rule before any request', async () => {
        await expect(firstValueFrom(api.editArtifact(ids.deckId, ids.sessionId, ids.first, { ...edit, nodeIds: [] }, command))).rejects.toBeInstanceOf(RequestValidationError);
        http.expectNone(() => true);
    });

    it('reverts to a revision: the artifact version and the revision in the body, 200 with the summary, a replay without ETag', async () => {
        const target = '4e700000-0000-4000-8000-000000000002';
        const summary = { ...clone(examples['artifactSummaryProposed']), artifactId: ids.first, rowVersion: '6', currentRevisionId: target };
        const result = firstValueFrom(api.revertArtifact(ids.deckId, ids.sessionId, ids.first, '5', target, command));
        const request = http.expectOne(candidate => candidate.url === pathOf('revertArtifact'));
        expect(request.request.method).toBe('POST');
        expect(request.request.body).toEqual({ commandId: command, expectedArtifactVersion: '5', toRevisionId: target });
        request.flush(summary, { headers: { ...privateHeaders, ETag: '"6"' } });
        expect(await result).toMatchObject({ state: 'PROPOSED', currentRevisionId: target, rowVersion: '6' });

        const replay = firstValueFrom(api.revertArtifact(ids.deckId, ids.sessionId, ids.first, '5', target, command));
        http.expectOne(candidate => candidate.url === pathOf('revertArtifact')).flush(summary, { headers: { ...privateHeaders, 'Idempotency-Replayed': 'true' } });
        await replay;
        // The no-op move to the revision that is already current answers 200 and may carry no ETag.
        const noop = firstValueFrom(api.revertArtifact(ids.deckId, ids.sessionId, ids.first, '5', target, command));
        http.expectOne(candidate => candidate.url === pathOf('revertArtifact')).flush(summary, { headers: privateHeaders });
        await noop;
    });

    it('refuses a revert answer that is not the proposed artifact on the revision asked for, or has a wrong ETag', async () => {
        const target = '4e700000-0000-4000-8000-000000000002';
        const summary = { ...clone(examples['artifactSummaryProposed']), artifactId: ids.first, rowVersion: '6', currentRevisionId: target };
        const attempt = async (body: object, headers: Record<string, string> = { ...privateHeaders, ETag: '"6"' }) => {
            const result = firstValueFrom(api.revertArtifact(ids.deckId, ids.sessionId, ids.first, '5', target, command));
            http.expectOne(candidate => candidate.url === pathOf('revertArtifact')).flush(body, { headers });
            await expect(result).rejects.toBeInstanceOf(AuthoringProtocolError);
        };
        await attempt({ ...summary, state: 'REVISING' });
        await attempt({ ...summary, currentRevisionId: ids.revision });
        await attempt({ ...summary, artifactId: ids.second });
        await attempt(summary, { ...privateHeaders, ETag: '"7"' });
        await attempt(summary, { ETag: '"6"' });
    });

    it('estimates the cost of an edit with the edit form', async () => {
        const result = firstValueFrom(api.estimateEdit(ids.deckId, { sessionId: ids.sessionId, artifactId: ids.first, action: 'REWRITE', targetNodeCount: 2 }));
        const request = http.expectOne(candidate => candidate.url === pathOf('estimateGeneration'));
        expect(request.request.body).toEqual({ edit: { sessionId: ids.sessionId, artifactId: ids.first, action: 'REWRITE', targetNodeCount: 2 } });
        request.flush(usageContract['estimateResponse'], { headers: privateHeaders });
        expect((await result).credits.p95).toBeGreaterThan(0);
    });

    it('reads one exact revision of an artifact with ?revisionId= and checks it is that one', async () => {
        const old = { ...clone(examples['artifactDetailItem']), currentRevisionId: '4e700000-0000-4000-8000-000000000002' };
        old.revision = clone(examples['artifactDetailItem']).revision;
        const result = firstValueFrom(api.getArtifact(ids.deckId, ids.sessionId, ids.first, ids.revision));
        const request = http.expectOne(candidate => candidate.url === pathOf('getArtifact') && candidate.params.get('revisionId') === ids.revision);
        request.flush(old, { headers: { ...privateHeaders, ETag: '"4"' } });
        expect((await result).revision?.revisionId).toBe(ids.revision);

        const mismatch = firstValueFrom(api.getArtifact(ids.deckId, ids.sessionId, ids.first, '4e700000-0000-4000-8000-000000000009'));
        http.expectOne(candidate => candidate.params.get('revisionId') === '4e700000-0000-4000-8000-000000000009')
            .flush(old, { headers: { ...privateHeaders, ETag: '"4"' } });
        await expect(mismatch).rejects.toBeInstanceOf(AuthoringProtocolError);
    });
});
