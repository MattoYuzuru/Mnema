import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';

import { ToastService } from '../../core/notifications/toast.service';
import { OwnDecksApiService } from '../own-decks/own-decks-api.service';
import { UsageApiService } from '../usage/usage-api.service';
import { spyObj, type SpyObj } from '../../../testing/mocks';
import { GenerationApiService } from './generation-api.service';
import { artifactWith, clone, deckFixture, eventsEnvelope, examples, ids, problemResponse, sessionWith } from './generation-test-data';
import { parseArtifactDetail, parseEditAccepted, parseEventsPage, parseSessionDetail } from './generation.models';
import { WorkshopSessionStore } from './workshop-session.store';

const image = '00000000-0000-4000-8000-000000000009';
const after = '4e700000-0000-4000-8000-000000000003';
const second = 'ca0d0000-0000-4000-8000-000000000002';

const proposed = (revision = ids.revision, version = '4') => artifactWith(ids.first, 0, 'PROPOSED', { rowVersion: version, currentRevisionId: revision });
/** The item detail with the image-search slot; the chosen candidate follows the revision. */
const detailAt = (revision = ids.revision, chosen = 'ca0d0000-0000-4000-8000-000000000001') => {
    const slot = clone(examples['mediaSlotImageSearch']);
    for (const candidate of slot.candidates) candidate.chosen = candidate.candidateId === chosen;
    return parseArtifactDetail({ ...clone(examples['artifactDetailItem']), currentRevisionId: revision, rowVersion: revision === ids.revision ? '4' : '5',
        revision: { ...clone(examples['artifactDetailItem']).revision, revisionId: revision }, mediaSlots: [slot], turns: [], revisions: [
            { revisionId: ids.revision, cause: 'INITIAL', createdAt: '2026-10-02T09:00:42Z' },
            ...(revision === ids.revision ? [] : [{ revisionId: revision, cause: 'MEDIA', createdAt: '2026-10-04T09:00:42Z' }])] });
};

describe('WorkshopSessionStore: image search (AI-10)', () => {
    let api: SpyObj<GenerationApiService>;
    let store: WorkshopSessionStore;

    function setup(state = 'REVIEW'): void {
        TestBed.resetTestingModule();
        vi.useFakeTimers();
        vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
        api = spyObj<GenerationApiService>({ getSession: vi.fn(), listEvents: vi.fn(), getArtifact: vi.fn(), editArtifact: vi.fn(), selectMediaCandidate: vi.fn() });
        api.getSession.mockImplementation(() => of(parseSessionDetail(sessionWith([proposed()], { state }))));
        api.listEvents.mockReturnValue(of(parseEventsPage(eventsEnvelope([], '0', { state: state as never, rowVersion: '12' }))));
        api.getArtifact.mockReturnValue(of(detailAt()));
        TestBed.configureTestingModule({ providers: [WorkshopSessionStore, { provide: GenerationApiService, useValue: api },
            { provide: OwnDecksApiService, useValue: { detail: () => of(deckFixture) } }, { provide: ToastService, useValue: { echo: vi.fn() } },
            { provide: UsageApiService, useValue: { load: vi.fn().mockReturnValue(of({ credits: { total: 1780 } })) } }] });
        store = TestBed.inject(WorkshopSessionStore);
        store.open(ids.deckId, ids.sessionId);
        store.loadDetail(ids.first);
    }
    const tick = (ms = 0) => vi.advanceTimersByTimeAsync(ms);
    afterEach(() => vi.useRealTimers());

    describe('the search', () => {
        it('sends IMAGE_SEARCH on the image with the query and the revision on screen, and follows the turn', async () => {
            setup();
            await tick();
            const queued = { ...clone(examples['turnQueued']), action: 'IMAGE_SEARCH', preset: null, instruction: 'лиса зимой', targetNodeIds: [image] };
            api.editArtifact.mockReturnValue(of(parseEditAccepted({ turn: queued, artifact: artifactWith(ids.first, 0, 'REVISING', { rowVersion: '5', currentRevisionId: ids.revision }) }, false)));
            const outcome = await store.edit(ids.first, { action: 'IMAGE_SEARCH', nodeIds: [image], anchorBefore: null, anchorAfter: null, instruction: 'лиса зимой' });
            expect(outcome.ok).toBe(true);
            expect(api.editArtifact.mock.calls[0]![3]).toEqual({ expectedRevisionId: ids.revision, action: 'IMAGE_SEARCH', nodeIds: [image], preset: null, instruction: 'лиса зимой' });
            expect(store.artifacts()[0]!.state).toBe('REVISING');
            expect(store.edits()[ids.first]).toMatchObject({ baseRevisionId: ids.revision, ask: { action: 'IMAGE_SEARCH', instruction: 'лиса зимой' } });
        });

        it('refuses a search in a cancelled session before any request (only REMOVE_MEDIA is free), and a removal still goes', async () => {
            setup('CANCELLED');
            await tick();
            const refused = await store.edit(ids.first, { action: 'IMAGE_SEARCH', nodeIds: [image], anchorBefore: null, anchorAfter: null });
            expect(refused).toMatchObject({ ok: false });
            expect(api.editArtifact).not.toHaveBeenCalled();
            api.editArtifact.mockReturnValue(of(parseEditAccepted({ turn: examples['turnRemoveMedia'], artifact: proposed(after, '5') }, false)));
            expect((await store.edit(ids.first, { action: 'REMOVE_MEDIA', nodeIds: [image], anchorBefore: null, anchorAfter: null })).ok).toBe(true);
        });

        it('sends AUDIO_REGENERATE of a material with the voice only when there is one, and refuses it in a cancelled session', async () => {
            setup();
            await tick();
            const queued = { ...clone(examples['turnQueued']), action: 'AUDIO_REGENERATE', preset: null, targetNodeIds: [image], voice: 'male' };
            api.editArtifact.mockReturnValue(of(parseEditAccepted({ turn: queued, artifact: artifactWith(ids.first, 0, 'REVISING', { rowVersion: '5', currentRevisionId: ids.revision }) }, false)));
            await store.edit(ids.first, { action: 'AUDIO_REGENERATE', nodeIds: [image], anchorBefore: null, anchorAfter: null, voice: 'male' });
            expect(api.editArtifact.mock.calls[0]![3]).toEqual({ expectedRevisionId: ids.revision, action: 'AUDIO_REGENERATE', nodeIds: [image], preset: null, instruction: null, voice: 'male' });
            setup('CANCELLED');
            await tick();
            expect(await store.edit(ids.first, { action: 'AUDIO_REGENERATE', nodeIds: [image], anchorBefore: null, anchorAfter: null })).toMatchObject({ ok: false });
            expect(api.editArtifact).not.toHaveBeenCalled();
        });

        it('says a refused search in the words of an image search', async () => {
            setup();
            await tick();
            api.editArtifact.mockReturnValue(throwError(() => problemResponse(409, { code: 'EDIT_IN_PROGRESS', turnId: ids.second })));
            const outcome = await store.edit(ids.first, { action: 'IMAGE_SEARCH', nodeIds: [image], anchorBefore: null, anchorAfter: null });
            expect(outcome).toMatchObject({ ok: false, message: expect.stringContaining('дождитесь окончания') });
        });
    });

    describe('choosing another image', () => {
        it('sends the candidate with the revision on screen and replaces the artifact and its detail with the answer', async () => {
            setup();
            await tick();
            api.selectMediaCandidate.mockReturnValue(of(detailAt(after, second)));
            expect(await store.selectCandidate(ids.first, 'i1', second)).toEqual({ ok: true });
            const [deck, session, artifact, slotKey, request, commandId] = api.selectMediaCandidate.mock.calls[0]!;
            expect([deck, session, artifact, slotKey]).toEqual([ids.deckId, ids.sessionId, ids.first, 'i1']);
            expect(request).toEqual({ expectedRevisionId: ids.revision, candidateId: second });
            expect(commandId).toMatch(/^[0-9a-f-]{36}$/u);
            expect(store.artifacts()[0]).toMatchObject({ currentRevisionId: after, rowVersion: '5' });
            const entry = store.details()[ids.first]!;
            expect(entry).toMatchObject({ phase: 'ready', forRevision: after, stale: false });
            expect(entry.detail!.mediaSlots[0]!.candidates.find(candidate => candidate.chosen)!.candidateId).toBe(second);
            expect(store.needsDetail(store.artifacts()[0]!)).toBe(false);
            expect(store.isBusy(ids.first)).toBe(false);
        });

        it('repeats an answer that never arrived with the same command, and uses a new one after a definitive answer', async () => {
            setup();
            await tick();
            api.selectMediaCandidate.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 503 })));
            await store.selectCandidate(ids.first, 'i1', second);
            await store.selectCandidate(ids.first, 'i1', second);
            expect(api.selectMediaCandidate.mock.calls[0]![5]).toBe(api.selectMediaCandidate.mock.calls[1]![5]);
            api.selectMediaCandidate.mockReturnValue(throwError(() => problemResponse(409, { code: 'GENERATION_STATE_CONFLICT', reason: 'ILLEGAL_STATE' })));
            await store.selectCandidate(ids.first, 'i1', second);
            // The first definitive answer still carries the command of the unknown outcome. It also says the state moved: the page
            // reads the artifact again and takes no new choice until it has (the detail is stale).
            expect(api.selectMediaCandidate.mock.calls[2]![5]).toBe(api.selectMediaCandidate.mock.calls[1]![5]);
            expect(await store.selectCandidate(ids.first, 'i1', second)).toMatchObject({ ok: false });
            expect(api.selectMediaCandidate).toHaveBeenCalledTimes(3);
        });

        it('on 412 says so calmly and reads the artifact again so the grid shows what the server holds', async () => {
            setup();
            await tick();
            api.selectMediaCandidate.mockReturnValue(throwError(() => problemResponse(412, { code: 'VERSION_CONFLICT' })));
            api.getSession.mockClear();
            const outcome = await store.selectCandidate(ids.first, 'i1', second);
            expect(outcome).toMatchObject({ ok: false, message: expect.stringContaining('выберите изображение ещё раз') });
            expect(api.getSession).toHaveBeenCalled();
            expect(store.details()[ids.first]!.stale).toBe(true);
            expect(store.needsDetail(store.artifacts()[0]!)).toBe(true);
        });

        it('on EDIT_IN_PROGRESS asks to wait, and leaves the page notice alone', async () => {
            setup();
            await tick();
            api.selectMediaCandidate.mockReturnValue(throwError(() => problemResponse(409, { code: 'EDIT_IN_PROGRESS', turnId: ids.second })));
            const outcome = await store.selectCandidate(ids.first, 'i1', second);
            expect(outcome).toMatchObject({ ok: false, message: expect.stringContaining('дождитесь окончания') });
            expect(store.notice()).toBeNull();
        });

        it('refuses while another command runs, when the artifact is not shown as current, and when it is gone', async () => {
            setup();
            await tick();
            let release: (value: unknown) => void = () => undefined;
            api.editArtifact.mockReturnValue(new (await import('rxjs')).Observable(subscriber => { release = () => subscriber.error(new HttpErrorResponse({ status: 503 })); }));
            const running = store.edit(ids.first, { action: 'IMAGE_SEARCH', nodeIds: [image], anchorBefore: null, anchorAfter: null });
            expect(await store.selectCandidate(ids.first, 'i1', second)).toMatchObject({ ok: false, message: expect.stringContaining('Подождите') });
            release(null);
            await running;
            expect(api.selectMediaCandidate).not.toHaveBeenCalled();
            expect(await store.selectCandidate('a7a70000-0000-4000-8000-0000000000ff', 'i1', second)).toMatchObject({ ok: false });
        });

        it('stops asking and marks the page missing on 404', async () => {
            setup();
            await tick();
            api.selectMediaCandidate.mockReturnValue(throwError(() => problemResponse(404, { code: 'RESOURCE_NOT_FOUND' })));
            await store.selectCandidate(ids.first, 'i1', second);
            expect(store.phase()).toBe('missing');
        });
    });
});
