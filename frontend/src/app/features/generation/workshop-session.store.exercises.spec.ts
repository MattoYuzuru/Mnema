import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';

import { ToastService } from '../../core/notifications/toast.service';
import { OwnDecksApiService } from '../own-decks/own-decks-api.service';
import { spyObj, type SpyObj } from '../../../testing/mocks';
import { ack, artifactIds, exerciseArtifact, exerciseSession } from './exercise-test-data';
import { GenerationApiService } from './generation-api.service';
import { deckFixture, eventsEnvelope, ids, problemResponse } from './generation-test-data';
import { parseApprovalAck, parseArtifactSummary, parseEventsPage, parseSessionDetail } from './generation.models';
import { WorkshopSessionStore } from './workshop-session.store';

const many = (count: number) => Array.from({ length: count }, (_, index) =>
    `a7a70000-0000-4000-8000-${String(index + 1).padStart(12, '0')}`);
const proposedBatch = (idList: readonly string[]) => idList.map((id, index) => ({ ...exerciseArtifact(id, index % 6, 'PROPOSED'),
    currentRevisionId: `4e700000-0000-4000-8000-${String(index + 1).padStart(12, '0')}` }));

describe('WorkshopSessionStore: exercise batches (AI-13)', () => {
    let api: SpyObj<GenerationApiService>;
    let decks: SpyObj<OwnDecksApiService>;
    let toast: { echo: ReturnType<typeof vi.fn> };
    let store: WorkshopSessionStore;
    let session: Record<string, unknown>;

    function setup(artifacts: readonly Record<string, unknown>[], overrides: Record<string, unknown> = {}): void {
        TestBed.resetTestingModule();
        vi.useFakeTimers();
        vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
        api = spyObj<GenerationApiService>({ getSession: vi.fn(), listEvents: vi.fn(), getArtifact: vi.fn(), approveArtifacts: vi.fn(),
            rejectArtifact: vi.fn(), undoRejectArtifact: vi.fn(), retryArtifact: vi.fn() });
        decks = spyObj<OwnDecksApiService>({ detail: vi.fn() });
        toast = { echo: vi.fn() };
        session = exerciseSession(artifacts, overrides);
        api.getSession.mockImplementation(() => of(parseSessionDetail(session)));
        api.listEvents.mockReturnValue(of(parseEventsPage(eventsEnvelope([], '0', { state: 'REVIEW', rowVersion: '12' }))));
        decks.detail.mockReturnValue(of(deckFixture));
        TestBed.configureTestingModule({ providers: [WorkshopSessionStore, { provide: GenerationApiService, useValue: api },
            { provide: OwnDecksApiService, useValue: decks }, { provide: ToastService, useValue: toast }] });
        store = TestBed.inject(WorkshopSessionStore);
        store.open(ids.deckId, ids.sessionId);
    }

    afterEach(() => vi.useRealTimers());

    const approveAnswer = (list: readonly string[], version: string, revision: string) => (_d: string, _s: string, targets: readonly { artifactId: string }[],
        _pin: unknown, commandId: string) => of(parseApprovalAck(ack(commandId, targets.map(target => target.artifactId), version, revision), false));

    it('counts a proposed exercise as approvable, so the batch can be saved', () => {
        setup(proposedBatch(many(3)));
        expect(store.approvable()).toHaveLength(3);
    });

    it('saves only the chosen proposals, in batch order, as one command with the deck pin and the versions the user saw', async () => {
        const list = many(4);
        setup(proposedBatch(list));
        api.approveArtifacts.mockImplementation(approveAnswer(list, '9', '33333333-3333-4333-8333-333333333333'));
        const published = await store.approveSelected([list[3]!, list[1]!]);
        expect(published).toBe(2);
        const [, , targets, pin, commandId] = api.approveArtifacts.mock.calls[0]!;
        expect(targets.map(target => target.artifactId)).toEqual([list[1], list[3]]);
        expect(targets[0]).toMatchObject({ expectedArtifactVersion: '3', expectedRevisionId: '4e700000-0000-4000-8000-000000000002' });
        expect(pin).toEqual({ rowVersion: deckFixture.rowVersion, revisionId: deckFixture.revisionId });
        expect(commandId).toMatch(/^[0-9a-f-]{36}$/);
        expect(toast.echo).toHaveBeenCalledWith('Новые упражнения: 2 — уже в колоде');
    });

    it('saves more than 20 in chunks, each its own command, the deck pin chained from the previous acknowledgement', async () => {
        const list = many(25);
        setup(proposedBatch(list));
        let call = 0;
        api.approveArtifacts.mockImplementation((_d, _s, targets, _pin, commandId) => of(parseApprovalAck(ack(commandId, targets.map(target => target.artifactId),
            String(9 + call++), call === 1 ? '33333333-3333-4333-8333-333333333333' : '44444444-4444-4444-8444-444444444444'), false)));
        const published = await store.approveSelected(list);
        expect(published).toBe(25);
        expect(api.approveArtifacts).toHaveBeenCalledTimes(2);
        const [first, second] = api.approveArtifacts.mock.calls;
        expect(first![2]).toHaveLength(20);
        expect(second![2]).toHaveLength(5);
        expect(first![3]).toEqual({ rowVersion: deckFixture.rowVersion, revisionId: deckFixture.revisionId });
        expect(second![3]).toEqual({ rowVersion: '9', revisionId: '33333333-3333-4333-8333-333333333333' });
        expect(first![4]).not.toBe(second![4]);
        expect(toast.echo).toHaveBeenCalledTimes(1);
        expect(toast.echo).toHaveBeenCalledWith('Новые упражнения: 25 — уже в колоде');
    });

    it('does nothing for no choice, a busy store, or a session that cannot approve', async () => {
        setup(proposedBatch(many(2)));
        expect(await store.approveSelected([])).toBe(0);
        expect(await store.approveSelected([artifactIds[5]!])).toBe(0);
        expect(api.approveArtifacts).not.toHaveBeenCalled();
        setup(proposedBatch(many(2)), { state: 'EXPIRED' });
        expect(await store.approveSelected(many(2))).toBe(0);
        expect(api.approveArtifacts).not.toHaveBeenCalled();
    });

    it('stops at a refusal, says how many were saved before it, and explains a stale material in words about exercises', async () => {
        const list = many(25);
        setup(proposedBatch(list));
        api.approveArtifacts.mockImplementationOnce(approveAnswer(list, '9', '33333333-3333-4333-8333-333333333333'));
        api.approveArtifacts.mockImplementationOnce(() => throwError(() => problemResponse(409, { code: 'GENERATION_STATE_CONFLICT', reason: 'SOURCE_STALE', artifactIds: [list[22]] })));
        const published = await store.approveSelected(list);
        expect(published).toBe(20);
        expect(store.notice()?.tone).toBe('error');
        expect(store.notice()?.text).toContain('Сохранено упражнений: 20.');
        expect(store.notice()?.text).toContain('Материал изменился, пока писалось упражнение');
        expect(toast.echo).toHaveBeenCalledWith('Новые упражнения: 20 — уже в колоде');
        // The state moved under the user's hands: the session is read again.
        expect(api.getSession.mock.calls.length).toBeGreaterThan(1);
    });

    describe('a 412 (the deck or a proposal moved)', () => {
        it('reads the deck and the session again and sends the same proposals once more when they are unchanged', async () => {
            const list = many(2);
            setup(proposedBatch(list));
            api.approveArtifacts.mockReturnValueOnce(throwError(() => problemResponse(412)));
            api.approveArtifacts.mockImplementationOnce(approveAnswer(list, '10', '33333333-3333-4333-8333-333333333333'));
            const published = await store.approveSelected(list);
            expect(published).toBe(2);
            expect(api.approveArtifacts).toHaveBeenCalledTimes(2);
            expect(decks.detail.mock.calls.length).toBeGreaterThan(1);
            expect(store.notice()).toBeNull();
        });

        it('explains instead of retrying when a proposal changed meanwhile', async () => {
            const list = many(2);
            setup(proposedBatch(list));
            api.approveArtifacts.mockImplementationOnce(() => {
                session = exerciseSession([{ ...proposedBatch(list)[0]!, currentRevisionId: '4e700000-0000-4000-8000-0000000000ff' }, proposedBatch(list)[1]!]);
                return throwError(() => problemResponse(412));
            });
            expect(await store.approveSelected(list)).toBe(0);
            expect(api.approveArtifacts).toHaveBeenCalledTimes(1);
            expect(store.notice()?.text).toContain('изменились');
        });

        it('tells the user the proposals are gone when none of them can be approved any more', async () => {
            const list = many(1);
            setup(proposedBatch(list));
            api.approveArtifacts.mockImplementationOnce(() => {
                session = exerciseSession([{ ...exerciseArtifact(list[0]!, 0, 'STALE') }], { state: 'REVIEW' });
                return throwError(() => problemResponse(412));
            });
            expect(await store.approveSelected(list)).toBe(0);
            expect(store.notice()?.text).toContain('изменились');
        });
    });

    it('sends the very same command again after an answer that never came, and a new one after a refusal', async () => {
        const list = many(1);
        setup(proposedBatch(list));
        api.approveArtifacts.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 503 })));
        api.approveArtifacts.mockImplementation(approveAnswer(list, '9', '33333333-3333-4333-8333-333333333333'));
        expect(await store.approveSelected(list)).toBe(0);
        expect(store.notice()?.text).toContain('Не удалось подтвердить действие');
        expect(await store.approveSelected(list)).toBe(1);
        expect(api.approveArtifacts.mock.calls[1]![4]).toBe(api.approveArtifacts.mock.calls[0]![4]);
    });

    it('says the deck could not be read when there is no pin to approve against', async () => {
        const list = many(1);
        setup(proposedBatch(list));
        decks.detail.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
        // the pin was read when the store opened; forget it by opening against a deck that cannot be read
        store.open(ids.deckId, ids.sessionId);
        expect(await store.approveSelected(list)).toBe(0);
        expect(store.notice()?.text).toBe('Не удалось прочитать колоду. Попробуйте ещё раз.');
        expect(api.approveArtifacts).not.toHaveBeenCalled();
    });

    it('rejects proposals one by one and resolves with how many were rejected', async () => {
        const list = many(3);
        setup(proposedBatch(list));
        api.rejectArtifact.mockImplementation((_d, _s, artifactId) => of(parseArtifactSummary({ ...exerciseArtifact(artifactId, 0, 'REJECTED'), rowVersion: '4' })));
        expect(await store.rejectMany([list[0]!, list[2]!])).toBe(2);
        expect(api.rejectArtifact).toHaveBeenCalledTimes(2);
        api.rejectArtifact.mockReturnValueOnce(throwError(() => problemResponse(409, { code: 'GENERATION_STATE_CONFLICT' })));
        expect(await store.rejectMany([list[1]!])).toBe(0);
    });
});
