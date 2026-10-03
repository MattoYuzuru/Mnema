import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject, of, throwError } from 'rxjs';

import { MediaPlaybackApi } from '../../content/rendering/media-playback.api';
import { ToastService } from '../../core/notifications/toast.service';
import { PageTransition } from '../../shared/page-transition.service';
import { spyObj, type SpyObj } from '../../../testing/mocks';
import { ExercisePreviewApiService } from '../authoring/exercise-preview-api.service';
import { OwnDecksApiService } from '../own-decks/own-decks-api.service';
import { audioAssetIds, exerciseTextLines } from './exercise-text';
import { createCommand, documentOf, exerciseDetail, exerciseIds, materialIds, materialRevisions, selfCheck } from './exercise-test-data';
import { GenerationApiService } from './generation-api.service';
import { activeStep, artifactWith, clone, deckFixture, eventsEnvelope, examples, ids, problemResponse, sessionWith } from './generation-test-data';
import {
    parseApprovalAck, parseArtifactDetail, parseArtifactSummary, parseEditAccepted, parseEventsPage, parseSessionDetail
} from './generation.models';
import { ANNOUNCE_GAP_MS, WorkshopPageComponent } from './workshop-page.component';
import { WorkshopSessionStore } from './workshop-session.store';

const ORIGINAL = '4e700000-0000-4000-8000-0000000000a1';
const REWRITTEN = '4e700000-0000-4000-8000-0000000000a2';
const MEDIA = '4e700000-0000-4000-8000-0000000000a3';
const TURN = '7a7a0000-0000-4000-8000-0000000000b1';
const VOICE_TURN = '7a7a0000-0000-4000-8000-0000000000b2';
const NEW_REVISION = '55555555-5555-4555-8555-5555555555ff';
const ASSET = '00000000-0000-4000-a000-0000000000a1';
const OTHER_ASSET = '00000000-0000-4000-a000-0000000000a2';
const OLD_TEXT = ['Seq Scan читает всю таблицу.', 'Index Scan идёт по индексу.'];
const NEW_TEXT = ['Seq Scan читает всю таблицу целиком.', 'Index Scan идёт по индексу.'];

const turn = (change: Record<string, unknown> = {}) => ({ ...clone(examples['turnQueued']), turnId: TURN, status: 'APPLIED', action: 'FREE', preset: null,
    instruction: 'Сделай объяснение проще', targetNodeIds: [], resultRevisionId: REWRITTEN, voice: null, ...change });

describe('Workshop of a revision (REVISE_ITEM and REVISE_EXERCISE, AI-16)', () => {
    let fixture: ComponentFixture<WorkshopPageComponent>;
    let api: SpyObj<GenerationApiService>;
    let toast: { echo: ReturnType<typeof vi.fn> };
    let store: WorkshopSessionStore;
    let session: Record<string, any>;
    let detail: Record<string, any>;
    let originals: Record<string, Record<string, any>>;
    let failCurrent = false;

    const root = (): HTMLElement => fixture.nativeElement as HTMLElement;
    const labelled = (label: string): HTMLButtonElement | undefined =>
        [...root().querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent!.trim() === label);
    const summary = (): string => root().querySelector('.summary')!.textContent!.replace(/\u00a0/g, ' ');

    async function settle(): Promise<void> {
        fixture.detectChanges();
        await vi.advanceTimersByTimeAsync(0);
        fixture.detectChanges();
        await vi.advanceTimersByTimeAsync(0);
        fixture.detectChanges();
    }

    // --- A material revision: the head copied (ORIGINAL), then rewritten by a turn over every block (REWRITTEN) ---

    const itemArtifact = (state = 'PROPOSED', change: Record<string, unknown> = {}) => artifactWith(ids.first, 0, state, { rowVersion: '5',
        currentRevisionId: state === 'REVISING' ? ORIGINAL : REWRITTEN, ...change });
    const itemDetail = (revision: string, texts: string[], turns: unknown[], change: Record<string, unknown> = {}) => {
        const base = clone(examples['artifactDetailItem']);
        return { ...base, currentRevisionId: revision, rowVersion: '5', mediaSlots: [], sourceRefs: [],
            revision: { ...base.revision, revisionId: revision, payload: { kind: 'NATIVE_DOCUMENT', document: documentOf(...texts) } },
            revisions: [{ revisionId: ORIGINAL, cause: 'INITIAL', createdAt: '2026-10-02T09:00:00Z' },
                ...(revision === ORIGINAL ? [] : [{ revisionId: REWRITTEN, cause: 'EDIT', createdAt: '2026-10-02T09:00:30Z' }])],
            turns, ...change };
    };
    const itemSession = (artifact: Record<string, unknown>, change: Record<string, unknown> = {}) => sessionWith([artifact], { kind: 'REVISE_ITEM', state: 'REVIEW',
        approvableCount: artifact['state'] === 'PROPOSED' ? 1 : 0, spec: { kind: 'REVISE_ITEM', target: { memberKey: materialIds.first, itemRevisionId: materialRevisions.first },
            instruction: 'Сделай объяснение проще' }, ...change });

    // --- An exercise revision: the exercise copied (ORIGINAL), rewritten (REWRITTEN) and its voice redone (MEDIA) ---

    const withAudio = (assetId: string, prompt = 'Когда планировщик выберет Seq Scan?') => {
        const exercise = selfCheck({ memberKey: materialIds.first, itemRevisionId: materialRevisions.first }, prompt) as any;
        exercise.content.prompt = [...exercise.content.prompt, { kind: 'AUDIO', assetId, title: 'Озвучка вопроса' }];
        return exercise;
    };
    const exerciseArtifact = (state = 'PROPOSED', revision = MEDIA, change: Record<string, unknown> = {}) => ({ ...clone(examples['artifactSummaryProposed']), artifactId: ids.first,
        ordinal: 0, targetKind: 'EXERCISE', state, rowVersion: '5', title: 'Выбор', currentRevisionId: revision, mediaSlotCounts: { total: 1, ready: 1, failed: 0 }, ...change });
    const exerciseRevision = (revision: string, asset: string, prompt: string, turns: unknown[], voice: string | null = 'male') => {
        const base = exerciseDetail(ids.first, 0, createCommand(selfCheck(), 'Выбор'), {}, 'absent');
        const display = { mechanic: 'SELF_CHECK', objectiveTitle: 'Выбор', quotes: { '00000000-0000-4000-8000-000000000004': 'Seq Scan читает всю таблицу.' } };
        const command = { objective: { operation: 'reuse', objectiveId: exerciseIds.objective, objectiveRevisionId: exerciseIds.objectiveRevision },
            exercise: withAudio(asset, prompt) };
        return { ...base, display, currentRevisionId: revision, rowVersion: '5', title: 'Выбор', mediaSlotCounts: { total: 1, ready: 1, failed: 0 },
            revision: { ...base['revision'], revisionId: revision, payload: { kind: 'EXERCISE_COMMAND', command } },
            mediaSlots: [{ slotKey: 'audio1', kind: 'AUDIO', nodeId: '00000000-0000-4000-8000-0000000000c1', assetId: asset, state: 'READY', errorCode: null, voice }],
            revisions: [{ revisionId: ORIGINAL, cause: 'INITIAL', createdAt: '2026-10-02T09:00:00Z' },
                ...(revision === ORIGINAL ? [] : [{ revisionId: revision, cause: revision === MEDIA ? 'MEDIA' : 'EDIT', createdAt: '2026-10-02T09:00:40Z' }])], turns };
    };
    const exerciseSession = (artifact: Record<string, unknown>, spec: Record<string, unknown>, change: Record<string, unknown> = {}) => sessionWith([artifact],
        { kind: 'REVISE_EXERCISE', state: 'REVIEW', approvableCount: artifact['state'] === 'PROPOSED' ? 1 : 0, spec: { kind: 'REVISE_EXERCISE', target: {
            exerciseId: exerciseIds.exercise, exerciseRevisionId: exerciseIds.exerciseRevision }, ...spec }, ...change });
    const voiceOnly = { media: { action: 'AUDIO_REGENERATE', voice: 'male' } };
    const voiceTurn = (change: Record<string, unknown> = {}) => turn({ turnId: VOICE_TURN, action: 'AUDIO_REGENERATE', instruction: null, voice: 'male', resultRevisionId: MEDIA, ...change });

    async function open(sessionBody: Record<string, any>, detailBody: Record<string, any>, extraOriginals: Record<string, Record<string, any>> = {}): Promise<void> {
        TestBed.resetTestingModule();
        vi.useFakeTimers();
        vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
        session = sessionBody;
        detail = detailBody;
        originals = extraOriginals;
        api = spyObj<GenerationApiService>({ getSession: vi.fn(), listEvents: vi.fn(), getArtifact: vi.fn(), approveArtifact: vi.fn(), rejectArtifact: vi.fn(),
            undoRejectArtifact: vi.fn(), editArtifact: vi.fn(), revertArtifact: vi.fn(), cancelSession: vi.fn(), deleteSession: vi.fn() });
        toast = { echo: vi.fn() };
        api.getSession.mockImplementation(() => of(parseSessionDetail(session)));
        api.listEvents.mockReturnValue(of(parseEventsPage(eventsEnvelope([], '0', { state: 'REVIEW', rowVersion: '12' }, []))));
        api.getArtifact.mockImplementation((_deck, _session, _artifact, revisionId) => {
            if (failCurrent && (revisionId === null || revisionId === undefined)) return throwError(() => new Error('offline'));
            const body = revisionId === null || revisionId === undefined ? detail : originals[revisionId] ?? detail;
            return of(parseArtifactDetail(body, revisionId ?? null));
        });
        const params = new BehaviorSubject(convertToParamMap({ deckId: ids.deckId, sessionId: ids.sessionId }));
        const query = new BehaviorSubject(convertToParamMap({}));
        TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: GenerationApiService, useValue: api },
            { provide: OwnDecksApiService, useValue: { detail: () => of(deckFixture) } }, { provide: ToastService, useValue: toast },
            { provide: PageTransition, useValue: { navigate: vi.fn().mockResolvedValue(true) } },
            { provide: ExercisePreviewApiService, useValue: { submit: vi.fn(), checkPair: vi.fn(), hint: vi.fn() } },
            { provide: MediaPlaybackApi, useValue: { read: () => new Promise(() => {}) } },
            { provide: ActivatedRoute, useValue: { paramMap: params, queryParamMap: query, snapshot: { paramMap: params.value, queryParamMap: query.value } } }] });
        fixture = TestBed.createComponent(WorkshopPageComponent);
        store = fixture.debugElement.injector.get(WorkshopSessionStore);
        await settle();
    }

    const openItem = (state = 'PROPOSED', turns: unknown[] = [turn()], revision = REWRITTEN, texts = NEW_TEXT) => open(itemSession(itemArtifact(state)),
        itemDetail(revision, texts, turns), { [ORIGINAL]: itemDetail(ORIGINAL, OLD_TEXT, []) });
    const openExercise = (spec: Record<string, unknown> = voiceOnly, turns: unknown[] = [voiceTurn()], asset = ASSET, state = 'PROPOSED') => open(
        exerciseSession(exerciseArtifact(state), spec), exerciseRevision(MEDIA, asset, 'Когда планировщик выберет Seq Scan?', turns),
        { [ORIGINAL]: exerciseRevision(ORIGINAL, ASSET, 'Когда планировщик выберет Seq Scan?', [], null) });

    afterEach(() => { vi.useRealTimers(); failCurrent = false; });

    describe('the material (REVISE_ITEM)', () => {
        it('is the Workshop of a revision: its heading, the request, no pager, and one card with the three answers the owner can give', async () => {
            await openItem();
            expect(root().querySelector('h1')!.textContent).toBe('Правка материала');
            expect(root().querySelector('.lede')!.textContent).toContain('Запрос: «Сделай объяснение проще»');
            expect(root().querySelector('app-batch-pager')).toBeNull();
            expect(root().querySelector('app-revise-item-result')).not.toBeNull();
            expect(root().querySelector('.result-title')!.textContent).toBe('Мнема переписала материал');
            expect(root().querySelector('.result-request')!.textContent).toContain('Сделай объяснение проще');
            expect([...root().querySelectorAll('.result-actions button')].map(button => button.textContent!.trim())).toEqual(['Оставить', 'Вернуть', 'Ещё раз', 'Отклонить']);
            expect(root().querySelector('h2')!.textContent).toContain('Правка материала, готов');
            expect(summary()).toContain('Правка готова: оставьте её, верните прежний текст или попросите ещё раз');
            // the document is the #293 proposal document, without the strip that would say «Оставить» a second time
            expect(root().querySelector('app-proposal-document')!.textContent).toContain('целиком');
            expect(root().querySelector('.rewrite-strip')).toBeNull();
        });

        it('shows what changed against the original as a word diff, open, with the words as text and not only as colour', async () => {
            await openItem();
            await settle();
            const diff = root().querySelector<HTMLDetailsElement>('.revise-diff')!;
            expect(diff.open).toBe(true);
            expect(diff.querySelector('ins')!.textContent).toContain('целиком');
            expect(diff.querySelector('ins .sr-only')!.textContent).toBe('добавлено: ');
            expect(diff.textContent).toContain('Index Scan идёт по индексу.');
            expect(api.getArtifact).toHaveBeenCalledWith(ids.deckId, ids.sessionId, ids.first, ORIGINAL);
        });

        it('«Оставить» approves the revision exactly as shown (an ordinary revise), says it in a toast and links to the material', async () => {
            await openItem();
            api.approveArtifact.mockReturnValue(of(parseApprovalAck({ commandId: ids.command, deckId: ids.deckId, deckRevisionId: ids.deckRevision, deckVersion: '9',
                artifacts: [{ artifactId: ids.first, state: 'PUBLISHED', publishedRef: { kind: 'ITEM', memberKey: materialIds.first, itemRevisionId: NEW_REVISION, ordinal: 2 } }] }, false)));
            session = itemSession(itemArtifact('PUBLISHED', { rowVersion: '6', publishedRef: { kind: 'ITEM', memberKey: materialIds.first, itemRevisionId: NEW_REVISION, ordinal: 2 } }),
                { state: 'CLOSED' });
            labelled('Оставить')!.click();
            await settle();
            const [deck, sessionId, target, pin] = api.approveArtifact.mock.calls[0]!;
            expect([deck, sessionId]).toEqual([ids.deckId, ids.sessionId]);
            expect(target).toEqual({ artifactId: ids.first, expectedArtifactVersion: '5', expectedRevisionId: REWRITTEN });
            expect(pin).toMatchObject({ rowVersion: deckFixture.rowVersion, revisionId: deckFixture.revisionId });
            expect(toast.echo).toHaveBeenCalledWith('Новая версия материала сохранена');
            expect(store.artifacts()[0]!.state).toBe('PUBLISHED');
            expect(root().querySelector('.notice.success')!.textContent).toContain('Новая версия материала сохранена');
            expect(root().querySelector('.notice.success a')!.getAttribute('href')).toBe(`/decks/${ids.deckId}/materials/${materialIds.first}`);
            expect(root().querySelector('.result-card')).toBeNull();
            expect(document.activeElement).toBe(root().querySelector('h2'));
        });

        it('explains a head that moved while the owner read (SOURCE_STALE), and keeps the card', async () => {
            await openItem();
            api.approveArtifact.mockReturnValue(throwError(() => problemResponse(409, { code: 'GENERATION_STATE_CONFLICT', reason: 'SOURCE_STALE' })));
            labelled('Оставить')!.click();
            await settle();
            expect(root().querySelector('.notice.error')!.textContent).toContain('В колоде остался прежний текст');
        });

        it('«Вернуть» goes back to the text the revision started from; then there is nothing to keep, and the card can close without a change', async () => {
            await openItem();
            api.revertArtifact.mockReturnValue(of(parseArtifactSummary({ ...itemArtifact('PROPOSED'), currentRevisionId: ORIGINAL, rowVersion: '6' })));
            detail = itemDetail(ORIGINAL, OLD_TEXT, [turn()]);
            labelled('Вернуть')!.click();
            await settle();
            expect(api.revertArtifact).toHaveBeenCalledWith(ids.deckId, ids.sessionId, ids.first, '5', ORIGINAL, expect.stringMatching(/^[0-9a-f-]{36}$/u));
            await settle();
            expect(root().querySelector('.result-title')!.textContent).toContain('Сейчас показан прежний текст');
            expect([...root().querySelectorAll('.result-actions button')].map(button => button.textContent!.trim())).toEqual(['Ещё раз', 'Закрыть без изменений']);
            expect(root().querySelector('.revise-diff')).toBeNull();
            expect(document.activeElement).toBe(root().querySelector('h2'));
        });

        it('«Ещё раз» asks the same request of the text on screen, over every block, as a free edit on the revision shown', async () => {
            await openItem();
            api.editArtifact.mockReturnValue(of(parseEditAccepted({ turn: turn({ turnId: VOICE_TURN, status: 'QUEUED', resultRevisionId: null, targetNodeIds: [] }),
                artifact: itemArtifact('REVISING', { rowVersion: '6', currentRevisionId: REWRITTEN }) }, false)));
            session = itemSession(itemArtifact('REVISING', { rowVersion: '6' }), { state: 'RUNNING' });
            labelled('Ещё раз')!.click();
            await settle();
            await vi.advanceTimersByTimeAsync(ANNOUNCE_GAP_MS);
            fixture.detectChanges();
            const [, , artifactId, request] = api.editArtifact.mock.calls[0]!;
            expect(artifactId).toBe(ids.first);
            expect(request).toMatchObject({ expectedRevisionId: REWRITTEN, action: 'FREE', instruction: 'Сделай объяснение проще' });
            expect(request.nodeIds).toHaveLength(2);
            expect(store.artifacts()[0]!.state).toBe('REVISING');
            expect(summary()).toContain('Мнема правит материал');
        });

        it('says what Мнема is doing while the first turn runs: the original can be read, and nothing can be decided yet', async () => {
            await open(itemSession(itemArtifact('REVISING'), { state: 'RUNNING' }), itemDetail(ORIGINAL, OLD_TEXT, [turn({ status: 'RUNNING', resultRevisionId: null })]));
            expect(root().querySelector('article')!.getAttribute('aria-busy')).toBe('true');
            expect(root().querySelector('.muted')!.textContent).toContain('Мнема переписывает материал');
            expect(root().querySelector('.result-card')).toBeNull();
            expect(root().querySelector('app-proposal-document')!.textContent).toContain('Seq Scan читает всю таблицу.');
        });

        it('says a failed turn changed nothing and charged nothing, with «Ещё раз» and no «Оставить»', async () => {
            await open(itemSession(itemArtifact('PROPOSED', { currentRevisionId: ORIGINAL })), itemDetail(ORIGINAL, OLD_TEXT,
                [turn({ status: 'FAILED', errorCode: 'REFUSAL', resultRevisionId: null })]));
            expect(root().querySelector('.result-title')!.textContent).toContain('Мнема отказалась переписывать этот фрагмент');
            expect(root().querySelector('.result-title')!.textContent).toContain('лимит не списан');
            expect([...root().querySelectorAll('.result-actions button')].map(button => button.textContent!.trim())).toEqual(['Ещё раз', 'Закрыть без изменений']);
            await open(itemSession(itemArtifact('PROPOSED', { currentRevisionId: ORIGINAL })), itemDetail(ORIGINAL, OLD_TEXT,
                [turn({ status: 'CANCELLED', resultRevisionId: null })]));
            expect(root().querySelector('.result-title')!.textContent).toContain('Правка остановлена');
        });

        it('shows a head that moved as a state of its own, a rejected revision with a way back, and one that was saved elsewhere', async () => {
            await openItem('STALE');
            expect(root().textContent).toContain('Материал изменился, пока Мнема его правила');
            expect(labelled('Закрыть правку')).toBeDefined();
            expect(root().querySelector('.result-card')).toBeNull();
            await openItem('REJECTED');
            expect(root().textContent).toContain('Вы отклонили правку');
            expect(labelled('Вернуть правку')).toBeDefined();
            await open(itemSession(itemArtifact('PUBLISHED', { publishedRef: { kind: 'ITEM', memberKey: materialIds.first, itemRevisionId: NEW_REVISION, ordinal: 2 } }),
                { state: 'CLOSED' }), itemDetail(REWRITTEN, NEW_TEXT, [turn()]));
            expect(root().querySelector('.notice.success')!.textContent).toContain('Прежний текст остался в истории материала');
            expect(root().textContent).toContain('Правка разобрана');
            await openItem('FAILED');
            expect(root().querySelector('.notice.error')).not.toBeNull();
        });

        it('rejects the revision, and brings a rejected one back', async () => {
            await openItem();
            api.rejectArtifact.mockReturnValue(of(parseArtifactSummary(itemArtifact('REJECTED', { rowVersion: '6' }))));
            labelled('Отклонить')!.click();
            await settle();
            expect(api.rejectArtifact).toHaveBeenCalledWith(ids.deckId, ids.sessionId, ids.first, '5', expect.any(String));
            expect(root().textContent).toContain('Вы отклонили правку');
            api.undoRejectArtifact.mockReturnValue(of(parseArtifactSummary(itemArtifact('PROPOSED', { rowVersion: '7' }))));
            labelled('Вернуть правку')!.click();
            await settle();
            expect(api.undoRejectArtifact).toHaveBeenCalled();
            expect(store.artifacts()[0]!.state).toBe('PROPOSED');
        });

        it('offers a retry when the text cannot be loaded, and nothing can be decided before it is', async () => {
            failCurrent = true;
            await openItem();
            expect(root().querySelector('.notice.error')!.textContent).toContain('Не удалось загрузить материал');
            expect(root().querySelector('.result-card')).toBeNull();
            expect(labelled('Оставить')).toBeUndefined();
            failCurrent = false;
            root().querySelector<HTMLButtonElement>('.notice.error button')!.click();
            await settle();
            expect(root().querySelector('.notice.error')).toBeNull();
            expect(labelled('Оставить')!.getAttribute('aria-disabled')).toBeNull();
        });

        it('ends a cancelled session in words and still lets the owner keep the result', async () => {
            await open(itemSession(itemArtifact('PROPOSED'), { state: 'CANCELLED' }), itemDetail(REWRITTEN, NEW_TEXT, [turn()]), { [ORIGINAL]: itemDetail(ORIGINAL, OLD_TEXT, []) });
            expect(root().textContent).toContain('Вы остановили правку');
            expect(labelled('Ещё раз')).toBeUndefined();
        });
    });

    describe('the exercise (REVISE_EXERCISE)', () => {
        it('is the Workshop of a revision of an exercise: the voice chip, the honest note on the Stub, the preview, the actions and the history', async () => {
            await openExercise();
            expect(root().querySelector('h1')!.textContent).toBe('Правка упражнения');
            expect(root().querySelector('.lede')!.textContent).toContain('Голос: мужской');
            expect(root().querySelector('[data-voice-chip]')!.textContent).toBe('Голос: мужской');
            expect(root().querySelector('[data-stub-note]')!.textContent).toBe('Озвучка обновится, когда подключим синтез речи.');
            expect(root().querySelector('app-exercise-preview-host')).not.toBeNull();
            expect([...root().querySelectorAll('.result-actions button')].map(button => button.textContent!.trim())).toEqual(['Оставить', 'Вернуть', 'Ещё раз', 'Отклонить']);
            expect(root().querySelector('.edit-history')!.textContent).toContain('Озвучка заново: мужской голос');
            expect(summary()).toContain('Правка готова');
        });

        it('does not claim the audio is unchanged when the asset really changed (real synthesis, AI-09)', async () => {
            await openExercise(voiceOnly, [voiceTurn()], OTHER_ASSET);
            await settle();
            expect(root().querySelector('[data-voice-chip]')).not.toBeNull();
            expect(root().querySelector('[data-stub-note]')).toBeNull();
        });

        it('shows the changed words of the exercise as a diff, and says so when only the voice changed', async () => {
            await open(exerciseSession(exerciseArtifact('PROPOSED', REWRITTEN), { instruction: 'Сделай вопрос короче' }), exerciseRevision(REWRITTEN, ASSET, 'Когда выберут Seq Scan?', [turn()], null),
                { [ORIGINAL]: exerciseRevision(ORIGINAL, ASSET, 'Когда планировщик выберет Seq Scan?', [], null) });
            await settle();
            const diff = root().querySelector('.revise-diff')!;
            expect(diff.textContent).toContain('Что изменилось в тексте');
            expect(diff.querySelector('del')!.textContent).toContain('планировщик');
            expect(root().querySelector('.lede')!.textContent).toContain('Запрос: «Сделай вопрос короче»');
            expect(root().querySelector('[data-voice-chip]')).toBeNull();
            await openExercise();
            await settle();
            expect(root().querySelector('.revise-diff')!.textContent).toContain('Текст упражнения не изменился');
        });

        it('«Оставить» saves the next revision of the same exercise, with a toast and a link to the exercise editor', async () => {
            await openExercise();
            api.approveArtifact.mockReturnValue(of(parseApprovalAck({ commandId: ids.command, deckId: ids.deckId, deckRevisionId: ids.deckRevision, deckVersion: '9',
                artifacts: [{ artifactId: ids.first, state: 'PUBLISHED', publishedRef: { kind: 'EXERCISE', exerciseId: exerciseIds.exercise,
                    exerciseRevisionId: exerciseIds.exerciseRevision, objectiveId: exerciseIds.objective, objectiveRevisionId: exerciseIds.objectiveRevision } }] }, false)));
            session = exerciseSession(exerciseArtifact('PUBLISHED', MEDIA, { rowVersion: '6', publishedRef: { kind: 'EXERCISE', exerciseId: exerciseIds.exercise,
                exerciseRevisionId: exerciseIds.exerciseRevision, objectiveId: exerciseIds.objective, objectiveRevisionId: exerciseIds.objectiveRevision } }), voiceOnly, { state: 'CLOSED' });
            labelled('Оставить')!.click();
            await settle();
            expect(api.approveArtifact.mock.calls[0]![2]).toEqual({ artifactId: ids.first, expectedArtifactVersion: '5', expectedRevisionId: MEDIA });
            expect(toast.echo).toHaveBeenCalledWith('Новая версия упражнения сохранена');
            expect(root().querySelector('.notice.success a')!.getAttribute('href')).toBe(`/decks/${ids.deckId}/exercises/${exerciseIds.exercise}/edit`);
            expect(root().querySelector('.result-card')).toBeNull();
        });

        it('«Ещё раз» of a voice change redoes the voice (an exercise edit with a voice and no target); of a text change, rewrites it whole', async () => {
            await openExercise();
            const queued = (action: string) => of(parseEditAccepted({ turn: turn({ turnId: VOICE_TURN, status: 'QUEUED', action, resultRevisionId: null, voice: null,
                instruction: action === 'FREE' ? 'Сделай вопрос короче' : null }), artifact: exerciseArtifact('REVISING', MEDIA, { rowVersion: '6' }) }, false));
            api.editArtifact.mockReturnValue(queued('AUDIO_REGENERATE'));
            session = exerciseSession(exerciseArtifact('REVISING', MEDIA, { rowVersion: '6' }), voiceOnly, { state: 'RUNNING' });
            labelled('Ещё раз')!.click();
            await settle();
            await vi.advanceTimersByTimeAsync(ANNOUNCE_GAP_MS);
            fixture.detectChanges();
            expect(api.editArtifact.mock.calls[0]![3]).toMatchObject({ expectedRevisionId: MEDIA, action: 'AUDIO_REGENERATE', nodeIds: [], exercise: true, voice: 'male' });
            expect(summary()).toContain('Мнема правит упражнение');

            await open(exerciseSession(exerciseArtifact(), { instruction: 'Сделай вопрос короче', ...voiceOnly }), exerciseRevision(MEDIA, ASSET, 'Когда выберут Seq Scan?',
                [turn({ instruction: 'Сделай вопрос короче' }), voiceTurn()]), { [ORIGINAL]: exerciseRevision(ORIGINAL, ASSET, 'Когда планировщик выберет Seq Scan?', [], null) });
            api.editArtifact.mockReturnValue(queued('FREE'));
            labelled('Ещё раз')!.click();
            await settle();
            expect(api.editArtifact.mock.calls[0]![3]).toMatchObject({ action: 'FREE', nodeIds: [], exercise: true, instruction: 'Сделай вопрос короче' });
        });

        it('«Вернуть» goes back to the exercise as it was; the chip and the note go with it', async () => {
            await openExercise();
            await settle();
            api.revertArtifact.mockReturnValue(of(parseArtifactSummary(exerciseArtifact('PROPOSED', ORIGINAL, { rowVersion: '6' }))));
            detail = exerciseRevision(ORIGINAL, ASSET, 'Когда планировщик выберет Seq Scan?', [voiceTurn()], null);
            labelled('Вернуть')!.click();
            await settle();
            expect(api.revertArtifact.mock.calls[0]![4]).toBe(ORIGINAL);
            await settle();
            expect(root().querySelector('.result-title')!.textContent).toContain('Сейчас показана прежняя версия');
            expect(root().querySelector('[data-voice-chip]')).toBeNull();
            expect(root().querySelector('[data-stub-note]')).toBeNull();
        });

        it('says a failed voice change changed nothing, and shows the states of a revision that is running, stale, rejected or already saved', async () => {
            await openExercise(voiceOnly, [voiceTurn({ status: 'FAILED', errorCode: 'PROVIDER_UNAVAILABLE', resultRevisionId: null })]);
            expect(root().querySelector('.result-title')!.textContent).toContain('Не удалось сменить голос');
            expect(root().querySelector('[data-voice-chip]')).toBeNull();
            await openExercise(voiceOnly, [voiceTurn()], ASSET, 'STALE');
            expect(root().textContent).toContain('Упражнение изменилось, пока Мнема его правила');
            await openExercise(voiceOnly, [voiceTurn()], ASSET, 'REJECTED');
            expect(labelled('Вернуть правку')).toBeDefined();
            await openExercise(voiceOnly, [voiceTurn()], ASSET, 'REVISING');
            expect(root().textContent).toContain('Мнема правит упражнение');
            expect(root().querySelector('.result-card')).toBeNull();
            await openExercise(voiceOnly, [voiceTurn()], ASSET, 'FAILED');
            expect(root().querySelector('.notice.error')).not.toBeNull();
        });

        it('goes back through the history to any version of the exercise', async () => {
            await openExercise();
            api.revertArtifact.mockReturnValue(of(parseArtifactSummary(exerciseArtifact('PROPOSED', ORIGINAL, { rowVersion: '6' }))));
            root().querySelector<HTMLButtonElement>('.edit-history li:first-child button')!.click();
            await settle();
            expect(api.revertArtifact.mock.calls[0]![4]).toBe(ORIGINAL);
        });
    });

    describe('what the words of an exercise are', () => {
        it('lists the words and the answers of every mechanic, leaving media and ids out', () => {
            const lines = (exercise: any, quotes: Record<string, string> = {}) => exerciseTextLines(exercise, quotes);
            const text = (value: string) => [{ kind: 'TEXT', text: value }];
            const spec = (type: string, content: Record<string, unknown>, answerKey: Record<string, unknown>) => ({ type, schemaVersion: 2, enabled: true,
                subject: { memberKey: materialIds.first, itemRevisionId: materialRevisions.first }, content, answerKey, evaluatorPolicy: { id: 'x', version: '1' } });
            expect(lines(withAudio(ASSET), { '00000000-0000-4000-8000-000000000004': 'Цитата' })).toEqual(['Вопрос: Когда планировщик выберет Seq Scan?', 'Эталон: Цитата']);
            expect(lines(spec('FREE_RESPONSE', { prompt: text('Что такое индекс?'), reference: [], responseInput: 'TEXT' }, { kind: 'TEXT', accepted: ['Структура данных', 'структура'] })))
                .toEqual(['Вопрос: Что такое индекс?', 'Ответ: Структура данных', 'Ответ: структура']);
            expect(lines(spec('CLOZE', { prompt: text('Вставьте'), passage: [{ kind: 'TEXT', text: 'Индекс ускоряет ' }, { kind: 'BLANK', blankId: 'b1' }, { kind: 'TEXT', text: '.' }] },
                { kind: 'CLOZE', blanks: [{ blankId: 'b1', accepted: ['чтение', 'выборку'] }] }))).toEqual(['Задание: Вставьте', 'Текст: Индекс ускоряет ____.', 'Ответ: чтение / выборку']);
            expect(lines(spec('CHOICE', { prompt: text('Выберите'), selectionMode: 'SINGLE', options: [{ optionId: 'o1', blocks: text('Верно') }, { optionId: 'o2', blocks: text('Неверно') }] },
                { kind: 'CHOICE', correctOptionIds: ['o1'] }))).toEqual(['Вопрос: Выберите', 'Верный вариант: Верно', 'Вариант: Неверно']);
            expect(lines(spec('MATCH', { prompt: [], left: [{ itemId: 'l1', blocks: text('Seq') }], right: [{ itemId: 'r1', blocks: text('таблица') }] },
                { kind: 'MATCH', pairs: [{ leftId: 'l1', rightId: 'r1' }] }))).toEqual(['Пара: Seq — таблица']);
            expect(lines(spec('ORDER', { prompt: text('Порядок'), items: [{ itemId: 'a', blocks: text('первый') }, { itemId: 'b', blocks: text('второй') }] },
                { kind: 'ORDER', sequence: ['b', 'a'] }))).toEqual(['Задание: Порядок', 'Шаг 1: второй', 'Шаг 2: первый']);
            expect(lines(spec('CATEGORIZE', { prompt: [], categories: [{ categoryId: 'c', label: 'Группа' }], items: [{ itemId: 'i', blocks: text('Слово') }] },
                { kind: 'CATEGORIZE', assignments: [{ itemId: 'i', categoryId: 'c' }] }))).toEqual(['Группа: Группа', 'Элемент: Слово → Группа']);
        });

        it('lists the assets of the audio blocks in order, which is what the Stub keeps and a real synthesis replaces', () => {
            expect(audioAssetIds(withAudio(ASSET))).toEqual([ASSET]);
            expect(audioAssetIds(selfCheck())).toEqual([]);
        });
    });
});
