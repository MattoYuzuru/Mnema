import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject, Observable, Subject, of, throwError } from 'rxjs';

import { MediaPlaybackApi } from '../../content/rendering/media-playback.api';
import { CAPABILITIES_UNAVAILABLE, CapabilitiesApiService, LearningCapabilities } from '../authoring/capabilities-api.service';
import { ToastService } from '../../core/notifications/toast.service';
import { HoldToDeleteButtonComponent } from '../../shared/hold-to-delete-button.component';
import { PageTransition } from '../../shared/page-transition.service';
import { spyObj, type SpyObj } from '../../../testing/mocks';
import { OwnDecksApiService } from '../own-decks/own-decks-api.service';
import { GenerationApiService } from './generation-api.service';
import { ANNOUNCE_GAP_MS, WorkshopPageComponent } from './workshop-page.component';
import { WorkshopSessionStore } from './workshop-session.store';
import {
    parseApprovalAck, parseArtifactDetail, parseArtifactSummary, parseEventsPage, parseHandoff, parseNoteArchive, parseSessionDetail
} from './generation.models';
import {
    activeStep, artifactDetailWithNote, artifactWith, clone, deckFixture, eventsEnvelope, examples, httpContract, ids, noteArchiveAnswer, noteIds, problemResponse,
    materialsPlanSession, planApprovedSession, planReadySession, sessionWith, sessionWithNotes, wireBlocks, wireEvent
} from './generation-test-data';

const third = 'a7a70000-0000-4000-8000-000000000003';
const revisionOf = (id: string): string => id === ids.first ? ids.revision : `4e700000-0000-4000-8000-${id.slice(-12)}`;

describe('WorkshopPageComponent', () => {
    let fixture: ComponentFixture<WorkshopPageComponent>;
    let api: SpyObj<GenerationApiService>;
    let decks: SpyObj<OwnDecksApiService>;
    let toast: { echo: ReturnType<typeof vi.fn> };
    let transition: { navigate: ReturnType<typeof vi.fn> };
    let router: Router;
    let params: BehaviorSubject<ReturnType<typeof convertToParamMap>>;
    let query: BehaviorSubject<ReturnType<typeof convertToParamMap>>;
    let store: WorkshopSessionStore;
    let capabilities$: Observable<LearningCapabilities>;

    const root = (): HTMLElement => fixture.nativeElement as HTMLElement;
    const proposed = (id: string, ordinal: number, overrides: Record<string, unknown> = {}) =>
        artifactWith(id, ordinal, 'PROPOSED', { rowVersion: '4', currentRevisionId: revisionOf(id), ...overrides });
    /** A proposal, one being written and one failed: the batch in the middle of its work. */
    const working = (): Record<string, unknown> => sessionWith([proposed(ids.first, 0), artifactWith(ids.second, 1, 'GENERATING', { rowVersion: '2',
        currentRevisionId: null, title: '' }), artifactWith(third, 2, 'FAILED', { rowVersion: '3', currentRevisionId: null, errorCode: 'PROVIDER_UNAVAILABLE' })]);
    const reviewing = (...extra: Record<string, unknown>[]): Record<string, unknown> => sessionWith([proposed(ids.first, 0), proposed(ids.second, 1), ...extra],
        { state: 'REVIEW', approvableCount: 2 });
    const detailOf = (id: string, ordinal: number) => parseArtifactDetail({ ...clone(examples['artifactDetailItem']), artifactId: id, ordinal, title: `Материал ${ordinal + 1}`,
        currentRevisionId: revisionOf(id), revision: { ...clone(examples['artifactDetailItem']).revision, revisionId: revisionOf(id) } });

    async function settle(): Promise<void> {
        fixture.detectChanges();
        await vi.advanceTimersByTimeAsync(0);
        fixture.detectChanges();
    }

    async function open(session: Record<string, unknown> = working(), queryN: string | null = null): Promise<void> {
        TestBed.resetTestingModule();
        vi.useFakeTimers();
        vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
        api = spyObj<GenerationApiService>({ getSession: vi.fn(), listEvents: vi.fn(), getArtifact: vi.fn(), approveArtifact: vi.fn(), approveArtifacts: vi.fn(),
            rejectArtifact: vi.fn(), undoRejectArtifact: vi.fn(), retryArtifact: vi.fn(), handoffArtifact: vi.fn(), cancelSession: vi.fn(), deleteSession: vi.fn(), archiveUsedNotes: vi.fn(), approvePlan: vi.fn() });
        decks = spyObj<OwnDecksApiService>({ detail: vi.fn() });
        capabilities$ = capabilities$ ?? of(CAPABILITIES_UNAVAILABLE);
        toast = { echo: vi.fn() };
        transition = { navigate: vi.fn().mockResolvedValue(true) };
        api.getSession.mockReturnValue(of(parseSessionDetail(session)));
        api.listEvents.mockReturnValue(of(parseEventsPage(eventsEnvelope([], '0', { state: 'RUNNING', rowVersion: '12' }, [activeStep]))));
        api.getArtifact.mockImplementation((_deck, _session, artifactId) => of(detailOf(artifactId, artifactId === ids.first ? 0 : artifactId === ids.second ? 1 : 2)));
        decks.detail.mockReturnValue(of(deckFixture));
        params = new BehaviorSubject(convertToParamMap({ deckId: ids.deckId, sessionId: ids.sessionId }));
        query = new BehaviorSubject(convertToParamMap(queryN === null ? {} : { n: queryN }));
        TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: GenerationApiService, useValue: api },
            { provide: OwnDecksApiService, useValue: decks }, { provide: ToastService, useValue: toast }, { provide: PageTransition, useValue: transition },
            { provide: MediaPlaybackApi, useValue: { read: () => new Promise(() => {}) } },
            { provide: CapabilitiesApiService, useValue: { read: () => capabilities$ } },
            { provide: ActivatedRoute, useValue: { paramMap: params, queryParamMap: query, snapshot: { paramMap: params.value, queryParamMap: query.value } } }] });
        router = TestBed.inject(Router);
        vi.spyOn(router, 'navigate').mockResolvedValue(true);
        fixture = TestBed.createComponent(WorkshopPageComponent);
        store = fixture.debugElement.injector.get(WorkshopSessionStore);
        await settle();
    }

    const dots = (): HTMLButtonElement[] => [...root().querySelectorAll<HTMLButtonElement>('.dot')];
    const shown = (): string => root().querySelector('app-proposal-view h2')?.textContent ?? '';
    const labelled = (label: string): HTMLButtonElement | undefined =>
        [...root().querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent!.trim() === label);
    const summary = (): string => root().querySelector('.summary')!.textContent!.replace(/\u00a0/g, ' ');

    afterEach(() => vi.useRealTimers());

    describe('opening', () => {
        it('shows what it is doing while it loads, then the heading, the pager and the first proposal', async () => {
            await open();
            expect(root().querySelector('h1')?.textContent).toBe('Мастерская');
            expect(root().querySelector('h1')?.getAttribute('tabindex')).toBe('-1');
            expect(root().querySelector('.eyebrow')?.textContent).toBe('ИИ · проверьте факты');
            expect(root().querySelector('.lede')?.textContent).toContain('Запрос: «Объясни разницу между Seq Scan и Index Scan на простом примере»');
            expect(dots()).toHaveLength(3);
            expect(dots()[0]!.getAttribute('aria-current')).toBe('step');
            expect(shown()).toBe('Материал 1 из 3, готов');
            expect(root().querySelector('a.back-link')?.textContent).toContain('«Японский N4»');
        });

        it('starts on the first proposal even when earlier materials are done, and keeps the first of a batch that has none', async () => {
            await open(sessionWith([artifactWith(ids.first, 0, 'PUBLISHED'), proposed(ids.second, 1), proposed(third, 2)], { state: 'REVIEW' }));
            expect(dots()[1]!.getAttribute('aria-current')).toBe('step');
            await open(sessionWith([artifactWith(ids.first, 0, 'GENERATING', { currentRevisionId: null }), artifactWith(ids.second, 1, 'QUEUED', { currentRevisionId: null })]));
            expect(dots()[0]!.getAttribute('aria-current')).toBe('step');
            await open(sessionWith([artifactWith(ids.first, 0, 'REJECTED'), artifactWith(ids.second, 1, 'FAILED', { errorCode: 'INVALID_OUTPUT', currentRevisionId: null })], { state: 'REVIEW' }));
            expect(dots()[1]!.getAttribute('aria-current')).toBe('step');
        });

        it('opens the material named by ?n (1-based), clamps a too large number and ignores one that is not a number', async () => {
            await open(working(), '2');
            expect(dots()[1]!.getAttribute('aria-current')).toBe('step');
            await open(working(), '99');
            expect(dots()[2]!.getAttribute('aria-current')).toBe('step');
            await open(working(), 'x');
            expect(dots()[0]!.getAttribute('aria-current')).toBe('step');
            await open(working(), '0');
            expect(dots()[0]!.getAttribute('aria-current')).toBe('step');
        });

        it('follows the URL when the position changes (Back, a link) and stays when it is removed', async () => {
            await open();
            query.next(convertToParamMap({ n: '3' }));
            await settle();
            expect(dots()[2]!.getAttribute('aria-current')).toBe('step');
            query.next(convertToParamMap({}));
            await settle();
            expect(dots()[2]!.getAttribute('aria-current')).toBe('step');
        });

        it('shows a page for a session that is gone, one that failed to open (with a retry), and an empty one', async () => {
            await open();
            api.getSession.mockReturnValue(throwError(() => problemResponse(404)));
            params.next(convertToParamMap({ deckId: ids.deckId, sessionId: '5e550000-0000-4000-8000-000000000009' }));
            await settle();
            expect(root().querySelector('h1')?.textContent).toBe('Мастерская недоступна');
            expect(root().textContent).toContain('Одобренные материалы остались в колоде');
            api.getSession.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
            params.next(convertToParamMap({ deckId: ids.deckId, sessionId: ids.sessionId }));
            await settle();
            expect(root().querySelector('h1')?.textContent).toBe('Не удалось открыть мастерскую');
            expect(root().querySelector('.notice.error')?.getAttribute('role')).toBe('alert');
            api.getSession.mockReturnValue(of(parseSessionDetail(sessionWith([], { state: 'REVIEW' }))));
            labelled('Повторить')!.click();
            await settle();
            expect(root().textContent).toContain('В этой мастерской пока нет материалов');
            expect(dots()).toHaveLength(0);
        });

        it('says «Открываем мастерскую…» until the first answer arrives', async () => {
            await open();
            api.getSession.mockReturnValue(new Subject());
            params.next(convertToParamMap({ deckId: ids.deckId, sessionId: '5e550000-0000-4000-8000-000000000009' }));
            await settle();
            expect(root().textContent).toContain('Открываем мастерскую…');
            expect(root().querySelector('h1')?.textContent).toBe('Мастерская');
            expect(root().querySelector('.pager-row')).toBeNull();
        });
    });

    describe('the pager', () => {
        it('shows the chosen material and puts the position in the URL without adding a history entry', async () => {
            await open();
            dots()[2]!.click();
            await settle();
            expect(dots()[2]!.getAttribute('aria-current')).toBe('step');
            expect(shown()).toBe('Материал 3 из 3, не удался');
            const [commands, extras] = vi.mocked(router.navigate).mock.calls.at(-1)!;
            expect(commands).toEqual([]);
            expect(extras).toMatchObject({ queryParams: { n: 3 }, queryParamsHandling: 'merge', replaceUrl: true });
            dots()[2]!.click();
            expect(vi.mocked(router.navigate)).toHaveBeenCalledTimes(1);
        });

        it('moves with the arrow keys and takes no focus away from where it is, apart from the dot itself', async () => {
            await open();
            document.body.append(root());
            dots()[0]!.focus();
            dots()[0]!.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true, cancelable: true }));
            await settle();
            expect(document.activeElement).toBe(dots()[1]);
            expect(dots()[1]!.getAttribute('aria-current')).toBe('step');
            root().remove();
        });

        it('loads the stored revision of the material on show, once', async () => {
            await open();
            expect(api.getArtifact).toHaveBeenCalledTimes(1);
            expect(api.getArtifact.mock.calls[0]![2]).toBe(ids.first);
            dots()[2]!.click();
            await settle();
            await settle();
            expect(api.getArtifact).toHaveBeenCalledTimes(1);
        });

        it('shows the draft of the material being written, with the blocks that have arrived, busy but not live', async () => {
            await open(working(), '2');
            api.listEvents.mockReturnValueOnce(of(parseEventsPage(eventsEnvelope([wireBlocks(1, 0, 1, 'Раз', 'Два')], '1', { state: 'RUNNING', rowVersion: '12' }, [activeStep]))));
            await vi.advanceTimersByTimeAsync(1000);
            fixture.detectChanges();
            expect(root().querySelectorAll('.draft .native-document p')).toHaveLength(2);
            expect(root().querySelector('article')?.getAttribute('aria-busy')).toBe('true');
            const live = [...root().querySelectorAll('[aria-live], [role=status], [role=alert]')].filter(element => !element.closest('app-native-media-surface') && !element.classList.contains('document-announcement'));
            expect(live.map(element => element.className)).toEqual(['summary']);
        });
    });

    describe('what the server can do', () => {
        it('hands the capabilities to the material, and treats an unreadable answer as nothing available', async () => {
            capabilities$ = of(CAPABILITIES_UNAVAILABLE);
            await open(reviewing());
            expect(root().querySelector('app-proposal-view')).not.toBeNull();
            expect(fixture.componentInstance.capabilities()).toEqual(CAPABILITIES_UNAVAILABLE);
            const available = { ...CAPABILITIES_UNAVAILABLE, textToSpeech: { available: true, reason: null } } as LearningCapabilities;
            capabilities$ = of(available);
            await open(reviewing());
            expect(fixture.componentInstance.capabilities()).toEqual(available);
            capabilities$ = throwError(() => new HttpErrorResponse({ status: 500 }));
            await open(reviewing());
            expect(fixture.componentInstance.capabilities()).toEqual(CAPABILITIES_UNAVAILABLE);
            capabilities$ = of(CAPABILITIES_UNAVAILABLE);
        });
    });

    describe('the end of an edit in the summary', () => {
        it('adds one sentence about it to the summary line, the one live region of the page, and takes it away without saying the line again', async () => {
            await open(reviewing());
            const before = summary();
            store.editNote.set('Мнема переписала фрагмент.');
            await settle();
            expect(summary()).toBe(`${before} · Мнема переписала фрагмент.`);
            const line = root().querySelector('.summary')!;
            expect(line.getAttribute('aria-atomic')).toBe('false');
            // The note is its own node: removing it leaves the text node of the line alone, so a reader is not told the whole line again.
            const text = line.firstChild;
            store.editNote.set(null);
            await settle();
            expect(summary()).toBe(before);
            expect(line.firstChild).toBe(text);
            const own = [...root().querySelectorAll('[role=status]')].filter(element => !element.closest('app-native-media-surface') && !element.classList.contains('document-announcement'));
            expect(own).toHaveLength(1);
        });
    });

    describe('the summary', () => {
        it('reads «N готово · M пишутся · K не удался» in the one live region', async () => {
            await open();
            expect(summary()).toBe('1 готово · 1 пишется · 1 не удался');
            expect(root().querySelector('.summary')?.getAttribute('role')).toBe('status');
            // The page itself has one live region; the status lines of the media players inside a document are theirs.
            const own = [...root().querySelectorAll('[role=status]')].filter(element => !element.closest('app-native-media-surface') && !element.classList.contains('document-announcement'));
            expect(own).toHaveLength(1);
        });

        it('changes at most once per two seconds: quick changes are merged into the last one', async () => {
            await open();
            const set = (state: string): void => store.session.set({ ...store.session()!, artifacts: store.session()!.artifacts.map(artifact =>
                artifact.artifactId === ids.second ? { ...artifact, state: state as never } : artifact) });
            set('PROPOSED');
            fixture.detectChanges();
            expect(summary()).toBe('1 готово · 1 пишется · 1 не удался');
            await vi.advanceTimersByTimeAsync(ANNOUNCE_GAP_MS - 100);
            set('REJECTED');
            fixture.detectChanges();
            expect(summary()).toBe('1 готово · 1 пишется · 1 не удался');
            await vi.advanceTimersByTimeAsync(100);
            fixture.detectChanges();
            expect(summary()).toBe('1 готово · 1 не удался · 1 отклонено');
            expect(ANNOUNCE_GAP_MS).toBe(2000);
        });

        it('says it at once when a long pause has passed', async () => {
            await open();
            await vi.advanceTimersByTimeAsync(5000);
            store.session.set({ ...store.session()!, artifacts: store.session()!.artifacts.map(artifact => ({ ...artifact, state: 'PUBLISHED' as const })) });
            fixture.detectChanges();
            expect(summary()).toBe('3 в колоде');
        });
    });

    describe('stopping and deleting', () => {
        it('offers «Стоп» while the session is working, and it stops the session', async () => {
            await open();
            const stop = labelled('Стоп')!;
            expect(stop.getAttribute('aria-label')).toBe('Стоп: остановить генерацию');
            api.cancelSession.mockReturnValue(of(parseSessionDetail(examples['sessionDetailCancelled'])));
            stop.click();
            await settle();
            expect(api.cancelSession).toHaveBeenCalledTimes(1);
            expect(labelled('Стоп')).toBeUndefined();
            expect(root().textContent).toContain('Вы остановили мастерскую');
        });

        it('has no «Стоп» when nothing is being written, and has it for a review session that is rewriting a material', async () => {
            await open(reviewing());
            expect(labelled('Стоп')).toBeUndefined();
            await open(sessionWith([proposed(ids.first, 0), artifactWith(ids.second, 1, 'REVISING')], { state: 'REVIEW' }));
            expect(labelled('Стоп')).toBeDefined();
        });

        it('has no «Стоп» in the header while a plan is made or waits: the plan has its own «Отменить» (#295)', async () => {
            await open(planReadySession(session => { session.state = 'PLANNING'; session.plan = null; }));
            expect(labelled('Стоп')).toBeUndefined();
            expect(labelled('Отменить')).toBeDefined();
            await open(planReadySession());
            expect(labelled('Стоп')).toBeUndefined();
            expect(labelled('Отменить')).toBeDefined();
        });

        it('deletes the whole Workshop only after the hold, then goes back to the deck with a short echo', async () => {
            await open();
            const hold = fixture.debugElement.query(By.directive(HoldToDeleteButtonComponent));
            expect(hold.componentInstance.label()).toBe('Удалить мастерскую');
            expect(hold.componentInstance.consequence()).toContain('Одобренные останутся в колоде');
            expect(api.deleteSession).not.toHaveBeenCalled();
            api.deleteSession.mockReturnValue(of(undefined));
            hold.componentInstance.confirmed.emit();
            await settle();
            expect(api.deleteSession).toHaveBeenCalledWith(ids.deckId, ids.sessionId);
            expect(toast.echo).toHaveBeenCalledWith('Мастерская удалена');
            expect(transition.navigate).toHaveBeenCalledWith(['/decks', ids.deckId]);
        });

        it('stays where it is and says why when the deletion fails', async () => {
            await open();
            api.deleteSession.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
            fixture.debugElement.query(By.directive(HoldToDeleteButtonComponent)).componentInstance.confirmed.emit();
            await settle();
            expect(transition.navigate).not.toHaveBeenCalled();
            expect(root().querySelector('.notice.error')?.getAttribute('role')).toBe('alert');
        });
    });

    describe('deciding about the materials', () => {
        const ackFor = (...artifactIds: string[]) => parseApprovalAck({ commandId: ids.command, deckId: ids.deckId, deckRevisionId: '33333333-3333-4333-8333-333333333333',
            deckVersion: '9', artifacts: artifactIds.map(artifactId => ({ artifactId, state: 'PUBLISHED', publishedRef: { kind: 'ITEM',
                memberKey: '44444444-4444-4444-8444-444444444444', itemRevisionId: '55555555-5555-4555-8555-555555555555', ordinal: 4 } })) }, false);

        it('approves the material on show and moves on to the next one that still waits, with focus on its title', async () => {
            await open(reviewing(artifactWith(third, 2, 'PUBLISHED')), '1');
            document.body.append(root());
            api.approveArtifact.mockReturnValue(of(ackFor(ids.first)));
            api.getSession.mockReturnValue(of(parseSessionDetail(sessionWith([artifactWith(ids.first, 0, 'PUBLISHED', { rowVersion: '5' }), proposed(ids.second, 1), artifactWith(third, 2, 'PUBLISHED')], { state: 'REVIEW' }))));
            labelled('Одобрить и далее →')!.click();
            await settle();
            await settle();
            expect(api.approveArtifact).toHaveBeenCalledWith(ids.deckId, ids.sessionId, { artifactId: ids.first, expectedArtifactVersion: '4', expectedRevisionId: ids.revision },
                { rowVersion: '8', revisionId: ids.deckRevision }, expect.any(String));
            expect(dots()[1]!.getAttribute('aria-current')).toBe('step');
            expect(toast.echo).toHaveBeenCalledWith('Материал одобрен');
            expect(document.activeElement).toBe(root().querySelector('app-proposal-view h2'));
            root().remove();
        });

        it('stays on the last material when nothing else waits', async () => {
            await open(sessionWith([proposed(ids.first, 0)], { state: 'REVIEW', approvableCount: 1 }));
            api.approveArtifact.mockReturnValue(of(ackFor(ids.first)));
            api.getSession.mockReturnValue(of(parseSessionDetail(sessionWith([artifactWith(ids.first, 0, 'PUBLISHED', { rowVersion: '5' })], { state: 'CLOSED' }))));
            labelled('Одобрить и далее →')!.click();
            await settle();
            await settle();
            expect(dots()[0]!.getAttribute('aria-current')).toBe('step');
            expect(root().textContent).toContain('Материал одобрен и добавлен в колоду');
            expect(root().textContent).toContain('Все материалы разобраны');
        });

        it('shows why an approval failed and keeps the material', async () => {
            await open(reviewing());
            api.approveArtifact.mockReturnValue(throwError(() => problemResponse(409, { code: 'GENERATION_STATE_CONFLICT', reason: 'MEDIA_NOT_READY' })));
            labelled('Одобрить и далее →')!.click();
            await settle();
            expect(root().querySelector('.notice.error')?.textContent).toContain('Медиа ещё не готовы');
            expect(dots()[0]!.getAttribute('aria-current')).toBe('step');
        });

        it('rejects with one press, keeps the material on screen and offers «Вернуть», which brings it back', async () => {
            await open(reviewing());
            const body = httpContract['endpoints'].find((e: any) => e.operationId === 'rejectArtifact').success.body;
            api.rejectArtifact.mockReturnValue(of(parseArtifactSummary(body)));
            labelled('Отклонить')!.click();
            await settle();
            expect(api.rejectArtifact).toHaveBeenCalledWith(ids.deckId, ids.sessionId, ids.first, '4', expect.any(String));
            expect(root().textContent).toContain('Вы отклонили этот материал');
            api.undoRejectArtifact.mockReturnValue(of(parseArtifactSummary({ ...clone(body), state: 'PROPOSED', rowVersion: '6' })));
            labelled('Вернуть')!.click();
            await settle();
            expect(api.undoRejectArtifact).toHaveBeenCalledWith(ids.deckId, ids.sessionId, ids.first, '5');
            expect(labelled('Одобрить и далее →')).toBeDefined();
        });

        it('hands the material to the editor and opens it on the draft that was made', async () => {
            await open(reviewing());
            const body = httpContract['endpoints'].find((e: any) => e.operationId === 'handoffArtifact').success.body;
            api.handoffArtifact.mockReturnValue(of(parseHandoff(body)));
            labelled('Править самому')!.click();
            await settle();
            expect(transition.navigate).toHaveBeenCalledWith(['/decks', ids.deckId, 'materials', 'new'],
                { queryParams: { write: 1, draft: 'd4af7000-0000-4000-8000-000000000001' } });
        });

        it('does not leave for the editor when the hand-off fails', async () => {
            await open(reviewing());
            api.handoffArtifact.mockReturnValue(throwError(() => problemResponse(422, { code: 'RESOURCE_LIMIT_EXCEEDED', limit: 'EDITING_DRAFTS' })));
            labelled('Править самому')!.click();
            await settle();
            expect(transition.navigate).not.toHaveBeenCalled();
            expect(root().querySelector('.notice.error')?.textContent).toContain('черновиков');
        });

        it('shows a failed material with the reason and retries it without losing the others', async () => {
            await open(working(), '3');
            expect(root().querySelector('.notice.error')?.textContent).toContain('Сервис ИИ временно недоступен.');
            expect(root().querySelector('.notice.error')?.textContent).toContain('За этот материал лимит не списан.');
            const body = httpContract['endpoints'].find((e: any) => e.operationId === 'retryArtifact').success.body;
            api.retryArtifact.mockReturnValue(of(parseArtifactSummary({ ...clone(body), artifactId: third, ordinal: 2, rowVersion: '4' })));
            labelled('Попробовать снова')!.click();
            await settle();
            expect(api.retryArtifact).toHaveBeenCalledWith(ids.deckId, ids.sessionId, third, '3', expect.any(String));
            expect(dots().map(dot => dot.dataset['status'])).toEqual(['ready', 'writing', 'queued']);
            expect(summary()).toContain('1 готово');
        });

        it('approves all ready materials only after an in-page confirmation, with focus on the confirming button and Esc to take it back', async () => {
            await open(reviewing());
            document.body.append(root());
            const trigger = labelled('Одобрить все готовые (2)')!;
            expect(root().querySelector('.confirm')).toBeNull();
            trigger.click();
            await settle();
            await settle();
            const group = root().querySelector('.confirm')!;
            expect(group.getAttribute('role')).toBe('group');
            expect(group.textContent).toContain('Одобрить материалов: 2?');
            expect(document.activeElement).toBe(labelled('Да, одобрить'));
            expect(api.approveArtifacts).not.toHaveBeenCalled();
            group.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
            await settle();
            expect(root().querySelector('.confirm')).toBeNull();
            expect(api.approveArtifacts).not.toHaveBeenCalled();
            labelled('Одобрить все готовые (2)')!.click();
            await settle();
            api.approveArtifacts.mockReturnValue(of(ackFor(ids.first, ids.second)));
            api.getSession.mockReturnValue(of(parseSessionDetail(sessionWith([artifactWith(ids.first, 0, 'PUBLISHED', { rowVersion: '5' }), artifactWith(ids.second, 1, 'PUBLISHED', { rowVersion: '5' })], { state: 'CLOSED' }))));
            labelled('Да, одобрить')!.click();
            await settle();
            await settle();
            expect(api.approveArtifacts).toHaveBeenCalledTimes(1);
            expect(api.approveArtifacts.mock.calls[0]![2]).toHaveLength(2);
            expect(toast.echo).toHaveBeenCalledWith('Одобрено материалов: 2');
            expect(root().querySelector('.confirm')).toBeNull();
            expect(labelled('Одобрить все готовые (0)')).toBeUndefined();
            root().remove();
        });

        it('returns focus to the «Одобрить все» button when the confirmation is taken back', async () => {
            await open(reviewing());
            document.body.append(root());
            labelled('Одобрить все готовые (2)')!.click();
            await settle();
            labelled('Отмена')!.click();
            await settle();
            await settle();
            expect(document.activeElement).toBe(labelled('Одобрить все готовые (2)'));
            root().remove();
        });

        it('can cancel the confirmation with its button, and offers nothing to approve when no material is ready', async () => {
            await open(reviewing());
            labelled('Одобрить все готовые (2)')!.click();
            await settle();
            labelled('Отмена')!.click();
            await settle();
            expect(root().querySelector('.confirm')).toBeNull();
            await open(sessionWith([artifactWith(ids.first, 0, 'GENERATING', { currentRevisionId: null })]));
            expect([...root().querySelectorAll('button')].some(button => button.textContent!.includes('Одобрить все готовые'))).toBe(false);
        });

        it('links to the deck from every state', async () => {
            await open();
            expect(root().querySelector<HTMLAnchorElement>('.footer-row a')?.getAttribute('href')).toBe(`/decks/${ids.deckId}`);
        });
    });

    describe('a plan-first Workshop (AI-14, #295)', () => {
        const status = (): string => root().querySelector('.exercise-summary')!.textContent!.replace(/\u00a0/g, ' ');

        it('shows the wait while the plan is made: no pager, no review, the plan\'s own status and cancel, and one live region', async () => {
            await open(planReadySession(session => { session.state = 'PLANNING'; session.plan = null; session.rowVersion = '1'; }));
            expect(root().querySelector('app-workshop-plan h2')?.textContent).toBe('Мнема составляет план…');
            expect(status()).toBe('Мнема составляет план…');
            expect(root().querySelector('app-batch-pager, app-exercise-batch-review, app-proposal-view')).toBeNull();
            expect(root().querySelectorAll('section.workshop [role=status]')).toHaveLength(1);
            expect(root().querySelector('.lede')?.textContent?.replace(/\u00a0/g, ' ')).toBe('Для 2 материалов: сначала план, потом упражнения.');
        });

        it('shows the plan when it is ready, says so once in the live region, and the review does not open before the launch', async () => {
            await open(planReadySession());
            expect(root().querySelector('app-workshop-plan h2')?.textContent).toBe('План упражнений');
            expect(status()).toBe('План готов: проверьте его и запустите');
            expect(root().querySelector('app-exercise-batch-review')).toBeNull();
            expect(root().querySelectorAll('app-workshop-plan ol.rows > li')).toHaveLength(2);
            expect(root().querySelector('.footer-row .text-link')?.textContent).toBe('Выйти в колоду');
        });

        it('opens the review of exactly the planned artifacts once the plan is launched, and the plan is gone', async () => {
            await open(planReadySession());
            api.approvePlan.mockReturnValue(of({ session: parseSessionDetail(planApprovedSession()), replayed: false }));
            labelled('Запустить по плану')!.click();
            await settle();
            expect(root().querySelector('app-workshop-plan')).toBeNull();
            expect(root().querySelector('app-exercise-batch-review')).not.toBeNull();
            await vi.advanceTimersByTimeAsync(ANNOUNCE_GAP_MS);
            fixture.detectChanges();
            expect(status()).toContain('6 пишутся');
            expect(root().querySelector('.lede')?.textContent?.replace(/\u00a0/g, ' ')).toBe('Для 2 материалов: проверьте упражнения и оставьте нужные.');
        });

        it('puts focus on the title of the Workshop when the plan leaves the page, launched or cancelled', async () => {
            await open(planReadySession());
            document.body.appendChild(root());
            api.approvePlan.mockReturnValue(of({ session: parseSessionDetail(planApprovedSession()), replayed: false }));
            labelled('Запустить по плану')!.click();
            await settle();
            await settle();
            expect(document.activeElement).toBe(root().querySelector('#workshop-title'));
            root().remove();
            await open(planReadySession());
            document.body.appendChild(root());
            api.cancelSession.mockReturnValue(of(parseSessionDetail(planReadySession(session => { session.state = 'CANCELLED'; session.rowVersion = '4'; session.endReason = 'USER_CANCELLED'; }))));
            labelled('Отменить')!.click();
            await settle();
            await settle();
            expect(document.activeElement).toBe(root().querySelector('#workshop-title'));
            root().remove();
        });

        it('shows only what happened when the plan ended before its launch: no review, no empty list, the reason in the note and the live region (#295)', async () => {
            await open(planReadySession(session => { session.state = 'CANCELLED'; session.endReason = 'PLAN_FAILED'; session.plan = null; session.rowVersion = '2'; }));
            expect(root().querySelector('app-exercise-batch-review, app-workshop-plan')).toBeNull();
            expect(status()).toBe('Не удалось составить план');
            expect(root().querySelector('.lede')?.textContent?.replace(/\u00a0/g, ' ')).toBe('Для 2 материалов: упражнения не создавались.');
            expect(root().querySelector('.page > .notice')?.textContent).toContain('Мнеме не удалось составить план');
            expect(root().querySelector('.footer-row .text-link')?.textContent).toBe('Выйти в колоду');
            await open(planReadySession(session => { session.state = 'CANCELLED'; session.endReason = 'USER_CANCELLED'; session.plan = null; session.rowVersion = '2'; }));
            expect(root().querySelector('.page > .notice')?.textContent).toContain('Вы остановили составление плана: ничего не создано, лимит не списан.');
            expect(status()).toBe('План отменён');
            await open(planReadySession(session => { session.state = 'EXPIRED'; session.rowVersion = '2'; }));
            expect(status()).toBe('Срок мастерской вышел');
            await open(materialsPlanSession(session => { session.state = 'CANCELLED'; session.endReason = 'PLAN_FAILED'; session.plan = null; }));
            expect(root().querySelector('app-proposal-view, app-batch-pager')).toBeNull();
            expect(root().querySelector('.page > .notice')?.textContent).toContain('Мнеме не удалось составить план');
        });

        it('says the same sentence once: a poll that changes nothing does not start the pause before the next sentence', async () => {
            await open(planReadySession());
            expect(status()).toBe('План готов: проверьте его и запустите');
            await vi.advanceTimersByTimeAsync(ANNOUNCE_GAP_MS * 3);
            api.approvePlan.mockReturnValue(of({ session: parseSessionDetail(planApprovedSession()), replayed: false }));
            labelled('Запустить по плану')!.click();
            await settle();
            expect(status()).toContain('6 пишутся');
        });

        it('shows the plan of materials with the prompt the owner wrote, and no review of exercises', async () => {
            await open(materialsPlanSession());
            expect(root().querySelector('app-workshop-plan h2')?.textContent).toBe('План материалов');
            expect(root().querySelector('.lede')?.textContent).toBe('Запрос: «Объясни планировщик»');
            expect(root().querySelector('app-proposal-view')).toBeNull();
        });

        it('fetches the plan when the poll learns that the plan is ready, and the failed plan ends the session with its reason', async () => {
            await open(planReadySession(session => { session.state = 'PLANNING'; session.plan = null; session.rowVersion = '1'; }));
            api.getSession.mockClear();
            api.getSession.mockReturnValue(of(parseSessionDetail(planReadySession())));
            api.listEvents.mockReturnValue(of(parseEventsPage(eventsEnvelope([wireEvent(1, 'SESSION_STATE', { state: 'PLAN_READY', rowVersion: '3',
                artifactCounts: clone(examples['sessionDetail']).artifactCounts }, null)], '1', { state: 'PLAN_READY', rowVersion: '3' }))));
            await vi.advanceTimersByTimeAsync(5_000);
            fixture.detectChanges();
            expect(api.getSession).toHaveBeenCalled();
            expect(root().querySelector('app-workshop-plan h2')?.textContent).toBe('План упражнений');
        });
    });

    describe('the state of the session and of the connection', () => {
        const note = (): string => [...root().querySelectorAll('.page > .notice')].map(notice => notice.textContent!.trim()).join('|');

        it('says in words that a session is over, expired or planning', async () => {
            await open(sessionWith([artifactWith(ids.first, 0, 'PUBLISHED')], { state: 'CLOSED' }));
            expect(note()).toContain('Все материалы разобраны.');
            await open(sessionWith([proposed(ids.first, 0)], { state: 'EXPIRED' }));
            expect(note()).toContain('Срок мастерской вышел');
            await open(sessionWith([], { state: 'CANCELLED', endReason: 'PLAN_FAILED' }));
            expect(note()).toContain('Мнеме не удалось составить план. Ничего не создано, лимит не списан.');
            await open(planReadySession(session => { session.state = 'CANCELLED'; session.endReason = 'USER_CANCELLED'; }));
            expect(note()).toContain('Вы отменили план: ничего не создано. Стоимость плана уже списана, остальной лимит вернулся.');
        });

        it('says when the network is gone and when it is back, and keeps what is already shown readable', async () => {
            await open();
            store.connection.set('offline');
            fixture.detectChanges();
            expect(note()).toContain('Нет сети: продолжим, когда она появится. Готовое можно читать.');
            expect(dots()).toHaveLength(3);
            store.connection.set('degraded');
            fixture.detectChanges();
            expect(note()).toContain('Не удалось обновить данные. Пробуем снова.');
            store.connection.set('recovered');
            fixture.detectChanges();
            expect(note()).toContain('Связь восстановлена.');
            store.connection.set('online');
            fixture.detectChanges();
            expect(note()).not.toContain('Связь');
        });

        it('says when the daily limit holds the work back and until when', async () => {
            await open();
            store.usage.set({ reservedCredits: 1, spentCredits: 1, balanceRemainingCredits: 1, deferredUntil: '2026-10-04T12:00:00Z' });
            fixture.detectChanges();
            expect(note()).toContain('Дневной лимит ИИ исчерпан. Мнема продолжит 4 октября');
        });

        it('shows a notice of a command in the right role: an error is an alert, a plain remark has no live role (the summary is the only status)', async () => {
            await open(reviewing());
            store.notice.set({ tone: 'info', text: 'Материал обновился.' });
            fixture.detectChanges();
            expect(root().querySelector('.notice')?.getAttribute('role')).toBeNull();
            store.notice.set({ tone: 'error', text: 'Не получилось.' });
            fixture.detectChanges();
            expect(root().querySelector('.notice')?.getAttribute('role')).toBe('alert');
        });

        it('closes its loop when the page goes away', async () => {
            await open();
            fixture.destroy();
            const calls = api.listEvents.mock.calls.length;
            await vi.advanceTimersByTimeAsync(30_000);
            expect(api.listEvents).toHaveBeenCalledTimes(calls);
            expect(wireEvent).toBeDefined();
        });
    });

    describe('notes of the batch (#290)', () => {
        const done = (archivable: number): Record<string, unknown> => sessionWithNotes({ used: 3, archivable },
            [artifactWith(ids.first, 0, 'PUBLISHED', { rowVersion: '5' }), proposed(ids.second, 1)], { state: 'REVIEW', approvableCount: 1 });
        const archiveButton = (): HTMLButtonElement | undefined => [...root().querySelectorAll<HTMLButtonElement>('.note-archive button')][0];

        it('offers «Архивировать использованные заметки (k)» as a secondary action only while some note can be archived', async () => {
            await open(done(2));
            const button = archiveButton()!;
            expect(button.textContent!.trim()).toBe('Архивировать использованные заметки (2)');
            expect(button.classList.contains('primary')).toBe(false);
            expect(button.getAttribute('aria-describedby')).toBe('note-archive-hint');
            expect(root().querySelector('#note-archive-hint')?.textContent).toContain('Изменённые заметки останутся');
            expect(root().querySelector('.footer-row')?.contains(button)).toBe(true);
            await open(done(0));
            expect(archiveButton()).toBeUndefined();
            await open(sessionWith([proposed(ids.first, 0)], { state: 'REVIEW', approvableCount: 1 }));
            expect(archiveButton()).toBeUndefined();
        });

        it('archives, shows what was archived and what was skipped and why, and the button is gone once nothing is left', async () => {
            await open(done(2));
            api.archiveUsedNotes.mockReturnValue(of(parseNoteArchive(noteArchiveAnswer([noteIds.first], [{ noteId: noteIds.second, reason: 'CHANGED' }]), false)));
            api.getSession.mockReturnValue(of(parseSessionDetail(done(0))));
            archiveButton()!.click();
            await settle();
            expect(api.archiveUsedNotes).toHaveBeenCalledTimes(1);
            expect(api.archiveUsedNotes.mock.calls[0]!.slice(0, 2)).toEqual([ids.deckId, ids.sessionId]);
            expect(root().querySelector('.note-archive-result')?.textContent).toContain('Архивировано: 1, пропущено: 1 — заметка изменилась');
            expect(toast.echo).toHaveBeenCalledWith('Архивировано: 1, пропущено: 1 — заметка изменилась');
            expect(archiveButton()).toBeUndefined();
        });

        it('moves focus to the result when the button goes away', async () => {
            await open(done(2));
            document.body.append(root());
            api.archiveUsedNotes.mockReturnValue(of(parseNoteArchive(noteArchiveAnswer([noteIds.first]), false)));
            api.getSession.mockReturnValue(of(parseSessionDetail(done(0))));
            archiveButton()!.focus();
            archiveButton()!.click();
            await settle();
            await vi.advanceTimersByTimeAsync(0);
            const result = root().querySelector<HTMLElement>('.note-archive-result')!;
            expect(result.getAttribute('tabindex')).toBe('-1');
            expect(document.activeElement).toBe(result);
            root().remove();
        });

        it('is idempotent in the interface: a press while archiving does nothing, and an unknown outcome is retried with the same command', async () => {
            await open(done(2));
            const pending = new Subject<ReturnType<typeof parseNoteArchive>>();
            api.archiveUsedNotes.mockReturnValueOnce(pending);
            archiveButton()!.click();
            fixture.detectChanges();
            expect(archiveButton()!.getAttribute('aria-disabled')).toBe('true');
            archiveButton()!.click();
            expect(api.archiveUsedNotes).toHaveBeenCalledTimes(1);
            pending.error(new HttpErrorResponse({ status: 0 }));
            await settle();
            expect(archiveButton()).toBeDefined();
            expect(root().querySelector('.notice.error')).not.toBeNull();
            api.archiveUsedNotes.mockReturnValueOnce(of(parseNoteArchive(noteArchiveAnswer([noteIds.first]), true)));
            archiveButton()!.click();
            await settle();
            expect(api.archiveUsedNotes.mock.calls[1]![2]).toBe(api.archiveUsedNotes.mock.calls[0]![2]);
            expect(root().querySelector('.note-archive-result')?.textContent).toContain('Архивировано: 1');
        });

        it('marks a proposal whose note changed, near its heading and outside any live region', async () => {
            await open(reviewing());
            api.getArtifact.mockImplementation((_deck, _session, artifactId) => of(parseArtifactDetail({ ...artifactDetailWithNote('CHANGED'), artifactId,
                ordinal: artifactId === ids.first ? 0 : 1, currentRevisionId: revisionOf(artifactId),
                revision: { ...clone(examples['artifactDetailItem']).revision, revisionId: revisionOf(artifactId) } })));
            store.loadDetail(ids.first);
            await settle();
            const tag = root().querySelector('app-proposal-view .note-changed-tag')!;
            expect(tag.textContent).toBe('заметка изменилась');
            expect(root().querySelector('app-proposal-view header')?.contains(tag)).toBe(true);
            expect(tag.closest('[role=status], [role=alert], [aria-live]')).toBeNull();
        });
    });
});
