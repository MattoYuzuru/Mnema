import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject, Subject, of, throwError } from 'rxjs';

import { MediaPlaybackApi } from '../../content/rendering/media-playback.api';
import { ToastService } from '../../core/notifications/toast.service';
import { PageTransition } from '../../shared/page-transition.service';
import { spyObj, type SpyObj } from '../../../testing/mocks';
import { ExercisePreviewApiService } from '../authoring/exercise-preview-api.service';
import { ItemApiService } from '../authoring/item-api.service';
import { OwnDecksApiService } from '../own-decks/own-decks-api.service';
import {
    ack, artifactIds, choice, createCommand, documentOf, exerciseArtifact, exerciseDetail, exerciseIds, exerciseSession, materialIds, materialRevisions, selfCheck
} from './exercise-test-data';
import { GenerationApiService } from './generation-api.service';
import { groupCards, ReviewCard } from './exercise-batch-review.component';
import { WorkshopPageComponent } from './workshop-page.component';
import { WorkshopSessionStore } from './workshop-session.store';
import { activeStep, deckFixture, eventsEnvelope, ids, problemResponse } from './generation-test-data';
import { parseApprovalAck, parseArtifactDetail, parseArtifactSummary, parseEventsPage, parseSessionDetail } from './generation.models';

const pinsOf = (key: keyof typeof materialIds) => ({ memberKey: materialIds[key], itemRevisionId: materialRevisions[key] });

describe('Exercise batch review (Workshop of an EXERCISES session)', () => {
    let fixture: ComponentFixture<WorkshopPageComponent>;
    let api: SpyObj<GenerationApiService>;
    let decks: SpyObj<OwnDecksApiService>;
    let items: { read: ReturnType<typeof vi.fn> };
    let toast: { echo: ReturnType<typeof vi.fn> };
    let session: Record<string, any>;
    let details: Record<string, Record<string, any>>;
    let store: WorkshopSessionStore;
    let titleOf: (memberKey: string) => string | null = memberKey => memberKey === materialIds.first ? 'Планы запросов' : 'Индексы';
    let failDetail = new Set<string>();
    let holdDetails = false;
    let held: { id: string; subject: Subject<ReturnType<typeof parseArtifactDetail>> }[] = [];

    const root = (): HTMLElement => fixture.nativeElement as HTMLElement;
    const cardOf = (index: number): HTMLElement => root().querySelector<HTMLElement>(`[data-card="${artifactIds[index]}"]`)!;
    const labelled = (scope: ParentNode, label: string): HTMLElement | undefined =>
        [...scope.querySelectorAll<HTMLElement>('button, a')].find(element => element.textContent!.trim() === label);
    const summary = (): string => root().querySelector('.summary')!.textContent!.replace(/\u00a0/g, ' ');

    /** The batch in the middle of its work: two proposals of the first material, one of the second, one writing, one failed. */
    const batch = (): Record<string, any>[] => [exerciseArtifact(artifactIds[0]!, 0, 'PROPOSED'), exerciseArtifact(artifactIds[1]!, 1, 'PROPOSED'),
        exerciseArtifact(artifactIds[2]!, 2, 'PROPOSED'), exerciseArtifact(artifactIds[3]!, 3, 'GENERATING'),
        exerciseArtifact(artifactIds[4]!, 4, 'FAILED', { errorCode: 'INVALID_OUTPUT', currentRevisionId: null })];

    const defaultDetails = (): Record<string, Record<string, any>> => ({
        [artifactIds[0]!]: exerciseDetail(artifactIds[0]!, 0, createCommand(selfCheck(pinsOf('first'))), {}),
        [artifactIds[1]!]: exerciseDetail(artifactIds[1]!, 1, createCommand(choice(pinsOf('first')), 'Что читает Seq Scan'), {}),
        [artifactIds[2]!]: exerciseDetail(artifactIds[2]!, 2, createCommand(choice(pinsOf('second')), 'Другая цель'), {})
    });

    async function settle(): Promise<void> {
        fixture.detectChanges();
        await vi.advanceTimersByTimeAsync(0);
        fixture.detectChanges();
        await vi.advanceTimersByTimeAsync(0);
        fixture.detectChanges();
    }

    async function open(artifacts: Record<string, any>[] = batch(), overrides: Record<string, unknown> = {}, extraDetails: Record<string, Record<string, any>> = {}): Promise<void> {
        TestBed.resetTestingModule();
        vi.useFakeTimers();
        vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
        session = exerciseSession(artifacts, { state: 'RUNNING', ...overrides }, [pinsOf('first'), pinsOf('second')]);
        details = { ...defaultDetails(), ...extraDetails };
        api = spyObj<GenerationApiService>({ getSession: vi.fn(), listEvents: vi.fn(), getArtifact: vi.fn(), approveArtifacts: vi.fn(), rejectArtifact: vi.fn(),
            undoRejectArtifact: vi.fn(), retryArtifact: vi.fn(), cancelSession: vi.fn(), deleteSession: vi.fn() });
        decks = spyObj<OwnDecksApiService>({ detail: vi.fn() });
        items = { read: vi.fn() };
        toast = { echo: vi.fn() };
        api.getSession.mockImplementation(() => of(parseSessionDetail(session)));
        api.listEvents.mockReturnValue(of(parseEventsPage(eventsEnvelope([], '0', { state: 'RUNNING', rowVersion: '12' }, [activeStep]))));
        api.getArtifact.mockImplementation((_deck, _session, artifactId) => {
            if (holdDetails) {
                const subject = new Subject<ReturnType<typeof parseArtifactDetail>>();
                held.push({ id: artifactId, subject });
                return subject;
            }
            return failDetail.has(artifactId) ? throwError(() => new Error('x')) : of(parseArtifactDetail(details[artifactId]!));
        });
        decks.detail.mockReturnValue(of(deckFixture));
        items.read.mockImplementation((_deck: string, memberKey: string) => titleOf(memberKey) === null ? throwError(() => problemResponse(404))
            : of({ memberKey, itemRevisionId: materialRevisions.first, document: documentOf(titleOf(memberKey)!) }));
        const params = new BehaviorSubject(convertToParamMap({ deckId: ids.deckId, sessionId: ids.sessionId }));
        const query = new BehaviorSubject(convertToParamMap({}));
        TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: GenerationApiService, useValue: api },
            { provide: OwnDecksApiService, useValue: decks }, { provide: ItemApiService, useValue: items }, { provide: ToastService, useValue: toast },
            { provide: PageTransition, useValue: { navigate: vi.fn().mockResolvedValue(true) } },
            { provide: ExercisePreviewApiService, useValue: { submit: vi.fn(), checkPair: vi.fn(), hint: vi.fn() } },
            { provide: MediaPlaybackApi, useValue: { read: () => new Promise(() => {}) } },
            { provide: ActivatedRoute, useValue: { paramMap: params, queryParamMap: query, snapshot: { paramMap: params.value, queryParamMap: query.value } } }] });
        fixture = TestBed.createComponent(WorkshopPageComponent);
        store = fixture.debugElement.injector.get(WorkshopSessionStore);
        await settle();
    }

    afterEach(() => {
        vi.useRealTimers();
        titleOf = memberKey => memberKey === materialIds.first ? 'Планы запросов' : 'Индексы';
        failDetail = new Set<string>();
        holdDetails = false;
    });

    describe('layout', () => {
        it('is the exercise Workshop: its own heading, the targets line, the summary, and no material pager', async () => {
            await open();
            expect(root().querySelector('h1')?.textContent).toBe('Мастерская упражнений');
            expect(root().querySelector('.lede')?.textContent).toContain('Для\u00a02\u00a0материалов');
            expect(summary()).toBe('3 готово · 1 пишется · 1 не удался');
            expect(root().querySelector('app-batch-pager')).toBeNull();
            expect(root().querySelector('app-proposal-view')).toBeNull();
            expect(root().querySelector('app-exercise-batch-review')).not.toBeNull();
            expect(root().textContent).not.toContain('Одобрить все готовые');
        });

        it('groups the proposals by material in the order they were requested, named by the material, then what is not ready', async () => {
            await open();
            const groups = [...root().querySelectorAll('.group')];
            expect(groups.map(group => group.querySelector('.group-title')!.textContent)).toEqual(['Планы запросов', 'Индексы', 'Пишутся', 'Не удались']);
            expect(groups[0]!.querySelectorAll('li.card')).toHaveLength(2);
            expect(groups[1]!.querySelectorAll('li.card')).toHaveLength(1);
            expect(groups.map(group => group.getAttribute('aria-labelledby')).every(id => root().querySelector(`#${id}`) !== null)).toBe(true);
        });

        it('names a material «Материал» while its title is unknown, and keeps going when one cannot be read', async () => {
            titleOf = memberKey => memberKey === materialIds.first ? null : 'Индексы';
            await open();
            const titles = [...root().querySelectorAll('.group-title')].map(title => title.textContent);
            expect(titles.slice(0, 2)).toEqual(['Материал', 'Индексы']);
        });

        it('tells an empty batch from a loading one', async () => {
            await open([], { state: 'REVIEW' });
            expect(root().textContent).toContain('В этой мастерской пока нет упражнений');
        });

        it('says what a finished or stopped exercise Workshop means, in words about exercises', async () => {
            await open(batch(), { state: 'CANCELLED' });
            expect(root().textContent).toContain('Готовые упражнения можно сохранить');
            await open([exerciseArtifact(artifactIds[0]!, 0, 'PUBLISHED')], { state: 'CLOSED' });
            expect(root().textContent).toContain('Все упражнения разобраны');
        });

        it('loads the proposals a few at a time, and starts the next one when one arrives', async () => {
            held = [];
            holdDetails = true;
            const six = artifactIds.map((id, index) => exerciseArtifact(id, index, 'PROPOSED'));
            await open(six, { state: 'REVIEW' }, Object.fromEntries(artifactIds.map((id, index) => [id, exerciseDetail(id, index, createCommand(selfCheck(pinsOf('first'))))])));
            expect(held).toHaveLength(4);
            held[0]!.subject.next(parseArtifactDetail(details[held[0]!.id]!));
            held[0]!.subject.complete();
            await settle();
            expect(held).toHaveLength(5);
        });
    });

    describe('a card', () => {
        it('plays a proposal in the compact preview host, with its own ids, and never writes anything', async () => {
            await open();
            const card = cardOf(0);
            expect(card.querySelector('app-exercise-preview-host section')?.getAttribute('data-mode')).toBe('PROPOSAL');
            expect(card.querySelector('h3')?.textContent).toBe('Вспомнить и сверить · Выбор между Seq Scan и Index Scan');
            expect(card.textContent).not.toContain('Seq Scan читает всю таблицу');
            labelled(card, 'Показать ответ')!.click();
            fixture.detectChanges();
            expect(card.textContent).toContain('Seq Scan читает всю таблицу');
            const allIds = [...root().querySelectorAll('[id]')].map(element => element.id);
            expect(new Set(allIds).size).toBe(allIds.length);
            expect(api.approveArtifacts).not.toHaveBeenCalled();
        });

        it('offers «Оставить» (checked), «Изменить» (to the editor with the session and the artifact) and «Отклонить»', async () => {
            await open();
            const card = cardOf(1);
            expect((card.querySelector('input[type=checkbox]') as HTMLInputElement).checked).toBe(true);
            const edit = labelled(card, 'Изменить') as HTMLAnchorElement;
            expect(edit.getAttribute('href')).toBe(`/decks/${ids.deckId}/materials/${materialIds.first}/exercises/new?session=${ids.sessionId}&artifact=${artifactIds[1]}`);
            expect(labelled(card, 'Отклонить')).toBeDefined();
        });

        it('says when a proposal was updated to the new version of its material by the server (AUTO_REPINNED)', async () => {
            await open([exerciseArtifact(artifactIds[0]!, 0, 'PROPOSED', { repinStatus: 'AUTO_REPINNED' }), exerciseArtifact(artifactIds[1]!, 1, 'PROPOSED')], { state: 'REVIEW' });
            expect(cardOf(0).querySelector('[data-repinned]')?.textContent).toContain('обновлено под его новую версию');
            expect(cardOf(1).querySelector('[data-repinned]')).toBeNull();
        });

        it('shows what is being written as a busy paper placeholder, and a failed one with the reason, the limit note and «Повторить»', async () => {
            await open();
            const writing = cardOf(3);
            expect(writing.getAttribute('aria-busy')).toBe('true');
            expect(writing.querySelector('.placeholder')).not.toBeNull();
            expect(writing.textContent).toContain('Мнема пишет упражнение');
            const failed = cardOf(4);
            expect(failed.textContent).toContain('Мнема не смогла собрать корректное упражнение');
            expect(failed.textContent).toContain('За этот материал лимит не списан.');
            expect(labelled(failed, 'Повторить')).toBeDefined();
        });

        it('asks again for a failed exercise and keeps focus on that card, not on the page heading', async () => {
            await open();
            api.retryArtifact.mockReturnValue(of(parseArtifactSummary({ ...exerciseArtifact(artifactIds[4]!, 4, 'QUEUED'), rowVersion: '4' })));
            labelled(cardOf(4), 'Повторить')!.click();
            await settle();
            expect(api.retryArtifact).toHaveBeenCalledWith(ids.deckId, ids.sessionId, artifactIds[4], '3', expect.any(String));
            await settle();
            expect(document.activeElement?.closest('[data-card]')?.getAttribute('data-card')).toBe(artifactIds[4]);
            expect(document.activeElement?.tagName).toBe('H3');
        });

        it('says «Материал изменился» for a stale exercise and offers to recreate or reject it', async () => {
            await open([exerciseArtifact(artifactIds[0]!, 0, 'STALE')]);
            const card = cardOf(0);
            expect(card.textContent).toContain('Материал изменился');
            expect(labelled(card, 'Пересоздать')).toBeDefined();
            expect(labelled(card, 'Отклонить')).toBeDefined();
            expect(card.querySelector('app-exercise-preview-host')).toBeNull();
        });

        it('shows a saved exercise with «Новое» and a link to it, and a rejected one with «Вернуть»', async () => {
            const published = exerciseArtifact(artifactIds[0]!, 0, 'PUBLISHED', { publishedRef: { kind: 'EXERCISE', exerciseId: exerciseIds.exercise,
                exerciseRevisionId: exerciseIds.exerciseRevision, objectiveId: exerciseIds.objective, objectiveRevisionId: exerciseIds.objectiveRevision } });
            await open([published, exerciseArtifact(artifactIds[1]!, 1, 'REJECTED')], { state: 'REVIEW' });
            const saved = cardOf(0);
            expect(saved.textContent).toContain('Сохранено.');
            expect(saved.querySelector('app-new-badge')).not.toBeNull();
            expect(saved.querySelector('a.text-link')?.getAttribute('href')).toBe(`/decks/${ids.deckId}/exercises/${exerciseIds.exercise}/edit`);
            expect(cardOf(1).closest('.group')?.querySelector('.group-title')?.textContent).toBe('Планы запросов');
            expect(labelled(cardOf(1), 'Вернуть')).toBeDefined();
        });

        it('rejects with «Отклонить» and puts focus on «Вернуть» of the same card; «Вернуть» puts it back on «Отклонить»', async () => {
            await open();
            api.rejectArtifact.mockImplementation(() => {
                session = { ...session, artifacts: session['artifacts'].map((artifact: any) => artifact.artifactId === artifactIds[1] ? { ...artifact, state: 'REJECTED', rowVersion: '4' } : artifact) };
                return of(parseArtifactSummary({ ...exerciseArtifact(artifactIds[1]!, 1, 'REJECTED'), rowVersion: '4' }));
            });
            labelled(cardOf(1), 'Отклонить')!.click();
            await settle();
            await settle();
            expect(api.rejectArtifact).toHaveBeenCalledWith(ids.deckId, ids.sessionId, artifactIds[1], '3', expect.any(String));
            expect(cardOf(1).textContent).toContain('Отклонено');
            expect(document.activeElement).toBe(labelled(cardOf(1), 'Вернуть'));
            api.undoRejectArtifact.mockImplementation(() => {
                session = { ...session, artifacts: session['artifacts'].map((artifact: any) => artifact.artifactId === artifactIds[1] ? { ...artifact, state: 'PROPOSED', rowVersion: '5' } : artifact) };
                return of(parseArtifactSummary({ ...exerciseArtifact(artifactIds[1]!, 1, 'PROPOSED'), rowVersion: '5' }));
            });
            labelled(cardOf(1), 'Вернуть')!.click();
            await settle();
            await settle();
            expect(api.undoRejectArtifact).toHaveBeenCalled();
            expect(document.activeElement).toBe(labelled(cardOf(1), 'Отклонить'));
        });

        it('offers to load a proposal again when its detail could not be read', async () => {
            failDetail = new Set([artifactIds[0]!]);
            await open();
            expect(cardOf(0).textContent).toContain('Не удалось загрузить упражнение');
            failDetail = new Set();
            labelled(cardOf(0), 'Повторить загрузку')!.click();
            await settle();
            expect(cardOf(0).querySelector('app-exercise-preview-host')).not.toBeNull();
        });
    });

    describe('saving', () => {
        const saveButton = (): HTMLButtonElement => root().querySelector<HTMLButtonElement>('[data-save]')!;

        it('counts what is kept: unchecking takes a proposal out of «Сохранить выбранные (N)»', async () => {
            await open();
            expect(saveButton().textContent!.trim()).toBe('Сохранить выбранные (3)');
            const box = cardOf(1).querySelector<HTMLInputElement>('input[type=checkbox]')!;
            box.click();
            fixture.detectChanges();
            expect(saveButton().textContent!.trim()).toBe('Сохранить выбранные (2)');
            box.click();
            fixture.detectChanges();
            expect(saveButton().textContent!.trim()).toBe('Сохранить выбранные (3)');
        });

        it('saves the kept ones in one command, says so, and moves focus on to the next control (never the heading)', async () => {
            await open();
            api.approveArtifacts.mockImplementation((_d, _s, targets, _p, commandId) => {
                session = { ...session, artifacts: session['artifacts'].map((artifact: any) => targets.some(target => target.artifactId === artifact.artifactId)
                    ? { ...artifact, state: 'PUBLISHED', rowVersion: '4' } : artifact) };
                return of(parseApprovalAck(ack(commandId, targets.map(target => target.artifactId)), false));
            });
            cardOf(1).querySelector<HTMLInputElement>('input[type=checkbox]')!.click();
            fixture.detectChanges();
            saveButton().click();
            await settle();
            await settle();
            const [, , targets] = api.approveArtifacts.mock.calls[0]!;
            expect(targets.map(target => target.artifactId)).toEqual([artifactIds[0], artifactIds[2]]);
            expect(toast.echo).toHaveBeenCalledWith('Новые упражнения: 2 — уже в колоде');
            expect(root().textContent).toContain('В колоде: 2.');
            const active = document.activeElement as HTMLElement;
            expect(active.hasAttribute('data-after-save')).toBe(true);
            expect(active.tagName).not.toBe('H1');
            expect(active.textContent).toContain('Отклонить остальные (1)');
        });

        it('moves focus on to «К колоде» when everything was saved and nothing is left to decide', async () => {
            await open([exerciseArtifact(artifactIds[0]!, 0, 'PROPOSED'), exerciseArtifact(artifactIds[1]!, 1, 'PROPOSED')], { state: 'REVIEW' });
            api.approveArtifacts.mockImplementation((_d, _s, targets, _p, commandId) => {
                session = { ...session, artifacts: session['artifacts'].map((artifact: any) => ({ ...artifact, state: 'PUBLISHED', rowVersion: '4' })) };
                return of(parseApprovalAck(ack(commandId, targets.map(target => target.artifactId)), false));
            });
            saveButton().click();
            await settle();
            await settle();
            const active = document.activeElement as HTMLElement;
            expect(active.hasAttribute('data-after-save')).toBe(true);
            expect(active.textContent!.trim()).toBe('К колоде');
            expect(active.closest('.review-footer')).toBeNull();
        });

        it('rejects what was left out with «Отклонить остальные (M)» after saving', async () => {
            await open();
            api.approveArtifacts.mockImplementation((_d, _s, targets, _p, commandId) => {
                session = { ...session, artifacts: session['artifacts'].map((artifact: any) => targets.some(target => target.artifactId === artifact.artifactId)
                    ? { ...artifact, state: 'PUBLISHED', rowVersion: '4' } : artifact) };
                return of(parseApprovalAck(ack(commandId, targets.map(target => target.artifactId)), false));
            });
            api.rejectArtifact.mockImplementation((_d, _s, artifactId) => of(parseArtifactSummary({ ...exerciseArtifact(artifactId, 1, 'REJECTED'), rowVersion: '5' })));
            expect(labelled(root(), 'Отклонить остальные (1)')).toBeUndefined();
            cardOf(1).querySelector<HTMLInputElement>('input[type=checkbox]')!.click();
            fixture.detectChanges();
            saveButton().click();
            await settle();
            await settle();
            labelled(root(), 'Отклонить остальные (1)')!.click();
            await settle();
            await settle();
            expect(api.rejectArtifact).toHaveBeenCalledTimes(1);
            expect(api.rejectArtifact.mock.calls[0]![2]).toBe(artifactIds[1]);
        });

        it('does nothing while nothing is kept, and says so', async () => {
            await open([exerciseArtifact(artifactIds[0]!, 0, 'PROPOSED')], { state: 'REVIEW' });
            cardOf(0).querySelector<HTMLInputElement>('input[type=checkbox]')!.click();
            fixture.detectChanges();
            expect(saveButton().getAttribute('aria-disabled')).toBe('true');
            saveButton().click();
            await settle();
            expect(api.approveArtifacts).not.toHaveBeenCalled();
            expect(root().textContent).toContain('Ничего не выбрано для сохранения');
        });

        it('shows a stale-material refusal where the user is looking and keeps the cards', async () => {
            await open();
            api.approveArtifacts.mockReturnValue(throwError(() => problemResponse(409, { code: 'GENERATION_STATE_CONFLICT', reason: 'SOURCE_STALE' })));
            saveButton().click();
            await settle();
            await settle();
            expect(root().querySelector('[role=alert]')?.textContent).toContain('Материал изменился, пока писалось упражнение');
            expect(root().querySelectorAll('li.card').length).toBe(5);
        });
    });

    describe('grouping rules', () => {
        const card = (artifactId: string, state: string, member: string | null): ReviewCard => ({
            artifact: parseArtifactSummary(exerciseArtifact(artifactId, 0, state)), entry: null, presentation: null, mechanic: null,
            status: { shape: 'ready', word: 'готов' },
            proposal: member === null ? null : { artifactId, revisionId: ids.revision, mechanic: 'CHOICE', objective: { operation: 'create', title: 'x' }, objectiveTitle: 'x',
                exercise: choice({ memberKey: member, itemRevisionId: materialRevisions.first }), quotes: {} } });

        it('puts materials in the requested order, then those the request did not list, then the rest by kind', () => {
            const groups = groupCards([card(artifactIds[0]!, 'PROPOSED', materialIds.second), card(artifactIds[1]!, 'PROPOSED', materialIds.third),
                card(artifactIds[2]!, 'PROPOSED', materialIds.first), card(artifactIds[3]!, 'QUEUED', null), card(artifactIds[4]!, 'FAILED', null),
                card(artifactIds[5]!, 'PUBLISHED', null)], [materialIds.first, materialIds.second], { [materialIds.first]: 'Первый' });
            expect(groups.map(group => group.key)).toEqual([materialIds.first, materialIds.second, materialIds.third, 'writing', 'failed', 'saved']);
            expect(groups.map(group => group.title)).toEqual(['Первый', 'Материал', 'Материал', 'Пишутся', 'Не удались', 'Уже в колоде']);
        });
    });
});
