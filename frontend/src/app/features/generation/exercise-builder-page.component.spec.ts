import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';

import { ToastService } from '../../core/notifications/toast.service';
import { PageTransition } from '../../shared/page-transition.service';
import { spyObj, type SpyObj } from '../../../testing/mocks';
import { CAPABILITIES_UNAVAILABLE, CapabilitiesApiService, LearningCapabilities } from '../authoring/capabilities-api.service';
import { ItemDetail, ItemPage, ItemSummary } from '../authoring/authoring.models';
import { ItemApiService } from '../authoring/item-api.service';
import { OwnDecksApiService } from '../own-decks/own-decks-api.service';
import { ESTIMATE_DEBOUNCE_MS } from './generation-composer.component';
import { ExerciseBuilderPageComponent } from './exercise-builder-page.component';
import { documentOf, exerciseSession } from './exercise-test-data';
import { GenerationApiService } from './generation-api.service';
import { deckFixture, ids, problemResponse, usageContract } from './generation-test-data';
import { parseEstimate, parseSessionDetail, serializeExercisesSpec } from './generation.models';

const key = (index: number) => `44444444-4444-4444-8444-${String(index).padStart(12, '0')}`;
const revision = (index: number) => `55555555-5555-4555-8555-${String(index).padStart(12, '0')}`;
const NB = '\u00a0';
const available: LearningCapabilities = { ...CAPABILITIES_UNAVAILABLE, aiGeneration: { available: true, reason: null } };

const row = (index: number, exerciseCount = index % 3): ItemSummary => ({ memberKey: key(index), itemRevisionId: revision(index), itemVersion: '1', ordinal: index,
    formatVersion: 1, createdAt: '2026-09-12T12:00:00Z', updatedAt: '2026-09-12T12:00:00Z', title: `Материал ${index}`, exerciseCount, exemplar: false });
const itemPage = (from: number, count: number, total: number): ItemPage => ({ deckId: ids.deckId, deckRevisionId: ids.deckRevision, deckVersion: '7', total,
    exemplars: { count: 0, limit: 10 }, items: Array.from({ length: count }, (_, index) => row(from + index)), nextCursor: from + count < total ? `c${from + count}` : null });
const detail = (index: number): ItemDetail => ({ memberKey: key(index), itemRevisionId: revision(index), itemVersion: '1', formatVersion: 1,
    createdAt: '2026-09-12T12:00:00Z', updatedAt: '2026-09-12T12:00:00Z', ordinal: index, exemplar: false, deckId: ids.deckId, deckRevisionId: ids.deckRevision,
    deckVersion: '7', document: documentOf(`Текст ${index}`) });

describe('ExerciseBuilderPageComponent', () => {
    let fixture: ComponentFixture<ExerciseBuilderPageComponent>;
    let api: SpyObj<GenerationApiService>;
    let items: { read: ReturnType<typeof vi.fn>; list: ReturnType<typeof vi.fn> };
    let transition: { navigate: ReturnType<typeof vi.fn> };
    let toast: { echo: ReturnType<typeof vi.fn> };
    let capabilities: LearningCapabilities | 'error';
    let readImpl: ((deck: string, member: string) => unknown) | null = null;
    let listImpl: ((deck: string, options?: { cursor?: string | null }) => unknown) | null = null;

    const root = (): HTMLElement => fixture.nativeElement as HTMLElement;
    const estimateAnswer = (): ReturnType<typeof parseEstimate> => parseEstimate(JSON.parse(JSON.stringify(usageContract['estimateResponse'])));
    const labelled = (label: string): HTMLElement | undefined =>
        [...root().querySelectorAll<HTMLElement>('button, a, label')].find(element => element.textContent!.trim() === label);
    const box = (label: string): HTMLInputElement => labelled(label)!.querySelector('input')!;
    const radio = (value: string): HTMLInputElement => root().querySelector<HTMLInputElement>(`input[type=radio][value=${value}]`)!;
    const cta = (): HTMLButtonElement => root().querySelector<HTMLButtonElement>('.generate-cta')!;
    const lastSpec = (): any => api.estimate.mock.calls.at(-1)![1];
    const created = (artifactSession = exerciseSession([], { state: 'RUNNING' })) => of({ session: parseSessionDetail(artifactSession), replayed: false });
    const wireSettings = (): any => (serializeExercisesSpec(lastSpec()) as any).settings;

    async function settle(ms = 0): Promise<void> {
        await vi.advanceTimersByTimeAsync(ms);
        fixture.detectChanges();
        await vi.advanceTimersByTimeAsync(0);
        fixture.detectChanges();
    }

    async function open(query: Record<string, string>): Promise<void> {
        TestBed.resetTestingModule();
        vi.useFakeTimers();
        api = spyObj<GenerationApiService>({ estimate: vi.fn(), createSession: vi.fn(), listActiveSessions: vi.fn() });
        items = { read: vi.fn(), list: vi.fn() };
        transition = { navigate: vi.fn().mockResolvedValue(true) };
        toast = { echo: vi.fn() };
        api.estimate.mockReturnValue(of(estimateAnswer()));
        api.listActiveSessions.mockReturnValue(of({ items: [], nextCursor: null }));
        items.read.mockImplementation(readImpl ?? ((_deck: string, member: string) => of(detail(Number(member.slice(-12))))));
        items.list.mockImplementation(listImpl ?? ((_deck: string, options?: { cursor?: string | null }) =>
            of(options?.cursor == null ? itemPage(1, 20, 45) : options.cursor === 'c21' ? itemPage(21, 20, 45) : itemPage(41, 5, 45))));
        TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: GenerationApiService, useValue: api }, { provide: ItemApiService, useValue: items },
            { provide: OwnDecksApiService, useValue: { detail: () => of(deckFixture) } },
            { provide: CapabilitiesApiService, useValue: { read: () => capabilities === 'error' ? throwError(() => new HttpErrorResponse({ status: 500 })) : of(capabilities) } },
            { provide: PageTransition, useValue: transition }, { provide: ToastService, useValue: toast },
            { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ deckId: ids.deckId }), queryParamMap: convertToParamMap(query) } } }] });
        fixture = TestBed.createComponent(ExerciseBuilderPageComponent);
        fixture.detectChanges();
        await settle();
    }

    beforeEach(() => { capabilities = available; readImpl = null; listImpl = null; });
    afterEach(() => vi.useRealTimers());

    describe('opening', () => {
        it('shows the heading, the targets as a summary with an expandable list, and the single primary button', async () => {
            await open({ members: `${key(1)},${key(2)}` });
            expect(root().querySelector('h1')?.textContent).toBe('Упражнения с ИИ');
            expect(root().querySelector('.eyebrow')?.textContent).toContain('Японский N4');
            expect(root().querySelector('.targets-title')?.textContent).toBe(`Для${NB}2${NB}материалов`);
            expect([...root().querySelectorAll('.targets-list li')].map(entry => entry.textContent)).toEqual(['Текст 1', 'Текст 2']);
            expect(root().querySelectorAll('.generate-cta')).toHaveLength(1);
            expect(cta().textContent).toBe('Создать упражнения');
            expect(root().querySelector('textarea')).toBeNull();
        });

        it('reads the current revisions itself: the request carries what the item reads answered, not what an address could say', async () => {
            await open({ members: key(1) });
            await settle(ESTIMATE_DEBOUNCE_MS);
            expect(items.read).toHaveBeenCalledWith(ids.deckId, key(1));
            expect(lastSpec().targets).toEqual([{ memberKey: key(1), itemRevisionId: revision(1) }]);
        });

        it('goes back to the material for one material and to the deck otherwise', async () => {
            await open({ members: key(1) });
            expect(root().querySelector('.back-link')?.getAttribute('href')).toBe(`/decks/${ids.deckId}/materials/${key(1)}`);
            expect(root().querySelector('.back-link')?.textContent).toContain('К материалу');
            await open({ members: `${key(1)},${key(2)}` });
            expect(root().querySelector('.back-link')?.getAttribute('href')).toBe(`/decks/${ids.deckId}`);
        });

        it('asks to choose materials when none is named', async () => {
            await open({});
            expect(root().textContent).toContain('Выберите материалы в колоде');
            expect(root().querySelector('form')).toBeNull();
        });

        it('says AI is unavailable and offers the manual editor, when the server does not offer generation or cannot be read', async () => {
            capabilities = CAPABILITIES_UNAVAILABLE;
            await open({ members: key(1) });
            expect(root().textContent).toContain('Помощник Мнема сейчас недоступен');
            expect(root().querySelector<HTMLAnchorElement>('a.button.primary')?.getAttribute('href')).toBe(`/decks/${ids.deckId}/materials/${key(1)}/exercises/new`);
            expect(root().querySelector('.generate-cta')).toBeNull();
            capabilities = 'error';
            await open({ members: key(1) });
            expect(root().textContent).toContain('Помощник Мнема сейчас недоступен');
        });

        it('says the materials are gone when none of them can be read, and names how many were skipped when some can', async () => {
            readImpl = () => throwError(() => problemResponse(404));
            await open({ members: key(1) });
            expect(root().textContent).toContain('Выбранных материалов больше нет');
            await open({ members: `${key(1)},bad` });
            expect(root().textContent).toContain('Выбранных материалов больше нет');
        });

        it('keeps working with what is left, and says how many were skipped', async () => {
            await open({ members: `${key(1)},nope` });
            expect(root().querySelector('form')).not.toBeNull();
            expect(root().textContent).toContain('Часть выбранного недоступна');
            expect(root().textContent).toContain('— 1.');
        });

        it('reads «all except» by paging the deck, and says when only the first materials are taken', async () => {
            listImpl = (_deck, options) => of(itemPage(options?.cursor == null ? 1 : Number(options.cursor.slice(1)), 20, 400));
            await open({ all: '1', except: key(2) });
            expect(root().textContent).toContain('Мнема берёт первые');
            expect(root().querySelector('.targets-title')?.textContent).toContain('200');
        });
    });

    describe('the choices', () => {
        it('starts with «Авто» for mechanics, UNCOVERED_FIRST for the priority and «Авто» for the quantity', async () => {
            await open({ members: `${key(1)},${key(2)}` });
            await settle(ESTIMATE_DEBOUNCE_MS);
            expect(box('Авто').checked).toBe(true);
            expect(wireSettings()).toEqual({ mechanics: 'AUTO', priority: 'UNCOVERED_FIRST', quantity: { mode: 'AUTO' }, planFirst: false, budgetPercent: null });
        });

        it('treats «Авто» as exclusive: a mechanic unchecks it, unchecking every mechanic returns to it, and «Авто» cannot be unchecked on its own', async () => {
            await open({ members: key(1) });
            box('Выбрать ответ').click();
            fixture.detectChanges();
            expect(box('Авто').checked).toBe(false);
            expect(box('Выбрать ответ').checked).toBe(true);
            box('Заполнить пропуски').click();
            fixture.detectChanges();
            await settle(ESTIMATE_DEBOUNCE_MS);
            expect(lastSpec().settings.mechanics).toEqual(['CLOZE', 'CHOICE']);
            box('Выбрать ответ').click();
            box('Заполнить пропуски').click();
            fixture.detectChanges();
            expect(box('Авто').checked).toBe(true);
            box('Авто').click();
            fixture.detectChanges();
            expect(box('Авто').checked).toBe(true);
            box('Выбрать ответ').click();
            fixture.detectChanges();
            box('Авто').click();
            fixture.detectChanges();
            expect(box('Выбрать ответ').checked).toBe(false);
            expect(box('Авто').checked).toBe(true);
        });

        it('offers the priority only with two or more materials, and explains the choice in a live text', async () => {
            await open({ members: key(1) });
            expect(root().textContent).not.toContain('Что сначала');
            await open({ members: `${key(1)},${key(2)}` });
            expect(root().textContent).toContain('Что сначала');
            expect(root().textContent).toContain('Сначала без упражнений');
            expect(root().textContent).toContain('Все выбранные поровну');
            expect(root().querySelector('.hint[aria-live=polite]')).not.toBeNull();
            radio('BALANCED').click();
            fixture.detectChanges();
            await settle(ESTIMATE_DEBOUNCE_MS);
            expect(lastSpec().settings.priority).toBe('BALANCED');
        });

        it('has three exclusive quantity modes: «Авто», «Точно» with a slider 1..10, «Не больше X% лимита» with a slider 1..100', async () => {
            await open({ members: key(1) });
            expect(root().querySelector('input[type=range]')).toBeNull();
            radio('EXACT').click();
            fixture.detectChanges();
            const exact = root().querySelector<HTMLInputElement>('input[type=range]')!;
            expect([exact.min, exact.max]).toEqual(['1', '10']);
            expect(exact.getAttribute('aria-valuetext')).toBe(`3${NB}упражнения на${NB}материал`);
            exact.value = '5';
            exact.dispatchEvent(new Event('input'));
            fixture.detectChanges();
            expect(exact.getAttribute('aria-valuetext')).toBe(`5${NB}упражнений на${NB}материал`);
            expect(root().querySelector('output')?.textContent).toBe(`5${NB}упражнений на${NB}материал`);
            await settle(ESTIMATE_DEBOUNCE_MS);
            expect(lastSpec().settings.quantity).toEqual({ mode: 'EXACT', perTarget: 5 });

            radio('BUDGET_PERCENT').click();
            fixture.detectChanges();
            const percent = root().querySelector<HTMLInputElement>('input[type=range]')!;
            expect([percent.min, percent.max]).toEqual(['1', '100']);
            percent.value = '25';
            percent.dispatchEvent(new Event('input'));
            fixture.detectChanges();
            expect(percent.getAttribute('aria-valuetext')).toBe(`Не больше 25${NB}% лимита`);
            await settle(ESTIMATE_DEBOUNCE_MS);
            expect(lastSpec().settings.quantity).toEqual({ mode: 'BUDGET_PERCENT', percent: 25 });

            radio('AUTO').click();
            fixture.detectChanges();
            expect(root().querySelector('input[type=range]')).toBeNull();
            await settle(ESTIMATE_DEBOUNCE_MS);
            expect(lastSpec().settings.quantity).toEqual({ mode: 'AUTO' });
        });
    });

    describe('implicit submission and the split notice', () => {
        const enter = (target: Element): KeyboardEvent => {
            const event = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true });
            target.dispatchEvent(event);
            return event;
        };

        it('does not let Enter on a checkbox, a radio or a slider start a priced session; a button keeps its own Enter', async () => {
            await open({ members: `${key(1)},${key(2)}` });
            expect(enter(box('Авто')).defaultPrevented).toBe(true);
            expect(enter(radio('BALANCED')).defaultPrevented).toBe(true);
            radio('EXACT').click();
            fixture.detectChanges();
            expect(enter(root().querySelector('input[type=range]')!).defaultPrevented).toBe(true);
            expect(enter(cta()).defaultPrevented).toBe(false);
            expect(api.createSession).not.toHaveBeenCalled();
        });

        it('names the order of a split only for «Сначала без упражнений», and says which materials wait above 60', async () => {
            await open({ all: '1' });
            expect(root().textContent).toContain('сначала с материалами без упражнений');
            radio('BALANCED').click();
            fixture.detectChanges();
            expect(root().textContent).toContain('Мнема откроет 3');
            expect(root().textContent).not.toContain('сначала с материалами без упражнений');
            listImpl = (_deck, options) => of(itemPage(options?.cursor == null ? 1 : Number(options.cursor.slice(1)), 20, 400));
            await open({ all: '1' });
            expect(root().textContent).toContain('не больше 3 мастерских');
            expect(root().textContent).toContain('в этот раз не войдут');
            api.createSession.mockReturnValue(created());
            await settle(ESTIMATE_DEBOUNCE_MS);
            cta().click();
            await settle();
            await settle();
            expect(api.createSession).toHaveBeenCalledTimes(3);
        });

        it('has the deck title alone above the heading, not a second «Упражнения с ИИ»', async () => {
            await open({ members: key(1) });
            expect(root().querySelector('.eyebrow')?.textContent).toBe('Японский N4');
        });
    });

    describe('the estimate', () => {
        it('asks once, 400 ms after the last change, and says «≈ 6 % лимита»', async () => {
            await open({ members: key(1) });
            expect(root().querySelector('.estimate')?.textContent).toBe('Считаем…');
            await settle(ESTIMATE_DEBOUNCE_MS - 1);
            expect(api.estimate).not.toHaveBeenCalled();
            box('Выбрать ответ').click();
            await settle(ESTIMATE_DEBOUNCE_MS - 1);
            expect(api.estimate).not.toHaveBeenCalled();
            await settle(1);
            expect(api.estimate).toHaveBeenCalledTimes(1);
            expect(root().querySelector('.estimate')?.textContent).toBe(`≈${NB}6${NB}% лимита`);
        });

        it('cancels the request in flight when the choice changes', async () => {
            await open({ members: key(1) });
            const first = new Subject<ReturnType<typeof estimateAnswer>>();
            api.estimate.mockReturnValueOnce(first);
            await settle(ESTIMATE_DEBOUNCE_MS);
            expect(first.observed).toBe(true);
            box('Выбрать ответ').click();
            await settle();
            expect(first.observed).toBe(false);
        });

        it('explains a limit answer in words with the numbers, and does not start a session on press', async () => {
            await open({ members: key(1) });
            api.estimate.mockReturnValue(throwError(() => problemResponse(422, { code: 'RESOURCE_LIMIT_EXCEEDED', limit: 'EXERCISES_PER_SESSION',
                limits: { maxExerciseTargets: 20, maxExercisesPerTarget: 10, maxExercisesPerSession: 60 } })));
            radio('EXACT').click();
            fixture.detectChanges();
            await settle(ESTIMATE_DEBOUNCE_MS);
            expect(root().querySelector('.estimate')?.textContent).toContain('не больше 60');
            expect(cta().getAttribute('aria-disabled')).toBe('true');
            cta().click();
            await settle();
            expect(root().querySelector('[role=alert]')?.textContent).toContain('не больше 60');
            expect(api.createSession).not.toHaveBeenCalled();
        });

        it('says it could not estimate but still lets the user start', async () => {
            await open({ members: key(1) });
            api.estimate.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
            box('Выбрать ответ').click();
            await settle(ESTIMATE_DEBOUNCE_MS);
            expect(root().querySelector('.estimate')?.textContent).toBe('Оценить не удалось, но запустить можно.');
            expect(cta().getAttribute('aria-disabled')).toBeNull();
        });

        it('explains the options when the budget does not cover the request, without blocking the button', async () => {
            await open({ members: key(1) });
            const short = JSON.parse(JSON.stringify(usageContract['estimateResponse']));
            short.canStart = false;
            short.blockingBuckets = [{ bucket: 'ai', window: 'MONTH', unit: 'CREDITS', limit: 100, used: 95, required: 10, offered: true, renewsAt: '2026-11-01T00:00:00Z',
                fitsAfterRenewal: true, plan: 'FREE' }];
            short.shortfallCredits = 5;
            api.estimate.mockReturnValue(of(parseEstimate(short)));
            box('Выбрать ответ').click();
            await settle(ESTIMATE_DEBOUNCE_MS);
            expect(root().querySelector('.estimate')?.textContent).toContain('Не хватит лимита');
            cta().click();
            await settle();
            expect(root().querySelector('.limit')?.textContent).toContain('Не хватит лимита ИИ на этот запрос.');
            expect(root().querySelector('.limit')?.textContent).not.toContain('Кратко');
            expect(api.createSession).not.toHaveBeenCalled();
        });

        it('warns about personal data the way the Materials composer does', async () => {
            await open({ members: key(1) });
            const flagged = JSON.parse(JSON.stringify(usageContract['estimateResponse']));
            flagged.warnings = [{ code: 'PERSONAL_DATA_SUSPECTED' }];
            api.estimate.mockReturnValue(of(parseEstimate(flagged)));
            box('Выбрать ответ').click();
            await settle(ESTIMATE_DEBOUNCE_MS);
            expect(root().textContent).toContain('личные данные');
        });
    });

    describe('creating', () => {

        it('creates the session and opens its Workshop', async () => {
            await open({ members: `${key(1)},${key(2)}` });
            api.createSession.mockReturnValue(created());
            await settle(ESTIMATE_DEBOUNCE_MS);
            cta().click();
            await settle();
            expect(api.createSession).toHaveBeenCalledTimes(1);
            const [deck, spec, commandId] = api.createSession.mock.calls[0]!;
            expect(deck).toBe(ids.deckId);
            expect((spec as any).targets).toHaveLength(2);
            expect(commandId).toMatch(/^[0-9a-f-]{36}$/);
            expect(transition.navigate).toHaveBeenCalledWith(['/decks', ids.deckId, 'workshop', ids.sessionId]);
            expect(toast.echo).not.toHaveBeenCalled();
        });

        it('ignores a second press while creating', async () => {
            await open({ members: key(1) });
            const pending = new Subject<ReturnType<typeof created> extends import('rxjs').Observable<infer V> ? V : never>();
            api.createSession.mockReturnValue(pending);
            await settle(ESTIMATE_DEBOUNCE_MS);
            cta().click();
            cta().click();
            await settle();
            expect(api.createSession).toHaveBeenCalledTimes(1);
            expect(cta().textContent).toBe('Создаём…');
            expect(root().querySelector('section')?.getAttribute('aria-busy')).toBe('true');
        });

        it('splits more than 20 materials into sessions, says so before starting, uncovered first, one command each, then opens the first', async () => {
            await open({ all: '1' });
            const note = root().querySelector('.notice[role=status]:not(.limit)');
            expect(root().textContent).toContain('Мнема откроет 3');
            expect(note).not.toBeNull();
            api.createSession.mockImplementation((_deck, _spec, _command) => created(exerciseSession([], { state: 'RUNNING', sessionId: `5e550000-0000-4000-8000-00000000000${api.createSession.mock.calls.length}` })));
            await settle(ESTIMATE_DEBOUNCE_MS);
            cta().click();
            await settle();
            await settle();
            expect(api.createSession).toHaveBeenCalledTimes(3);
            const specs = api.createSession.mock.calls.map(call => (call[1] as any).targets as { memberKey: string }[]);
            expect(specs.map(targets => targets.length)).toEqual([20, 20, 5]);
            expect(new Set(api.createSession.mock.calls.map(call => call[2])).size).toBe(3);
            // The materials with no exercises (every third one has none) come first.
            expect(specs[0]!.slice(0, 3).map(target => target.memberKey)).toEqual([key(3), key(6), key(9)]);
            expect(new Set(specs.flat().map(target => target.memberKey)).size).toBe(45);
            expect(transition.navigate).toHaveBeenCalledTimes(1);
            expect(transition.navigate).toHaveBeenCalledWith(['/decks', ids.deckId, 'workshop', '5e550000-0000-4000-8000-000000000001']);
            expect(toast.echo).toHaveBeenCalledWith(expect.stringContaining('мастерских: 3'));
        });

        it('stops at a refusal of a later session, says how many were opened, takes their materials off the form and links the Workshops', async () => {
            await open({ all: '1' });
            api.createSession.mockReturnValueOnce(created());
            api.createSession.mockReturnValueOnce(throwError(() => problemResponse(422, { code: 'RESOURCE_LIMIT_EXCEEDED', limit: 'ACTIVE_SESSIONS' })));
            api.listActiveSessions.mockReturnValue(of({ items: [parseSessionDetail(exerciseSession([]))], nextCursor: null }));
            await settle(ESTIMATE_DEBOUNCE_MS);
            cta().click();
            await settle();
            await settle();
            expect(api.createSession).toHaveBeenCalledTimes(2);
            const alert = root().querySelector('[role=alert]')!;
            expect(alert.textContent).toContain('Уже идут три мастерские');
            expect(alert.textContent).toContain('Открыто мастерских: 1 из 3');
            expect(alert.querySelectorAll('.workshop-links a').length).toBeGreaterThanOrEqual(1);
            expect(root().querySelector('[aria-label="Уже открытые мастерские"] a')).not.toBeNull();
            expect(root().querySelector('.targets-title')?.textContent).toBe(`Для${NB}25${NB}материалов`);
            expect(transition.navigate).not.toHaveBeenCalled();
        });

        it('never opens a second session for materials it already opened: pressing again continues with what is left, with the same commands', async () => {
            await open({ all: '1' });
            const answer = (index: number) => created(exerciseSession([], { state: 'RUNNING', sessionId: `5e550000-0000-4000-8000-00000000000${index}` }));
            api.createSession.mockReturnValueOnce(answer(1));
            api.createSession.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 503 })));
            api.createSession.mockReturnValue(answer(2));
            await settle(ESTIMATE_DEBOUNCE_MS);
            cta().click();
            await settle();
            await settle();
            const firstBatch = (api.createSession.mock.calls[0]![1] as any).targets.map((target: any) => target.memberKey) as string[];
            expect(api.createSession).toHaveBeenCalledTimes(2);
            cta().click();
            await settle();
            await settle();
            const calls = api.createSession.mock.calls;
            // The second press repeats the batch whose outcome was unknown (same command) and goes on; the first batch never comes back.
            expect(calls[2]![2]).toBe(calls[1]![2]);
            const later = calls.slice(2).flatMap(call => (call[1] as any).targets.map((target: any) => target.memberKey) as string[]);
            expect(later.some(member => firstBatch.includes(member))).toBe(false);
            expect(transition.navigate).toHaveBeenCalledWith(['/decks', ids.deckId, 'workshop', '5e550000-0000-4000-8000-000000000001']);
        });

        it('keeps the button busy until the Workshop is open', async () => {
            await open({ members: key(1) });
            let resolve!: (value: boolean) => void;
            transition.navigate.mockReturnValue(new Promise<boolean>(done => { resolve = done; }));
            api.createSession.mockReturnValue(created());
            await settle(ESTIMATE_DEBOUNCE_MS);
            cta().click();
            await settle();
            await settle();
            expect(api.createSession).toHaveBeenCalledTimes(1);
            expect(cta().textContent).toBe('Создаём…');
            cta().click();
            await settle();
            expect(api.createSession).toHaveBeenCalledTimes(1);
            resolve(true);
            await settle();
            expect(cta().textContent).toBe('Создать упражнения');
        });

        it('sends the very same command again after an answer that never came, and a new one when the request changed', async () => {
            await open({ members: key(1) });
            api.createSession.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 503 })));
            api.createSession.mockReturnValue(created());
            await settle(ESTIMATE_DEBOUNCE_MS);
            cta().click();
            await settle();
            expect(root().querySelector('[role=alert]')?.textContent).toContain('Не удалось подтвердить действие');
            cta().click();
            await settle();
            expect(api.createSession.mock.calls[1]![2]).toBe(api.createSession.mock.calls[0]![2]);
            await open({ members: key(1) });
            api.createSession.mockReset();
            api.createSession.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 503 })));
            api.createSession.mockReturnValue(created());
            await settle(ESTIMATE_DEBOUNCE_MS);
            cta().click();
            await settle();
            box('Выбрать ответ').click();
            await settle(ESTIMATE_DEBOUNCE_MS);
            cta().click();
            await settle();
            expect(api.createSession.mock.calls[1]![2]).not.toBe(api.createSession.mock.calls[0]![2]);
        });

        it('explains a refused budget from the answer of the server, and a limit with its numbers', async () => {
            await open({ members: key(1) });
            api.createSession.mockReturnValueOnce(throwError(() => problemResponse(409, { code: 'USAGE_LIMIT_REACHED', bucket: 'ai', window: 'DAY', unit: 'CREDITS', limit: 10,
                used: 10, required: 4, offered: true, renewsAt: '2026-10-04T00:00:00Z', fitsAfterRenewal: true, plan: 'FREE' })));
            await settle(ESTIMATE_DEBOUNCE_MS);
            cta().click();
            await settle();
            expect(root().querySelector('.limit')?.textContent).toContain('На сегодня лимит ИИ исчерпан.');
            api.createSession.mockReturnValueOnce(throwError(() => problemResponse(422, { code: 'RESOURCE_LIMIT_EXCEEDED', limit: 'EXERCISES_PER_TARGET',
                limits: { maxExerciseTargets: 20, maxExercisesPerTarget: 8, maxExercisesPerSession: 60 } })));
            cta().click();
            await settle();
            expect(root().querySelector('[role=alert]')?.textContent).toContain('не больше 8');
            api.createSession.mockReturnValueOnce(throwError(() => problemResponse(409, { code: 'CAPABILITY_UNAVAILABLE' })));
            cta().click();
            await settle();
            expect(root().querySelector('[role=alert]')?.textContent).toContain('ИИ сейчас недоступен');
        });
    });
});
