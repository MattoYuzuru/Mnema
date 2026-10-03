import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';

import { ToastService } from '../../core/notifications/toast.service';
import { OwnDecksApiService } from '../own-decks/own-decks-api.service';
import { UsageApiService } from '../usage/usage-api.service';
import { spyObj, type SpyObj } from '../../../testing/mocks';
import { GenerationApiService } from './generation-api.service';
import { deckFixture, eventsEnvelope, examples, ids, problemResponse, sessionWith, artifactWith, usageContract, clone } from './generation-test-data';
import {
    EditAccepted, parseArtifactDetail, parseArtifactSummary, parseEditAccepted, parseEstimate, parseEventsPage, parseSessionDetail
} from './generation.models';
import { EDIT_NOTE_MS, WorkshopSessionStore } from './workshop-session.store';

const node = '00000000-0000-4000-8000-000000000004';
const next = '00000000-0000-4000-8000-000000000005';
const result = '4e700000-0000-4000-8000-000000000002';
const turnId = examples['turnQueued'].turnId;

const turn = (change: Record<string, unknown> = {}) => ({ ...clone(examples['turnQueued']), ...change });
const reviewing = (revision = ids.revision) => artifactWith(ids.first, 0, 'REVISING', { rowVersion: '5', currentRevisionId: revision });
const proposed = (revision = ids.revision, version = '4') => artifactWith(ids.first, 0, 'PROPOSED', { rowVersion: version, currentRevisionId: revision });
const accepted = (queued = turn(), artifact: Record<string, unknown> = reviewing()): EditAccepted => parseEditAccepted({ turn: queued, artifact }, false);
const detailWith = (turns: unknown[], revision = ids.revision) => parseArtifactDetail({ ...clone(examples['artifactDetailItem']), currentRevisionId: revision,
    revision: { ...clone(examples['artifactDetailItem']).revision, revisionId: revision },
    turns, revisions: [{ revisionId: ids.revision, cause: 'INITIAL', createdAt: '2026-10-02T09:00:42Z' },
        ...(revision === ids.revision ? [] : [{ revisionId: revision, cause: 'EDIT', createdAt: '2026-10-02T09:01:10Z' }])] });

describe('WorkshopSessionStore: selection edits (AI-11)', () => {
    let api: SpyObj<GenerationApiService>;
    let usage: { load: ReturnType<typeof vi.fn> };
    let store: WorkshopSessionStore;

    function setup(artifact: Record<string, unknown> = proposed(), state = 'REVIEW'): void {
        TestBed.resetTestingModule();
        vi.useFakeTimers();
        vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
        api = spyObj<GenerationApiService>({ getSession: vi.fn(), listEvents: vi.fn(), getArtifact: vi.fn(), editArtifact: vi.fn(), revertArtifact: vi.fn(),
            estimateEdit: vi.fn() });
        usage = { load: vi.fn().mockReturnValue(of({ credits: { total: 1780 } })) };
        api.getSession.mockImplementation(() => of(parseSessionDetail(sessionWith([artifact], { state }))));
        api.listEvents.mockReturnValue(of(parseEventsPage(eventsEnvelope([], '0', { state: state as never, rowVersion: '12' }))));
        api.getArtifact.mockReturnValue(of(detailWith([])));
        TestBed.configureTestingModule({ providers: [WorkshopSessionStore, { provide: GenerationApiService, useValue: api },
            { provide: OwnDecksApiService, useValue: { detail: () => of(deckFixture) } }, { provide: ToastService, useValue: { echo: vi.fn() } },
            { provide: UsageApiService, useValue: usage }] });
        store = TestBed.inject(WorkshopSessionStore);
        store.open(ids.deckId, ids.sessionId);
        store.loadDetail(ids.first);
    }

    const ask = { action: 'REWRITE' as const, nodeIds: [node], anchorBefore: null, anchorAfter: next, preset: 'SIMPLER' as const };
    const tick = (ms = 0) => vi.advanceTimersByTimeAsync(ms);
    afterEach(() => vi.useRealTimers());

    describe('asking for an edit', () => {
        it('sends the blocks, the preset and the revision on screen, shows the turn at once and follows the rewrite at the busy cadence', async () => {
            setup();
            await tick();
            api.editArtifact.mockReturnValue(of(accepted()));
            const outcome = await store.edit(ids.first, ask);
            expect(outcome).toMatchObject({ ok: true, turn: { status: 'QUEUED' } });
            const [deck, session, artifactId, request, commandId] = api.editArtifact.mock.calls[0]!;
            expect([deck, session, artifactId]).toEqual([ids.deckId, ids.sessionId, ids.first]);
            expect(request).toEqual({ expectedRevisionId: ids.revision, action: 'REWRITE', nodeIds: [node], preset: 'SIMPLER', instruction: null });
            expect(commandId).toMatch(/^[0-9a-f-]{36}$/u);
            // The artifact is REVISING and its detail already lists the turn.
            expect(store.artifacts()[0]!.state).toBe('REVISING');
            expect(store.details()[ids.first]!.detail!.turns.map(held => held.turnId)).toEqual([turnId]);
            expect(store.edits()[ids.first]).toMatchObject({ turnId, baseRevisionId: ids.revision, dismissed: false, announced: false,
                ask: { nodeIds: [node], anchorBefore: null, anchorAfter: next, preset: 'SIMPLER' } });
            expect(store.isBusy(ids.first)).toBe(false);
            // A rewrite in flight is polled at the busy cadence even though no step is listed yet.
            api.listEvents.mockClear();
            await tick(1_000);
            expect(api.listEvents).toHaveBeenCalled();
        });

        it('keeps a turn that already ended (it failed at once and was read before the answer arrived) from going back to «queued», and still says how it ended', async () => {
            setup();
            await tick();
            api.getArtifact.mockReturnValue(of(detailWith([turn({ status: 'FAILED', errorCode: 'REFUSAL' })])));
            store.loadDetail(ids.first);
            api.editArtifact.mockReturnValue(of(accepted()));
            expect((await store.edit(ids.first, ask)).ok).toBe(true);
            expect(store.details()[ids.first]!.detail!.turns.map(held => held.status)).toEqual(['FAILED']);
            expect(store.edits()[ids.first]).toMatchObject({ turnId, announced: true });
            expect(store.editNote()).toContain('текст не изменился');
        });

        it('reads the artifact again when a turn it lists as running has ended without the revision moving (the summary was read before the events)', async () => {
            setup();
            await tick();
            api.editArtifact.mockReturnValue(of(accepted()));
            await store.edit(ids.first, ask);
            expect(store.needsDetail(store.artifacts()[0]!)).toBe(false);
            // The session read says the artifact is proposed again; the detail still lists the turn as queued.
            api.getSession.mockImplementation(() => of(parseSessionDetail(sessionWith([proposed(ids.revision, '6')], { state: 'REVIEW' }))));
            await store.refresh();
            expect(store.artifacts()[0]!.state).toBe('PROPOSED');
            expect(store.needsDetail(store.artifacts()[0]!)).toBe(true);
            api.getArtifact.mockReturnValue(of(detailWith([turn({ status: 'FAILED', errorCode: 'REFUSAL' })])));
            store.loadDetail(ids.first);
            expect(store.needsDetail(store.artifacts()[0]!)).toBe(false);
            expect(store.editNote()).toContain('текст не изменился');
        });

        it('applies REMOVE_MEDIA at once: no memo, a sentence for the summary, and the artifact is on its new revision', async () => {
            setup();
            await tick();
            const removed = parseEditAccepted({ turn: examples['turnRemoveMedia'], artifact: { ...proposed(result, '5') } }, false);
            api.editArtifact.mockReturnValue(of(removed));
            const outcome = await store.edit(ids.first, { action: 'REMOVE_MEDIA', nodeIds: [node], anchorBefore: null, anchorAfter: null });
            expect(outcome.ok).toBe(true);
            expect(store.edits()[ids.first]).toBeUndefined();
            expect(store.editNote()).toBe('Медиа убрано.');
            expect(store.artifacts()[0]).toMatchObject({ state: 'PROPOSED', currentRevisionId: result });
            await tick(EDIT_NOTE_MS);
            expect(store.editNote()).toBeNull();
        });

        it('asks again as a new command with its own reservation: «Ещё раз» never replays the earlier command, an exact repeat does', async () => {
            setup();
            await tick();
            api.editArtifact.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 503 })));
            await store.edit(ids.first, ask);
            await store.edit(ids.first, ask);
            expect(api.editArtifact.mock.calls[1]![4]).toBe(api.editArtifact.mock.calls[0]![4]);
            api.editArtifact.mockReturnValue(of(accepted()));
            await store.edit(ids.first, { ...ask, again: true });
            expect(api.editArtifact.mock.calls[2]![4]).not.toBe(api.editArtifact.mock.calls[0]![4]);
            expect(api.editArtifact.mock.calls[2]![3]).toEqual(api.editArtifact.mock.calls[0]![3]);
        });

        it('refuses without a request when the artifact is not proposed, the session cannot rewrite, or the revision on screen is not current', async () => {
            setup(reviewing());
            await tick();
            expect((await store.edit(ids.first, ask))).toMatchObject({ ok: false, aborted: false });
            setup(proposed(), 'CANCELLED');
            await tick();
            expect(await store.edit(ids.first, ask)).toMatchObject({ ok: false, message: expect.stringContaining('нельзя править') });
            setup(proposed('4e700000-0000-4000-8000-0000000000aa'));
            await tick();
            expect(await store.edit(ids.first, ask)).toMatchObject({ ok: false, message: expect.stringContaining('Материал обновился') });
            expect(await store.edit(ids.second, ask)).toMatchObject({ ok: false, message: 'Материал больше недоступен.' });
            expect(api.editArtifact).not.toHaveBeenCalled();
        });

        it('takes REMOVE_MEDIA in a cancelled session, but no rewrite', async () => {
            setup(proposed(), 'CANCELLED');
            await tick();
            api.editArtifact.mockReturnValue(of(parseEditAccepted({ turn: examples['turnRemoveMedia'], artifact: proposed(result, '5') }, false)));
            expect((await store.edit(ids.first, { action: 'REMOVE_MEDIA', nodeIds: [node], anchorBefore: null, anchorAfter: null })).ok).toBe(true);
        });

        it('refuses a second command while one is in flight', async () => {
            setup();
            await tick();
            const answer = new Subject<EditAccepted>();
            api.editArtifact.mockReturnValue(answer);
            const first = store.edit(ids.first, ask);
            expect(store.isBusy(ids.first)).toBe(true);
            expect(await store.edit(ids.first, ask)).toMatchObject({ ok: false, message: expect.stringContaining('Подождите') });
            answer.next(accepted());
            await first;
            expect(api.editArtifact).toHaveBeenCalledTimes(1);
        });
    });

    describe('when the server refuses', () => {
        it('explains a second edit while the first runs, reads the session and marks the detail out of date so the running turn shows', async () => {
            setup();
            await tick();
            api.editArtifact.mockReturnValue(throwError(() => problemResponse(409, { code: 'EDIT_IN_PROGRESS', turnId })));
            api.getSession.mockClear();
            const outcome = await store.edit(ids.first, ask);
            expect(outcome).toEqual({ ok: false, aborted: false, message: 'Мнема ещё переписывает предыдущий фрагмент — дождитесь окончания.' });
            expect(api.getSession).toHaveBeenCalled();
            expect(store.details()[ids.first]!.stale).toBe(true);
            expect(store.notice()).toBeNull();
        });

        it('says why in words for the reason of a refused target, leaves the state alone and never touches the page notice', async () => {
            setup();
            await tick();
            api.editArtifact.mockReturnValue(throwError(() => problemResponse(400, { code: 'INVALID_REQUEST', reason: 'TARGET_PERSONAL_DATA' })));
            api.getSession.mockClear();
            expect(await store.edit(ids.first, ask)).toMatchObject({ ok: false, message: expect.stringContaining('e-mail или телефон') });
            expect(api.getSession).not.toHaveBeenCalled();
            expect(store.notice()).toBeNull();
        });

        it('treats a lost answer as unknown (the same command is sent again) and a 404 as a Workshop that is gone', async () => {
            setup();
            await tick();
            api.editArtifact.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 0 })));
            api.getSession.mockClear();
            expect(await store.edit(ids.first, ask)).toMatchObject({ ok: false, message: expect.stringContaining('та же команда') });
            expect(api.getSession).not.toHaveBeenCalled();
            api.editArtifact.mockReturnValue(throwError(() => problemResponse(404)));
            await store.edit(ids.first, { ...ask, again: true });
            expect(store.phase()).toBe('missing');
        });

        it('takes an unsent request back: nothing is reported, the Workshop is read again and the same command is kept', async () => {
            setup();
            await tick();
            const answer = new Subject<EditAccepted>();
            api.editArtifact.mockReturnValue(answer);
            const control = new AbortController();
            const pending = store.edit(ids.first, ask, control.signal);
            control.abort();
            expect(await pending).toEqual({ ok: false, aborted: true, message: '' });
            expect(answer.observed).toBe(false);
            expect(store.isBusy(ids.first)).toBe(false);
            expect(store.details()[ids.first]!.stale).toBe(true);
            // The page reads the artifact again (it was marked out of date), then asks the same thing: the command is the same one.
            store.loadDetail(ids.first);
            api.editArtifact.mockReturnValue(of(accepted()));
            await store.edit(ids.first, ask);
            expect(api.editArtifact.mock.calls[1]![4]).toBe(api.editArtifact.mock.calls[0]![4]);
        });

        it('does not even send a request that was taken back before it left', async () => {
            setup();
            await tick();
            const already = new AbortController();
            already.abort();
            api.editArtifact.mockReturnValue(new Subject<EditAccepted>());
            expect(await store.edit(ids.first, { ...ask, instruction: 'иначе' }, already.signal)).toMatchObject({ aborted: true });
        });
    });

    describe('the end of a rewrite', () => {
        const finish = (status: string, extra: Record<string, unknown> = {}) => {
            api.getArtifact.mockReturnValue(of(detailWith([turn({ status, ...extra })], status === 'APPLIED' ? result : ids.revision)));
            store.loadDetail(ids.first);
        };

        it('puts one sentence into the summary line when the turn this page started has ended, and does not repeat it', async () => {
            setup();
            await tick();
            api.editArtifact.mockReturnValue(of(accepted()));
            await store.edit(ids.first, ask);
            finish('RUNNING');
            expect(store.editNote()).toBeNull();
            finish('APPLIED', { resultRevisionId: result });
            expect(store.editNote()).toBe('Мнема переписала фрагмент.');
            expect(store.edits()[ids.first]!.announced).toBe(true);
            await tick(EDIT_NOTE_MS);
            finish('APPLIED', { resultRevisionId: result });
            expect(store.editNote()).toBeNull();
        });

        it('says a failed rewrite left the text as it was, and a stopped one too', async () => {
            setup();
            await tick();
            api.editArtifact.mockReturnValue(of(accepted()));
            await store.edit(ids.first, ask);
            finish('FAILED', { errorCode: 'REFUSAL' });
            expect(store.editNote()).toContain('текст не изменился');
            // The artifact is proposed again on the same revision (the session read says so), and the user asks once more.
            api.getSession.mockImplementation(() => of(parseSessionDetail(sessionWith([proposed(ids.revision, '6')], { state: 'REVIEW' }))));
            await store.refresh();
            const again = '7a7a0000-0000-4000-8000-0000000000bb';
            api.editArtifact.mockReturnValue(of(accepted(turn({ turnId: again }))));
            await store.edit(ids.first, { ...ask, again: true });
            finish('CANCELLED', { turnId: again });
            expect(store.editNote()).toContain('остановлена');
        });

        it('stays silent for a turn of an earlier page (no memo) and for a read without the turn', async () => {
            setup();
            await tick();
            finish('APPLIED', { resultRevisionId: result });
            expect(store.editNote()).toBeNull();
            api.editArtifact.mockReturnValue(of(accepted()));
            await store.edit(ids.first, ask);
            api.getArtifact.mockReturnValue(of(detailWith([])));
            store.loadDetail(ids.first);
            expect(store.editNote()).toBeNull();
        });

        it('closes the strip of a rewrite and forgets a memo at the next open', async () => {
            setup();
            await tick();
            api.editArtifact.mockReturnValue(of(accepted()));
            await store.edit(ids.first, ask);
            store.dismissEdit(ids.first);
            expect(store.edits()[ids.first]!.dismissed).toBe(true);
            store.dismissEdit(ids.second);
            store.open(ids.deckId, ids.sessionId);
            expect(store.edits()).toEqual({});
        });
    });

    describe('going back', () => {
        it('moves to the revision asked for with the artifact version, closes the strip and says so once', async () => {
            setup(proposed(result, '6'));
            await tick();
            api.editArtifact.mockReturnValue(of(accepted(turn(), proposed(result, '6'))));
            api.revertArtifact.mockReturnValue(of(parseArtifactSummary(proposed(ids.revision, '7'))));
            expect(await store.revert(ids.first, ids.revision)).toBe(true);
            const [, , artifactId, version, target, commandId] = api.revertArtifact.mock.calls[0]!;
            expect([artifactId, version, target]).toEqual([ids.first, '6', ids.revision]);
            expect(commandId).toMatch(/^[0-9a-f-]{36}$/u);
            expect(store.artifacts()[0]).toMatchObject({ currentRevisionId: ids.revision, rowVersion: '7' });
            expect(store.editNote()).toBe('Вернули выбранную версию.');
        });

        it('refuses while a rewrite runs or in a session that does not allow it, and reports a refusal on the page', async () => {
            setup(reviewing());
            await tick();
            expect(await store.revert(ids.first, ids.revision)).toBe(false);
            setup(proposed(), 'CANCELLED');
            await tick();
            expect(await store.revert(ids.first, result)).toBe(false);
            setup();
            await tick();
            api.revertArtifact.mockReturnValue(throwError(() => problemResponse(409, { code: 'GENERATION_STATE_CONFLICT', reason: 'ILLEGAL_STATE' })));
            expect(await store.revert(ids.first, result)).toBe(false);
            expect(store.notice()).toMatchObject({ tone: 'error' });
            expect(api.revertArtifact).toHaveBeenCalledTimes(1);
        });
    });

    describe('reading a revision and the cost', () => {
        it('reads each revision once, and tries again after a failure', async () => {
            setup();
            await tick();
            api.getArtifact.mockClear();
            api.getArtifact.mockReturnValue(of(detailWith([])));
            const first = await store.loadRevision(ids.first, ids.revision);
            expect(first?.root.content.length).toBeGreaterThan(0);
            await store.loadRevision(ids.first, ids.revision);
            expect(api.getArtifact).toHaveBeenCalledTimes(1);
            expect(api.getArtifact).toHaveBeenCalledWith(ids.deckId, ids.sessionId, ids.first, ids.revision);
            api.getArtifact.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
            expect(await store.loadRevision(ids.first, result)).toBeNull();
            await tick();
            api.getArtifact.mockReturnValue(of(detailWith([], ids.revision)));
            expect(await store.loadRevision(ids.first, result)).not.toBeNull();
            const exercise = parseArtifactDetail(examples['artifactDetailExercise']);
            api.getArtifact.mockReturnValue(of(exercise));
            expect(await store.loadRevision(ids.second, exercise.revision!.revisionId)).toBeNull();
        });

        it('writes the cost line from the estimate and the whole allowance, caps the block count, and says nothing when it cannot', async () => {
            setup();
            await tick();
            api.estimateEdit.mockReturnValue(of(parseEstimate({ ...clone(usageContract['estimateResponse']), credits: { p50: 3, p95: 4 } })));
            expect(await store.editCost(ids.first, 2)).toEqual({ text: '≈ 0,2 % лимита', canStart: true });
            expect(api.estimateEdit).toHaveBeenCalledWith(ids.deckId, { sessionId: ids.sessionId, artifactId: ids.first, action: 'REWRITE', targetNodeCount: 2 });
            await store.editCost(ids.first, 80);
            expect(api.estimateEdit.mock.calls[1]![1].targetNodeCount).toBe(50);
            expect(usage.load).toHaveBeenCalledTimes(1);
            api.estimateEdit.mockReturnValue(throwError(() => problemResponse(409, { code: 'CAPABILITY_UNAVAILABLE' })));
            expect(await store.editCost(ids.first, 1)).toBeNull();
        });

        it('falls back to the integer percent when the budget cannot be read', async () => {
            setup();
            await tick();
            usage.load.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
            api.estimateEdit.mockReturnValue(of(parseEstimate(usageContract['estimateResponse'])));
            expect((await store.editCost(ids.first, 1))?.text).toMatch(/% лимита$/u);
        });

        it('keeps a failure the user must read out of the summary line: the page notice is for refusals the window cannot show', async () => {
            setup();
            await tick();
            store.notify('Не получилось.');
            expect(store.notice()).toEqual({ tone: 'error', text: 'Не получилось.' });
        });
    });
});
