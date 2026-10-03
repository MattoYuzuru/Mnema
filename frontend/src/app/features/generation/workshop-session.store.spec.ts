import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';

import { ToastService } from '../../core/notifications/toast.service';
import { OwnDecksApiService } from '../own-decks/own-decks-api.service';
import { spyObj, type SpyObj } from '../../../testing/mocks';
import { GenerationApiService } from './generation-api.service';
import {
    ApprovalAck, ArtifactSummary, parseApprovalAck, parseNoteArchive, parseArtifactDetail, parseArtifactSummary, parseEventsPage, parseHandoff, parseSessionDetail
} from './generation.models';
import {
    POLL_ACTIVE_MS, POLL_BACKGROUND_MAX_MS, POLL_BACKGROUND_MIN_MS, POLL_IDLE_MS, WorkshopSessionStore
} from './workshop-session.store';
import {
    activeStep, artifactWith, clone, deckFixture, eventsEnvelope, examples, httpContract, ids, noteArchiveAnswer, noteIds, problemResponse, sessionWith,
    sessionWithNotes, wireBlocks, wireEvent
} from './generation-test-data';

const second = 'a7a70000-0000-4000-8000-000000000003';
const proposedEvent = (seq: number, id = ids.second, version = '4') => wireEvent(seq, 'ARTIFACT_STATE', { state: 'PROPOSED', artifactVersion: version,
    currentRevisionId: '4e700000-0000-4000-8000-000000000002', errorCode: null, repinStatus: null }, id);

describe('WorkshopSessionStore', () => {
    let api: SpyObj<GenerationApiService>;
    let decks: SpyObj<OwnDecksApiService>;
    let toast: { echo: ReturnType<typeof vi.fn> };
    let store: WorkshopSessionStore;
    let visibility: ReturnType<typeof vi.spyOn>;

    const running = (): Record<string, unknown> => sessionWith([artifactWith(ids.first, 0, 'PROPOSED'),
        artifactWith(ids.second, 1, 'GENERATING', { rowVersion: '2', currentRevisionId: null, title: '' })]);
    const review = (): Record<string, unknown> => sessionWith([artifactWith(ids.first, 0, 'PROPOSED'),
        artifactWith(ids.second, 1, 'PROPOSED', { rowVersion: '3', currentRevisionId: '4e700000-0000-4000-8000-000000000002' })],
        { state: 'REVIEW', approvableCount: 2 });

    function setup(session: Record<string, unknown> = running(), deckReadable = true): void {
        TestBed.resetTestingModule();
        vi.useFakeTimers();
        visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
        api = spyObj<GenerationApiService>({
            getSession: vi.fn().mockName('getSession'), listEvents: vi.fn().mockName('listEvents'), getArtifact: vi.fn().mockName('getArtifact'),
            approveArtifact: vi.fn().mockName('approveArtifact'), approveArtifacts: vi.fn().mockName('approveArtifacts'),
            rejectArtifact: vi.fn().mockName('rejectArtifact'), undoRejectArtifact: vi.fn().mockName('undoRejectArtifact'),
            retryArtifact: vi.fn().mockName('retryArtifact'), handoffArtifact: vi.fn().mockName('handoffArtifact'),
            cancelSession: vi.fn().mockName('cancelSession'), deleteSession: vi.fn().mockName('deleteSession'),
            archiveUsedNotes: vi.fn().mockName('archiveUsedNotes')
        });
        decks = spyObj<OwnDecksApiService>({ detail: vi.fn().mockName('detail') });
        toast = { echo: vi.fn() };
        api.getSession.mockReturnValue(of(parseSessionDetail(session)));
        api.listEvents.mockReturnValue(of(parseEventsPage(eventsEnvelope([], '0'))));
        decks.detail.mockReturnValue(deckReadable ? of(deckFixture) : throwError(() => new HttpErrorResponse({ status: 500 })));
        TestBed.configureTestingModule({ providers: [WorkshopSessionStore, { provide: GenerationApiService, useValue: api },
            { provide: OwnDecksApiService, useValue: decks }, { provide: ToastService, useValue: toast }] });
        store = TestBed.inject(WorkshopSessionStore);
        store.open(ids.deckId, ids.sessionId);
    }

    const events = (list: Record<string, unknown>[], cursor: string, session?: { state: string; rowVersion: string }, steps: Record<string, unknown>[] = []) =>
        of(parseEventsPage(eventsEnvelope(list, cursor, session, steps)));
    const tick = (ms: number) => vi.advanceTimersByTimeAsync(ms);

    afterEach(() => vi.useRealTimers());

    describe('opening', () => {
        it('reads the session and the deck title, and polls the events from the very start at once', async () => {
            setup();
            expect(store.phase()).toBe('ready');
            expect(store.artifacts()).toHaveLength(2);
            expect(api.listEvents).not.toHaveBeenCalled();
            await tick(0);
            expect(store.deckTitle()).toBe('Японский N4');
            expect(api.listEvents).toHaveBeenCalledWith(ids.deckId, ids.sessionId, '0', 100);
        });

        it('says «missing» for a session that is gone and «error» for any other failure, and can be retried', () => {
            setup();
            api.getSession.mockReturnValueOnce(throwError(() => problemResponse(404)));
            store.open(ids.deckId, ids.sessionId);
            expect(store.phase()).toBe('missing');
            api.getSession.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 500 })));
            store.retryLoad();
            expect(store.phase()).toBe('error');
            api.getSession.mockReturnValueOnce(of(parseSessionDetail(running())));
            store.retryLoad();
            expect(store.phase()).toBe('ready');
        });

        it('does not poll a session that is already over', async () => {
            setup(sessionWith([artifactWith(ids.first, 0, 'PUBLISHED')], { state: 'CLOSED' }));
            await tick(60_000);
            expect(api.listEvents).not.toHaveBeenCalled();
            expect(store.terminal()).toBe(true);
        });

        it('drops the answers of an earlier open when the page moves to another session', async () => {
            setup();
            const late = new Subject<ReturnType<typeof parseSessionDetail>>();
            api.getSession.mockReturnValueOnce(late);
            store.open(ids.deckId, ids.sessionId);
            api.getSession.mockReturnValueOnce(of(parseSessionDetail(review())));
            store.open(ids.deckId, ids.sessionId);
            late.next(parseSessionDetail(running()));
            expect(store.session()?.state).toBe('REVIEW');
        });
    });

    describe('polling cadence', () => {
        it('polls every second while visible and a step is active, and every five seconds when idle', async () => {
            setup();
            api.listEvents.mockReturnValue(events([], '0', { state: 'RUNNING', rowVersion: '12' }, [activeStep]));
            await tick(0);
            expect(api.listEvents).toHaveBeenCalledTimes(1);
            await tick(POLL_ACTIVE_MS - 1);
            expect(api.listEvents).toHaveBeenCalledTimes(1);
            await tick(1);
            expect(api.listEvents).toHaveBeenCalledTimes(2);

            api.listEvents.mockReturnValue(events([], '0', { state: 'REVIEW', rowVersion: '13' }));
            api.getSession.mockReturnValue(of(parseSessionDetail(review())));
            await tick(POLL_ACTIVE_MS);
            const calls = api.listEvents.mock.calls.length;
            await tick(POLL_IDLE_MS - 1);
            expect(api.listEvents).toHaveBeenCalledTimes(calls);
            await tick(1);
            expect(api.listEvents).toHaveBeenCalledTimes(calls + 1);
        });

        it('slows to 5 s in a background tab, backs off to 15 s while nothing happens and starts over after news', async () => {
            setup();
            visibility.mockReturnValue('hidden');
            await tick(0);
            expect(api.listEvents).toHaveBeenCalledTimes(1);
            const gaps: number[] = [];
            let last = Date.now();
            for (let count = 1; gaps.length < 5; count++) {
                const before = api.listEvents.mock.calls.length;
                await tick(500);
                if (api.listEvents.mock.calls.length > before) { gaps.push(Date.now() - last); last = Date.now(); }
                expect(count).toBeLessThan(200);
            }
            // 500 ms is the resolution of the loop above.
            expect(gaps.map(gap => Math.round(gap / 500) * 500)).toEqual([POLL_BACKGROUND_MIN_MS, 7500, 11500, POLL_BACKGROUND_MAX_MS, POLL_BACKGROUND_MAX_MS]);
            api.listEvents.mockReturnValue(events([wireBlocks(1, 0, 1, 'Раз')], '1', { state: 'RUNNING', rowVersion: '12' }, [activeStep]));
            await tick(POLL_BACKGROUND_MAX_MS + 500);
            const before = api.listEvents.mock.calls.length;
            await tick(POLL_BACKGROUND_MIN_MS + 500);
            expect(api.listEvents.mock.calls.length).toBeGreaterThan(before);
        });

        it('polls at once when the tab becomes visible again and drops back to the background pace when it is hidden', async () => {
            setup();
            api.listEvents.mockReturnValue(events([], '0', { state: 'RUNNING', rowVersion: '12' }, [activeStep]));
            await tick(0);
            visibility.mockReturnValue('hidden');
            document.dispatchEvent(new Event('visibilitychange'));
            const afterHide = api.listEvents.mock.calls.length;
            await tick(POLL_ACTIVE_MS + 500);
            expect(api.listEvents).toHaveBeenCalledTimes(afterHide);
            visibility.mockReturnValue('visible');
            document.dispatchEvent(new Event('visibilitychange'));
            await tick(0);
            expect(api.listEvents).toHaveBeenCalledTimes(afterHide + 1);
        });

        it('wakes on the browser coming back online', async () => {
            setup();
            api.listEvents.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 0 })));
            await tick(0);
            expect(store.connection()).toBe('offline');
            api.listEvents.mockReturnValue(events([], '0', { state: 'RUNNING', rowVersion: '12' }));
            window.dispatchEvent(new Event('online'));
            await tick(0);
            expect(store.connection()).toBe('recovered');
            expect(api.listEvents).toHaveBeenCalledTimes(2);
        });

        it('marks the connection offline on a missing network without a request when the browser says so', () => {
            setup();
            window.dispatchEvent(new Event('offline'));
            expect(store.connection()).toBe('offline');
        });

        it('reads on without pausing while a full page of events comes back', async () => {
            setup();
            const full = Array.from({ length: 100 }, (_, index) => wireEvent(index + 1, 'USAGE_UPDATED',
                { reservedCredits: 1, spentCredits: index, balanceRemainingCredits: 5, deferredUntil: null }, null));
            api.listEvents.mockReturnValueOnce(events(full, '100', { state: 'RUNNING', rowVersion: '12' }));
            api.listEvents.mockReturnValue(events([], '100', { state: 'RUNNING', rowVersion: '12' }));
            await tick(0);
            expect(api.listEvents).toHaveBeenCalledTimes(1);
            // A timer set while another fires is due 1 ms later under fake timers; there is no pause of a poll interval.
            await tick(1);
            expect(api.listEvents).toHaveBeenCalledTimes(2);
            expect(api.listEvents.mock.calls[1]![2]).toBe('100');
        });

        it('stops for good in every terminal state of the session', async () => {
            for (const state of ['CLOSED', 'CANCELLED', 'EXPIRED']) {
                TestBed.resetTestingModule();
                setup();
                api.listEvents.mockReturnValue(events([], '4', { state, rowVersion: '14' }));
                api.getSession.mockReturnValue(of(parseSessionDetail(sessionWith([artifactWith(ids.first, 0, 'PUBLISHED')], { state, rowVersion: '14' }))));
                await tick(0);
                const calls = api.listEvents.mock.calls.length;
                await tick(60_000);
                expect(api.listEvents.mock.calls.length, state).toBe(calls);
                expect(store.session()?.state, state).toBe(state);
            }
        });

        it('stops when the component is destroyed', async () => {
            setup();
            api.listEvents.mockReturnValue(events([], '0', { state: 'RUNNING', rowVersion: '12' }, [activeStep]));
            await tick(0);
            TestBed.resetTestingModule();
            const calls = api.listEvents.mock.calls.length;
            await tick(30_000);
            expect(api.listEvents).toHaveBeenCalledTimes(calls);
        });

        it('backs off after a failed poll (1, 2, 4 s ...), reports it, and says the connection is back for one cycle', async () => {
            setup();
            api.listEvents.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 502 })));
            await tick(0);
            expect(store.connection()).toBe('degraded');
            await tick(999);
            expect(api.listEvents).toHaveBeenCalledTimes(1);
            await tick(1);
            expect(api.listEvents).toHaveBeenCalledTimes(2);
            await tick(1999);
            expect(api.listEvents).toHaveBeenCalledTimes(2);
            await tick(1);
            expect(api.listEvents).toHaveBeenCalledTimes(3);
            api.listEvents.mockReturnValue(events([], '0', { state: 'RUNNING', rowVersion: '12' }, [activeStep]));
            await tick(4000);
            expect(store.connection()).toBe('recovered');
            await tick(POLL_ACTIVE_MS);
            expect(store.connection()).toBe('online');
        });

        it('treats a 404 on the events as the session being gone', async () => {
            setup();
            api.listEvents.mockReturnValue(throwError(() => problemResponse(404)));
            await tick(0);
            expect(store.phase()).toBe('missing');
            await tick(60_000);
            expect(api.listEvents).toHaveBeenCalledTimes(1);
        });
    });

    describe('events', () => {
        it('follows the cursor, so a block is never shown twice, and keeps the draft of the artifact being written', async () => {
            setup();
            api.listEvents.mockReturnValueOnce(events([wireBlocks(1, 0, 1, 'Раз')], '1', { state: 'RUNNING', rowVersion: '12' }, [activeStep]));
            await tick(0);
            api.listEvents.mockReturnValueOnce(events([wireBlocks(1, 0, 1, 'Раз'), wireBlocks(2, 1, 1, 'Два')], '2', { state: 'RUNNING', rowVersion: '12' }, [activeStep]));
            await tick(POLL_ACTIVE_MS);
            expect(api.listEvents.mock.calls[1]![2]).toBe('1');
            expect(store.drafts()[ids.second]!.blocks).toHaveLength(2);
            expect(store.arrival()).toMatchObject({ artifactId: ids.second, fromIndex: 1 });
            expect(store.activeSteps()).toHaveLength(1);
        });

        it('reads the session again when an artifact moves on, and marks a loaded detail stale', async () => {
            setup(review());
            api.getArtifact.mockReturnValue(of(parseArtifactDetail({ ...clone(examples['artifactDetailItem']), artifactId: ids.second, ordinal: 1 })));
            store.loadDetail(ids.second);
            api.getSession.mockClear();
            api.listEvents.mockReturnValueOnce(events([proposedEvent(7, ids.second, '9')], '7', { state: 'REVIEW', rowVersion: '13' }));
            await tick(0);
            expect(api.getSession).toHaveBeenCalled();
            expect(store.artifacts()[1]).toMatchObject({ rowVersion: '9' });
        });

        it('moves past an event it cannot read and reads the session instead', async () => {
            setup();
            const bad = wireEvent(1, 'ARTIFACT_STATE', { state: 'FROM_THE_FUTURE', artifactVersion: '4', currentRevisionId: null, errorCode: null, repinStatus: null });
            api.listEvents.mockReturnValueOnce(events([bad], '1', { state: 'RUNNING', rowVersion: '12' }));
            api.getSession.mockClear();
            await tick(0);
            expect(api.getSession).toHaveBeenCalled();
            await tick(POLL_ACTIVE_MS);
            expect(api.listEvents.mock.calls[1]![2]).toBe('1');
        });

        it('notes the deferral of a daily burst and the credits', async () => {
            setup();
            api.listEvents.mockReturnValueOnce(events([wireEvent(1, 'USAGE_UPDATED', { reservedCredits: 21, spentCredits: 11, balanceRemainingCredits: 297,
                deferredUntil: '2026-10-03T21:00:00Z' }, null)], '1', { state: 'RUNNING', rowVersion: '12' }));
            await tick(0);
            expect(store.usage()?.deferredUntil).toBe('2026-10-03T21:00:00Z');
        });
    });

    describe('the stored revision on show', () => {
        const withRevision = (id: string): ArtifactSummary => store.artifacts().find(artifact => artifact.artifactId === id)!;

        it('needs a detail for a proposal, once, and again only when the revision moves on or it is marked stale', async () => {
            setup(review());
            api.getArtifact.mockReturnValue(of(parseArtifactDetail(examples['artifactDetailItem'])));
            const first = withRevision(ids.first);
            expect(store.needsDetail(first)).toBe(true);
            store.loadDetail(ids.first);
            expect(store.details()[ids.first]).toMatchObject({ phase: 'ready', forRevision: ids.revision, stale: false });
            expect(store.needsDetail(first)).toBe(false);
            api.listEvents.mockReturnValueOnce(events([wireEvent(1, 'MEDIA_SLOT_STATE', { slotKey: 'a1', kind: 'AUDIO', state: 'READY',
                assetId: '00000000-0000-4000-a000-000000000001', errorCode: null }, ids.first)], '1', { state: 'REVIEW', rowVersion: '12' }));
            await tick(0);
            expect(store.needsDetail(first)).toBe(true);
        });

        it('reads again a detail that went stale while it was on its way', async () => {
            setup(review());
            const pending = new Subject<ReturnType<typeof parseArtifactDetail>>();
            api.getArtifact.mockReturnValue(pending);
            store.loadDetail(ids.first);
            api.listEvents.mockReturnValueOnce(events([wireEvent(1, 'MEDIA_SLOT_STATE', { slotKey: 'a1', kind: 'AUDIO', state: 'READY',
                assetId: '00000000-0000-4000-a000-000000000001', errorCode: null }, ids.first)], '1', { state: 'REVIEW', rowVersion: '12' }));
            await tick(0);
            expect(store.details()[ids.first]).toMatchObject({ phase: 'loading', stale: false });
            pending.next(parseArtifactDetail(examples['artifactDetailItem']));
            expect(store.details()[ids.first]).toMatchObject({ phase: 'ready', stale: true });
            expect(store.needsDetail(withRevision(ids.first))).toBe(true);
        });

        it('never needs one for a material without a revision or one that is published, and does not hammer a failing read', () => {
            setup(sessionWith([artifactWith(ids.first, 0, 'GENERATING', { currentRevisionId: null }), artifactWith(ids.second, 1, 'PUBLISHED')]));
            expect(store.needsDetail(withRevision(ids.first))).toBe(false);
            expect(store.needsDetail(withRevision(ids.second))).toBe(false);
            setup(review());
            api.getArtifact.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
            store.loadDetail(ids.first);
            expect(store.details()[ids.first]?.phase).toBe('error');
            expect(store.needsDetail(withRevision(ids.first))).toBe(false);
        });

        it('ends the Workshop when the artifact is gone (404)', () => {
            setup(review());
            api.getArtifact.mockReturnValue(throwError(() => problemResponse(404)));
            store.loadDetail(ids.first);
            expect(store.phase()).toBe('missing');
        });
    });

    describe('commands', () => {
        const item = () => parseArtifactDetail(examples['artifactDetailItem']);
        const approvalAck = (artifactIds: string[], deckVersion = '9', replayed = false): ApprovalAck => parseApprovalAck({
            commandId: ids.command, deckId: ids.deckId, deckRevisionId: '33333333-3333-4333-8333-333333333333', deckVersion,
            artifacts: artifactIds.map(artifactId => ({ artifactId, state: 'PUBLISHED',
                publishedRef: { kind: 'ITEM', memberKey: '44444444-4444-4444-8444-444444444444', itemRevisionId: '55555555-5555-4555-8555-555555555555', ordinal: 4 } })) }, replayed);

        function ready(): void {
            setup(review());
            api.getArtifact.mockImplementation((_deck, _session, artifactId) => of(parseArtifactDetail(artifactId === ids.first
                ? examples['artifactDetailItem'] : { ...clone(examples['artifactDetailItem']), artifactId: ids.second, ordinal: 1, rowVersion: '3',
                    currentRevisionId: '4e700000-0000-4000-8000-000000000002',
                    revision: { ...clone(examples['artifactDetailItem']).revision, revisionId: '4e700000-0000-4000-8000-000000000002' } })));
            store.loadDetail(ids.first);
            store.loadDetail(ids.second);
        }

        it('approves exactly what is shown, pinned to the deck, echoes it and marks the material published', async () => {
            ready();
            api.approveArtifact.mockReturnValue(of(approvalAck([ids.first])));
            api.getSession.mockReturnValue(of(parseSessionDetail(sessionWith([artifactWith(ids.first, 0, 'PUBLISHED', { rowVersion: '5' }),
                artifactWith(ids.second, 1, 'PROPOSED', { rowVersion: '3', currentRevisionId: '4e700000-0000-4000-8000-000000000002' })], { state: 'REVIEW' }))));
            expect(await store.approve(ids.first)).toBe(true);
            expect(api.approveArtifact).toHaveBeenCalledWith(ids.deckId, ids.sessionId,
                { artifactId: ids.first, expectedArtifactVersion: '4', expectedRevisionId: ids.revision },
                { rowVersion: '8', revisionId: ids.deckRevision }, expect.stringMatching(/^[0-9a-f-]{36}$/));
            expect(toast.echo).toHaveBeenCalledWith('Материал одобрен');
            expect(store.artifacts()[0]).toMatchObject({ state: 'PUBLISHED' });
            expect(store.isBusy(ids.first)).toBe(false);
        });

        it('chains the deck pin: the next approval carries the version the last one acknowledged', async () => {
            ready();
            api.approveArtifact.mockReturnValueOnce(of(approvalAck([ids.first], '9'))).mockReturnValueOnce(of(approvalAck([ids.second], '10')));
            await store.approve(ids.first);
            await store.approve(ids.second);
            expect(api.approveArtifact.mock.calls[1]![3]).toEqual({ rowVersion: '9', revisionId: '33333333-3333-4333-8333-333333333333' });
        });

        it('refuses to approve a version the user has not seen (the revision moved on) and says so', async () => {
            ready();
            expect(await store.approve(second)).toBe(false);
            api.getArtifact.mockReturnValue(of(parseArtifactDetail({ ...clone(examples['artifactDetailItem']), currentRevisionId: '4e700000-0000-4000-8000-0000000000aa',
                revision: { ...clone(examples['artifactDetailItem']).revision, revisionId: '4e700000-0000-4000-8000-0000000000aa' } })));
            store.loadDetail(ids.first);
            expect(await store.approve(ids.first)).toBe(false);
            expect(store.notice()).toMatchObject({ tone: 'info', text: expect.stringContaining('Проверьте новую версию') });
            expect(api.approveArtifact).not.toHaveBeenCalled();
        });

        it('refuses what the state does not allow: a material still being written, or a cancelled session retrying', async () => {
            setup(running());
            expect(await store.approve(ids.second)).toBe(false);
            expect(await store.retry(ids.second)).toBe(false);
            setup(sessionWith([artifactWith(ids.first, 0, 'FAILED', { errorCode: 'PROVIDER_UNAVAILABLE', currentRevisionId: null })], { state: 'CANCELLED' }));
            expect(await store.retry(ids.first)).toBe(false);
            setup(sessionWith([artifactWith(ids.first, 0, 'FAILED', { errorCode: 'REFUSAL', currentRevisionId: null })], { state: 'REVIEW' }));
            expect(await store.retry(ids.first)).toBe(false);
            expect(api.retryArtifact).not.toHaveBeenCalled();
        });

        it('retries once with a refreshed deck pin when the deck moved but the proposal is the same one', async () => {
            ready();
            api.approveArtifact.mockReturnValueOnce(throwError(() => problemResponse(412, { code: 'VERSION_CONFLICT' }))).mockReturnValueOnce(of(approvalAck([ids.first], '10')));
            decks.detail.mockReturnValue(of({ ...deckFixture, rowVersion: '9' }));
            expect(await store.approve(ids.first)).toBe(true);
            expect(api.approveArtifact).toHaveBeenCalledTimes(2);
            expect(api.approveArtifact.mock.calls[1]![3].rowVersion).toBe('9');
            expect(api.approveArtifact.mock.calls[1]![4]).not.toBe(api.approveArtifact.mock.calls[0]![4]);
        });

        it('gives up after a second stale answer and shows the server truth', async () => {
            ready();
            api.approveArtifact.mockReturnValue(throwError(() => problemResponse(412, { code: 'VERSION_CONFLICT' })));
            expect(await store.approve(ids.first)).toBe(false);
            expect(api.approveArtifact).toHaveBeenCalledTimes(2);
            expect(store.notice()).toMatchObject({ tone: 'error', text: expect.stringContaining('изменились') });
        });

        it('sends the very same command again after an unknown outcome, and a new one after a definitive refusal', async () => {
            ready();
            api.approveArtifact.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 0 })));
            expect(await store.approve(ids.first)).toBe(false);
            expect(store.notice()?.text).toContain('та же команда');
            api.approveArtifact.mockReturnValueOnce(throwError(() => problemResponse(409, { code: 'GENERATION_STATE_CONFLICT', reason: 'MEDIA_NOT_READY' })));
            await store.approve(ids.first);
            api.approveArtifact.mockReturnValueOnce(of(approvalAck([ids.first], '9', true)));
            await store.approve(ids.first);
            const [uncertain, refused, third] = api.approveArtifact.mock.calls.map(call => call[4]);
            expect(refused).toBe(uncertain);
            expect(third).not.toBe(refused);
        });

        it('tells when the deck cannot be read for the pin', async () => {
            setup(review(), false);
            expect(store.deckTitle()).toBeNull();
            api.getArtifact.mockReturnValue(of(item()));
            store.loadDetail(ids.first);
            expect(await store.approve(ids.first)).toBe(false);
            expect(store.notice()?.text).toContain('Не удалось прочитать колоду');
            expect(await store.approveAll()).toBe(0);
        });

        it('approves all ready materials in chunks of 20, one atomic command each, chaining the pin, and echoes the total', async () => {
            const many = Array.from({ length: 25 }, (_, position) => artifactWith(`a7a70000-0000-4000-8000-${String(position + 1).padStart(12, '0')}`, position, 'PROPOSED',
                { currentRevisionId: `4e700000-0000-4000-8000-${String(position + 1).padStart(12, '0')}` }));
            setup(sessionWith(many, { state: 'REVIEW' }));
            api.approveArtifacts.mockImplementation((_d, _s, targets) => of(approvalAck(targets.map(target => target.artifactId), String(8 + api.approveArtifacts.mock.calls.length))));
            expect(await store.approveAll()).toBe(25);
            expect(api.approveArtifacts.mock.calls.map(call => call[2].length)).toEqual([20, 5]);
            expect(api.approveArtifacts.mock.calls[0]![3]).toEqual({ rowVersion: '8', revisionId: ids.deckRevision });
            expect(api.approveArtifacts.mock.calls[1]![3].rowVersion).toBe('9');
            expect(toast.echo).toHaveBeenCalledWith('Одобрено материалов: 25');
            expect(store.isBusy('session')).toBe(false);
        });

        it('stops at the first failing chunk and says how many were published', async () => {
            const many = Array.from({ length: 22 }, (_, position) => artifactWith(`a7a70000-0000-4000-8000-${String(position + 1).padStart(12, '0')}`, position, 'PROPOSED',
                { currentRevisionId: `4e700000-0000-4000-8000-${String(position + 1).padStart(12, '0')}` }));
            setup(sessionWith(many, { state: 'REVIEW' }));
            api.approveArtifacts.mockImplementationOnce((_d, _s, targets) => of(approvalAck(targets.map(target => target.artifactId), '9')))
                .mockReturnValueOnce(throwError(() => problemResponse(409, { code: 'GENERATION_STATE_CONFLICT', reason: 'MEDIA_NOT_READY', artifactIds: [many[21]!['artifactId'] as string] })));
            expect(await store.approveAll()).toBe(20);
            expect(store.notice()?.text).toContain('Одобрено материалов: 20.');
            expect(api.approveArtifacts).toHaveBeenCalledTimes(2);
        });

        it('has nothing to approve when no material is ready, in a state that does not allow it, or while a command is running', async () => {
            setup(running());
            expect(await store.approveAll()).toBe(0);
            setup(sessionWith([artifactWith(ids.first, 0, 'PROPOSED')], { state: 'CLOSED' }));
            expect(await store.approveAll()).toBe(0);
            expect(api.approveArtifacts).not.toHaveBeenCalled();
        });

        it('rejects and undoes, patching the material at once and reading the session after', async () => {
            ready();
            const rejectedBody = httpContract['endpoints'].find((e: any) => e.operationId === 'rejectArtifact').success.body;
            api.rejectArtifact.mockReturnValue(of(parseArtifactSummary(rejectedBody)));
            expect(await store.reject(ids.first)).toBe(true);
            expect(api.rejectArtifact).toHaveBeenCalledWith(ids.deckId, ids.sessionId, ids.first, '4', expect.any(String));
            expect(store.artifacts()[0]).toMatchObject({ state: 'REJECTED', rowVersion: '5' });
            api.undoRejectArtifact.mockReturnValue(of(parseArtifactSummary({ ...clone(rejectedBody), state: 'PROPOSED', rowVersion: '6' })));
            expect(await store.undoReject(ids.first)).toBe(true);
            expect(api.undoRejectArtifact).toHaveBeenCalledWith(ids.deckId, ids.sessionId, ids.first, '5');
            expect(store.artifacts()[0]).toMatchObject({ state: 'PROPOSED', rowVersion: '6' });
            expect(await store.undoReject(ids.first)).toBe(false);
        });

        it('retries a failed material and puts the loop back to work', async () => {
            setup(sessionWith([artifactWith(ids.first, 0, 'FAILED', { errorCode: 'PROVIDER_UNAVAILABLE', currentRevisionId: null, rowVersion: '2' })], { state: 'REVIEW' }));
            const body = httpContract['endpoints'].find((e: any) => e.operationId === 'retryArtifact').success.body;
            api.retryArtifact.mockReturnValue(of(parseArtifactSummary(body)));
            api.listEvents.mockClear();
            expect(await store.retry(ids.first)).toBe(true);
            expect(api.retryArtifact).toHaveBeenCalledWith(ids.deckId, ids.sessionId, ids.first, '2', expect.any(String));
            expect(store.artifacts()[0]).toMatchObject({ state: 'QUEUED' });
            await tick(0);
            expect(api.listEvents).toHaveBeenCalled();
        });

        it('hands off the shown revision and returns the draft', async () => {
            ready();
            const body = httpContract['endpoints'].find((e: any) => e.operationId === 'handoffArtifact').success.body;
            api.handoffArtifact.mockReturnValue(of(parseHandoff(body)));
            const result = await store.handoff(ids.first);
            expect(result?.draft.draftId).toBe('d4af7000-0000-4000-8000-000000000001');
            expect(api.handoffArtifact).toHaveBeenCalledWith(ids.deckId, ids.sessionId,
                { artifactId: ids.first, expectedArtifactVersion: '4', expectedRevisionId: ids.revision }, expect.any(String));
            expect(store.artifacts()[0]).toMatchObject({ state: 'HANDED_OFF' });
            api.handoffArtifact.mockReturnValue(throwError(() => problemResponse(422, { code: 'RESOURCE_LIMIT_EXCEEDED', limit: 'EDITING_DRAFTS' })));
            expect(await store.handoff(ids.second)).toBeNull();
            expect(store.notice()?.text).toContain('черновиков');
        });

        it('does not hand off what is not on screen yet or not allowed', async () => {
            setup(review());
            expect(await store.handoff(ids.first)).toBeNull();
            expect(api.handoffArtifact).not.toHaveBeenCalled();
        });

        it('stops the session: the loop ends and the proposals stay', async () => {
            setup();
            api.cancelSession.mockReturnValue(of(parseSessionDetail(examples['sessionDetailCancelled'])));
            expect(await store.cancel()).toBe(true);
            expect(store.session()?.state).toBe('CANCELLED');
            api.listEvents.mockClear();
            await tick(60_000);
            expect(api.listEvents).not.toHaveBeenCalled();
            expect(await store.cancel()).toBe(false);
        });

        it('keeps the session when stopping fails, and says why', async () => {
            setup();
            api.cancelSession.mockReturnValue(throwError(() => problemResponse(409, { code: 'GENERATION_STATE_CONFLICT', reason: 'ILLEGAL_STATE' })));
            expect(await store.cancel()).toBe(false);
            expect(store.notice()?.tone).toBe('error');
        });

        it('deletes the session, counting a repeat after a lost answer (404) as done, and keeps it on any other failure', async () => {
            setup();
            api.deleteSession.mockReturnValue(of(undefined));
            expect(await store.deleteSession()).toBe(true);
            api.deleteSession.mockReturnValue(throwError(() => problemResponse(404)));
            expect(await store.deleteSession()).toBe(true);
            api.deleteSession.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
            expect(await store.deleteSession()).toBe(false);
            expect(store.notice()?.tone).toBe('error');
        });

        it('marks the Workshop gone when a command meets a 404, and can clear its notice', async () => {
            ready();
            api.rejectArtifact.mockReturnValue(throwError(() => problemResponse(404)));
            await store.reject(ids.first);
            expect(store.phase()).toBe('missing');
            store.clearNotice();
            expect(store.notice()).toBeNull();
        });

        it('refuses a second command on the same material while one is running', async () => {
            ready();
            const pending = new Subject<ReturnType<typeof parseArtifactSummary>>();
            api.rejectArtifact.mockReturnValue(pending);
            const first = store.reject(ids.first);
            expect(store.isBusy(ids.first)).toBe(true);
            expect(await store.reject(ids.first)).toBe(false);
            pending.next(parseArtifactSummary({ ...clone(examples['artifactSummaryProposed']), state: 'REJECTED', rowVersion: '5' }));
            pending.complete();
            expect(await first).toBe(true);
        });
    });

    describe('archiving the used notes (#290)', () => {
        const published = (): Record<string, unknown>[] => [artifactWith(ids.first, 0, 'PUBLISHED', { rowVersion: '5' }),
            artifactWith(ids.second, 1, 'PUBLISHED', { rowVersion: '5' })];
        const withNotes = (archivable: number, used = 3): Record<string, unknown> =>
            sessionWithNotes({ used, archivable }, published(), { state: 'CLOSED', approvableCount: 0 });
        const answer = (archived: string[], skipped: { noteId: string; reason: string }[] = [], replayed = false) =>
            parseNoteArchive(noteArchiveAnswer(archived, skipped), replayed);

        it('knows how many notes are archivable, and none while the server does not report it', () => {
            setup(withNotes(2));
            expect(store.archivableNotes()).toBe(2);
            setup(sessionWith(published(), { state: 'CLOSED' }));
            expect(store.archivableNotes()).toBe(0);
        });

        it('does nothing without archivable notes, and never asks the server', async () => {
            setup(withNotes(0));
            expect(await store.archiveNotes()).toBe(false);
            setup(sessionWith(published(), { state: 'CLOSED' }));
            expect(await store.archiveNotes()).toBe(false);
            expect(api.archiveUsedNotes).not.toHaveBeenCalled();
        });

        it('archives with a command id, keeps the result for the page, echoes a summary and reads the session again', async () => {
            setup(withNotes(2));
            api.archiveUsedNotes.mockReturnValue(of(answer([noteIds.first], [{ noteId: noteIds.second, reason: 'CHANGED' }])));
            api.getSession.mockReturnValue(of(parseSessionDetail(withNotes(0))));
            expect(await store.archiveNotes()).toBe(true);
            expect(api.archiveUsedNotes).toHaveBeenCalledWith(ids.deckId, ids.sessionId, expect.stringMatching(/^[0-9a-f-]{36}$/));
            expect(store.noteArchive()).toMatchObject({ archived: [noteIds.first], skipped: [{ noteId: noteIds.second, reason: 'CHANGED' }] });
            expect(toast.echo).toHaveBeenCalledWith('Архивировано: 1, пропущено: 1 — заметка изменилась');
            expect(store.archivableNotes()).toBe(0);
            expect(store.isBusy('notes')).toBe(false);
        });

        it('sends the same command again after an unknown outcome, and a new one after a definitive answer', async () => {
            setup(withNotes(2));
            api.archiveUsedNotes.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 0 })));
            expect(await store.archiveNotes()).toBe(false);
            expect(store.notice()?.tone).toBe('error');
            expect(store.noteArchive()).toBeNull();
            api.archiveUsedNotes.mockReturnValueOnce(of(answer([noteIds.first], [], true)));
            expect(await store.archiveNotes()).toBe(true);
            api.archiveUsedNotes.mockReturnValueOnce(of(answer([])));
            await store.archiveNotes();
            const [uncertain, retried, later] = api.archiveUsedNotes.mock.calls.map(call => call[2]);
            expect(retried).toBe(uncertain);
            expect(later).not.toBe(retried);
        });

        it('refuses a second press while the first is running, so one press archives once', async () => {
            setup(withNotes(2));
            const pending = new Subject<ReturnType<typeof answer>>();
            api.archiveUsedNotes.mockReturnValue(pending);
            const first = store.archiveNotes();
            expect(store.isBusy('notes')).toBe(true);
            expect(await store.archiveNotes()).toBe(false);
            expect(api.archiveUsedNotes).toHaveBeenCalledTimes(1);
            pending.next(answer([noteIds.first]));
            pending.complete();
            expect(await first).toBe(true);
        });

        it('shows the refusal in words and treats a 404 as the Workshop being gone', async () => {
            setup(withNotes(2));
            api.archiveUsedNotes.mockReturnValueOnce(throwError(() => problemResponse(409, { code: 'IDEMPOTENCY_CONFLICT' })));
            expect(await store.archiveNotes()).toBe(false);
            expect(store.notice()?.tone).toBe('error');
            api.archiveUsedNotes.mockReturnValueOnce(throwError(() => problemResponse(404)));
            await store.archiveNotes();
            expect(store.phase()).toBe('missing');
        });

        it('forgets the result of an earlier session when another one is opened', async () => {
            setup(withNotes(2));
            api.archiveUsedNotes.mockReturnValue(of(answer([noteIds.first])));
            await store.archiveNotes();
            expect(store.noteArchive()).not.toBeNull();
            store.open(ids.deckId, ids.sessionId);
            expect(store.noteArchive()).toBeNull();
        });
    });
});
