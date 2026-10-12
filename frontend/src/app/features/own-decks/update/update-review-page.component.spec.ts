import { HttpErrorResponse, HttpHeaders, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject, Subject, of, throwError } from 'rxjs';

import { ToastService } from '../../../core/notifications/toast.service';
import { AutoLoadComponent } from '../../../shared/auto-load.component';
import { UPDATE_TEXT } from '../../../shared/copy-text';
import { documentOf, nativeNode } from '../../../content/rendering/native-renderer.fixtures';
import { SpyObj, spyObj } from '../../../../testing/mocks';
import { isCanonicalCommandId } from '../own-deck.models';
import type { OwnDeck } from '../own-deck.models';
import { OwnDecksApiService } from '../own-decks-api.service';
import { FORK_POLL_LIMIT } from '../fork/fork-poll';
import { UpdatePlanApiService } from './update-plan-api.service';
import { ApplyAnswer, ApplyCommand, PlanUnit, UpdatePlanPage } from './update-plan.models';
import { UpdateReviewPageComponent } from './update-review-page.component';
import { APPLYING_COMMAND, DECK_ID, DOC_AUTHOR, DOC_MINE, PLAN_VERSION, closedPage, exerciseSides, keyOf, materialSides, planUnit, readyPage } from './update-test-data';

const problem = (status: number, code?: string, extra: Record<string, unknown> = {}) => new HttpErrorResponse({ status, error: code === undefined ? null : { code, ...extra } });
const busy = () => new HttpErrorResponse({ status: 503, error: { code: 'UPDATE_PLAN_BUSY', retryAfter: 1 }, headers: new HttpHeaders({ 'Retry-After': '1' }) });
const refusal = (reason: string, unitId?: string, dependency?: string) => problem(400, 'INVALID_REQUEST', { reason, ...(unitId === undefined ? {} : { unitId }), ...(dependency === undefined ? {} : { dependency }) });

describe('UpdateReviewPageComponent', () => {
    let fixture: ComponentFixture<UpdateReviewPageComponent>;
    let api: SpyObj<UpdatePlanApiService>;
    let decks: SpyObj<OwnDecksApiService>;
    let toasts: SpyObj<ToastService>;
    let params: BehaviorSubject<ReturnType<typeof convertToParamMap>>;

    const root = () => fixture.nativeElement as HTMLElement;
    const text = (selector: string) => root().querySelector(selector)?.textContent?.replace(/\s+/gu, ' ').trim();
    const rowTitles = () => [...root().querySelectorAll('app-update-unit-row .title')].map(title => title.textContent!.trim());
    const rows = () => [...root().querySelectorAll<HTMLElement>('app-update-unit-row')];
    const rowOf = (title: string) => rows().find(row => row.querySelector('.title')!.textContent!.trim() === title)!;
    const radios = (row: HTMLElement) => [...row.querySelectorAll<HTMLInputElement>('input[type=radio]')];
    const radioLabel = (row: HTMLElement, label: string) => radios(row).find(radio => radio.labels![0]!.textContent!.trim() === label)!;
    const tabs = () => [...root().querySelectorAll<HTMLButtonElement>('[role=tab]')];
    const tab = (name: string) => tabs().find(candidate => candidate.querySelector('.name')!.textContent === name)!;
    const button = (label: string) => [...root().querySelectorAll<HTMLButtonElement>('button')].find(candidate => candidate.textContent!.trim() === label)!;
    const live = () => root().querySelector('.update-page > p.visually-hidden[role=status]')!.textContent;
    const lastApply = (): ApplyCommand => api.apply.mock.lastCall![1];

    async function settle(): Promise<void> {
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
    }

    async function open(first: UpdatePlanPage | ReturnType<typeof throwError>): Promise<void> {
        api.plan.mockReturnValueOnce('state' in first ? of(first) : first);
        fixture = TestBed.createComponent(UpdateReviewPageComponent);
        await settle();
    }

    beforeEach(async () => {
        api = spyObj<UpdatePlanApiService>({ plan: vi.fn().mockName('plan'), unit: vi.fn().mockName('unit'), apply: vi.fn().mockName('apply') });
        decks = spyObj<OwnDecksApiService>({ detail: vi.fn().mockName('detail') });
        decks.detail.mockReturnValue(of({ deckId: DECK_ID, metadata: { title: 'Моя испанская копия', description: '' }, fork: { upstreamTitle: 'Испанский язык: базовые глаголы', upstreamCode: 'Kq7xT3mNpR' } } as unknown as OwnDeck));
        toasts = spyObj<ToastService>({ echo: vi.fn().mockName('echo'), echoError: vi.fn().mockName('echoError') });
        params = new BehaviorSubject(convertToParamMap({ deckId: DECK_ID }));
        await TestBed.configureTestingModule({
            imports: [UpdateReviewPageComponent],
            providers: [
                provideRouter([{ path: '**', children: [] }]),
                provideHttpClient(),
                provideHttpClientTesting(),
                { provide: UpdatePlanApiService, useValue: api },
                { provide: OwnDecksApiService, useValue: decks },
                { provide: ToastService, useValue: toasts },
                { provide: ActivatedRoute, useValue: { paramMap: params } }
            ]
        }).compileComponents();
    });

    const material = (index: number, unitClass: PlanUnit['class'], extra: Partial<PlanUnit> = {}) => planUnit('MATERIAL', index, unitClass, extra);
    const exercise = (index: number, unitClass: PlanUnit['class'], extra: Partial<PlanUnit> = {}) => planUnit('EXERCISE', index, unitClass, extra);

    /** New material 1 and the exercise 2 that needs it, a fix 3, a conflict 4, a deletion 5 and a unit changed again 6. */
    const mixed = (): PlanUnit[] => [
        material(1, 'NEW'), exercise(2, 'NEW', { dependsOn: [`MATERIAL:${keyOf(1)}`] }), material(3, 'SAFE'),
        material(4, 'CONFLICT'), material(5, 'DELETED'), material(6, 'ASK_AGAIN')
    ];

    describe('the screen', () => {
        it('names itself, the copy and its source, and loads the plan once', async () => {
            await open(readyPage(mixed()));
            expect(text('h1')).toBe('Обновление от автора');
            expect(root().querySelector('h1')!.id).toBe('update-title');
            expect(text('.eyebrow')).toBe('Моя испанская копия');
            expect(text('.lede')).toBe('Копия колоды «Испанский язык: базовые глаголы»');
            expect(text('a.back-link')).toBe('← К колоде');
            expect(root().querySelector('a.back-link')!.getAttribute('href')).toBe(`/decks/${DECK_ID}`);
            expect(api.plan).toHaveBeenCalledTimes(1);
            expect(api.plan).toHaveBeenCalledWith(DECK_ID);
        });

        it('links to the history of updates from every loaded state, and from neither the loading line nor the failure', async () => {
            const link = () => root().querySelector<HTMLAnchorElement>('a[href$="/update/history"]');
            await open(readyPage(mixed()));
            expect(link()!.textContent!.trim()).toBe('История обновлений');
            expect(link()!.getAttribute('href')).toBe(`/decks/${DECK_ID}/update/history`);
            expect(link()!.classList.contains('quiet')).toBe(true);
            await open(closedPage('DETACHED'));
            expect(link()).not.toBeNull();
            await open(readyPage([], { state: 'UP_TO_DATE' }));
            expect(link()).not.toBeNull();
            await open(throwError(() => problem(500)));
            expect(link()).toBeNull();
        });

        it('shows a loading line, then the summary with the author\'s note and the counts of the whole plan', async () => {
            const pending = new Subject<UpdatePlanPage>();
            api.plan.mockReturnValueOnce(pending);
            fixture = TestBed.createComponent(UpdateReviewPageComponent);
            await settle();
            expect(text('.update-page > p.status')).toBe('Загружаем обновление…');
            pending.next(readyPage(mixed(), { releaseNote: 'Добавили глаголы' }));
            await settle();
            expect(root().querySelector('.update-page > p.status')).toBeNull();
            expect(text('app-update-summary .line')).toBe('+1 материал, +1 упражнение, 1 исправление, 2 конфликта, 1 удалено автором');
            expect(text('app-update-summary .news-text')).toBe('Добавили глаголы');
            expect(text('app-update-summary button.primary')).toBe('Принять всё безопасное');
            expect(root().querySelectorAll('h1')).toHaveLength(1);
            expect(root().querySelector('app-update-summary h2')!.textContent).toBe('Что изменил автор');
        });

        it.each([
            ['PREPARING', 'Готовим колоду…', 'Обновления станут доступны, когда копия будет готова.'],
            ['DETACHED', 'Колода отвязана от автора, обновлений нет', 'Всё, что есть в копии, остаётся с вами.'],
            ['UP_TO_DATE', 'У вас всё как у автора', 'Новых изменений от автора сейчас нет.']
        ] as const)('says %s in words and offers no apply button', async (state, title, note) => {
            await open(state === 'UP_TO_DATE' ? readyPage([], { state }) : closedPage(state));
            expect(text('h2')).toBe(title);
            expect(text('section.notice p')).toBe(note);
            expect(root().querySelector('app-update-summary, app-count-tabs, [role=tabpanel]')).toBeNull();
        });

        it('shows the approved sentence for a closed source with app-unavailable-note', async () => {
            await open(closedPage('SOURCE_UNAVAILABLE'));
            expect(text('app-unavailable-note .title')).toBe('Источник больше недоступен');
            expect(text('app-unavailable-note .hint')).toContain('Автор закрыл исходную колоду.');
            expect(root().querySelector('app-update-summary')).toBeNull();
        });

        it('lets a prepared copy check again from the preparing state', async () => {
            await open(closedPage('PREPARING'));
            api.plan.mockReturnValueOnce(of(readyPage(mixed())));
            button('Проверить снова').click();
            await settle();
            expect(api.plan).toHaveBeenCalledTimes(2);
            expect(root().querySelector('app-update-summary')).not.toBeNull();
        });

        it('says that the load failed and retries; a missing copy is not retried', async () => {
            await open(throwError(() => problem(500)));
            expect(text('.notice.error h2')).toBe('Не удалось открыть обновление');
            api.plan.mockReturnValueOnce(of(readyPage(mixed())));
            button('Повторить').click();
            await settle();
            expect(root().querySelector('.notice.error')).toBeNull();
            expect(rows().length).toBeGreaterThan(0);

            api.plan.mockReturnValueOnce(throwError(() => problem(404)));
            params.next(convertToParamMap({ deckId: '44444444-4444-4444-8444-444444444444' }));
            await settle();
            expect(text('.notice.error p')).toBe('Колода не найдена или больше недоступна.');
            expect(root().querySelector('.notice.error button')).toBeNull();
        });

        it('treats a malformed deck id as a missing copy without asking the server', async () => {
            params.next(convertToParamMap({ deckId: 'nonsense' }));
            fixture = TestBed.createComponent(UpdateReviewPageComponent);
            await settle();
            expect(api.plan).not.toHaveBeenCalled();
            expect(text('.notice.error p')).toBe('Колода не найдена или больше недоступна.');
        });

        it('lowercases the id and keeps the source line out when the deck read fails', async () => {
            decks.detail.mockReturnValue(throwError(() => problem(500)));
            await open(readyPage(mixed()));
            expect(root().querySelector('.lede')).toBeNull();
            expect(root().querySelector('.eyebrow')).toBeNull();
            expect(rows().length).toBeGreaterThan(0);
        });

        it('moves the focus to the title once, when the focus was lost', async () => {
            await open(readyPage(mixed()));
            expect(document.activeElement).toBe(root().querySelector('h1'));
        });
    });

    describe('tabs and rows', () => {
        it('counts the tabs from the whole plan, with the conflicts joined by what changed again', async () => {
            await open(readyPage(mixed()));
            expect(tabs().map(candidate => candidate.textContent!.replace(/\s+/gu, ' ').trim())).toEqual(['Новое: 2', 'Исправления: 1', 'Конфликты: 2', 'Удалено автором: 1']);
            expect(root().querySelector('[role=tablist]')!.getAttribute('aria-label')).toBe('Виды изменений');
        });

        it('selects the first tab that has something, and filters the loaded units by class', async () => {
            await open(readyPage([material(3, 'SAFE'), material(4, 'CONFLICT'), material(6, 'ASK_AGAIN'), material(5, 'DELETED')]));
            expect(tab('Исправления').getAttribute('aria-selected')).toBe('true');
            expect(rowTitles()).toEqual(['Материал 3']);
            tab('Конфликты').click();
            await settle();
            expect(rowTitles()).toEqual(['Материал 4', 'Материал 6']);
            expect([...root().querySelectorAll('.meta .stamp')].map(stamp => stamp.textContent)).toEqual(['Конфликт', 'Снова изменено']);
            tab('Удалено автором').click();
            await settle();
            expect(rowTitles()).toEqual(['Материал 5']);
            expect(text('.meta .stamp')).toBe('Удалено автором');
        });

        it('makes the panel the tab\'s own and names the heading levels without a jump', async () => {
            await open(readyPage(mixed()));
            const panel = root().querySelector<HTMLElement>('[role=tabpanel]')!;
            expect(panel.getAttribute('aria-labelledby')).toBe(tab('Новое').id);
            expect(tab('Новое').getAttribute('aria-controls')).toBe(panel.id);
            expect(root().querySelector('h2.changes-heading')!.textContent).toBe('Изменения');
            expect(panel.querySelectorAll('h3.title')).toHaveLength(2);
        });

        it('says an empty tab is empty', async () => {
            await open(readyPage([material(1, 'NEW')]));
            tab('Конфликты').click();
            await settle();
            expect(text('.empty')).toBe('В этой вкладке ничего нет.');
            expect(rows()).toHaveLength(0);
        });

        it('names a unit without a title by its kind', async () => {
            await open(readyPage([material(1, 'NEW', { title: '' }), planUnit('ORDER', 2, 'SAFE', { title: null })]));
            expect(rowTitles()).toEqual(['Материал недоступен']);
            tab('Исправления').click();
            await settle();
            expect(rowTitles()).toEqual(['Порядок материалов']);
        });

        it('shows the dependency a unit brings with it', async () => {
            await open(readyPage(mixed()));
            expect(rowOf('Материал 1').querySelector('.dependency')).toBeNull();
            expect(rowOf('Упражнение 2').querySelector('.dependency')!.textContent).toBe('Нужен материал «Материал 1». Он будет добавлен вместе с этим.');
        });
    });

    describe('paging', () => {
        const manyNew = (count: number): PlanUnit[] => Array.from({ length: count }, (_, index) => material(index + 1, 'NEW'));
        const ids = (units: readonly PlanUnit[]) => units.map(unit => unit.unitId);

        it('continues the list through app-auto-load with the cursor and the page size, and adds rows without page buttons', async () => {
            const first = manyNew(50);
            const second = Array.from({ length: 5 }, (_, index) => material(51 + index, 'NEW'));
            await open(readyPage(first, { nextCursor: 'cursor-1', summary: { ...readyPage([]).summary!, newMaterials: 55 } }));
            expect(rows()).toHaveLength(50);
            expect(root().querySelector('button.load-more, .pagination')).toBeNull();
            api.plan.mockReturnValueOnce(of(readyPage(second, { nextCursor: null, summary: { ...readyPage([]).summary!, newMaterials: 55 } })));
            fixture.debugElement.query(By.directive(AutoLoadComponent)).componentInstance.loadNext.emit();
            await settle();
            expect(api.plan).toHaveBeenLastCalledWith(DECK_ID, 'cursor-1');
            expect(rows()).toHaveLength(55);
            expect(fixture.debugElement.query(By.directive(AutoLoadComponent)).componentInstance.continuation()).toBeNull();
        });

        it('keeps loading by itself while the active tab shows fewer rows than a page, and stops when the tab\'s count is reached', async () => {
            const firstPage = [...manyNew(3), material(90, 'SAFE')];
            const summary = { ...readyPage([]).summary!, newMaterials: 3, fixes: 1, deletedByAuthor: 1 };
            await open(readyPage(firstPage, { nextCursor: 'cursor-1', summary }));
            tab('Удалено автором').click();
            api.plan.mockReturnValueOnce(of(readyPage([material(95, 'DELETED')], { nextCursor: 'cursor-2', summary })));
            await settle();
            expect(api.plan).toHaveBeenCalledTimes(2);
            expect(api.plan).toHaveBeenLastCalledWith(DECK_ID, 'cursor-1');
            expect(rowTitles()).toEqual(['Материал 95']);
            // The count of the tab is reached: cursor-2 is not followed although a cursor exists.
            await settle();
            expect(api.plan).toHaveBeenCalledTimes(2);
        });

        it('stops searching a tab as soon as a unit of a later class has arrived', async () => {
            const summary = { ...readyPage([]).summary!, newMaterials: 5, fixes: 1 };
            await open(readyPage([material(1, 'NEW'), material(2, 'SAFE')], { nextCursor: 'cursor-1', summary }));
            // The plan promised five new units but the list already moved on to fixes: the tab is complete.
            await settle();
            expect(api.plan).toHaveBeenCalledTimes(1);
            expect(rowTitles()).toEqual(['Материал 1']);
        });

        it('says it is searching and ends the search at the end of the list', async () => {
            const summary = { ...readyPage([]).summary!, newMaterials: 1, conflicts: 3 };
            await open(readyPage([material(1, 'NEW')], { nextCursor: 'cursor-1', summary }));
            const next = new Subject<UpdatePlanPage>();
            api.plan.mockReturnValueOnce(next);
            tab('Конфликты').click();
            await settle();
            expect(text('app-auto-load .status')).toBe('Ищем записи этой вкладки…');
            next.next(readyPage([material(10, 'CONFLICT'), material(11, 'CONFLICT')], { nextCursor: null, summary }));
            await settle();
            expect(rowTitles()).toEqual(['Материал 10', 'Материал 11']);
            expect(api.plan).toHaveBeenCalledTimes(2);
        });

        it('does not repeat a unit a page lists twice and treats an unchanged cursor as the end', async () => {
            const summary = { ...readyPage([]).summary!, newMaterials: 60 };
            await open(readyPage(manyNew(50), { nextCursor: 'cursor-1', summary }));
            api.plan.mockReturnValueOnce(of(readyPage([...manyNew(2), material(51, 'NEW')], { nextCursor: 'cursor-1', summary })));
            fixture.debugElement.query(By.directive(AutoLoadComponent)).componentInstance.loadNext.emit();
            await settle();
            expect(rows()).toHaveLength(51);
            expect(ids(manyNew(2))).toHaveLength(2);
            expect(fixture.debugElement.query(By.directive(AutoLoadComponent)).componentInstance.continuation()).toBeNull();
        });

        it('rereads the plan from the first page when the cursor is stale, says so once, and keeps the reader\'s picks', async () => {
            const summary = { ...readyPage([]).summary!, newMaterials: 60 };
            await open(readyPage(manyNew(50), { nextCursor: 'cursor-1', summary }));
            radioLabel(rowOf('Материал 1'), 'Не брать').click();
            api.plan.mockReturnValueOnce(throwError(() => problem(409, 'UPDATE_PLAN_STALE')));
            api.plan.mockReturnValueOnce(of(readyPage(manyNew(50), { nextCursor: 'cursor-9', planVersion: `sha256:${'cd'.repeat(32)}`, summary })));
            fixture.debugElement.query(By.directive(AutoLoadComponent)).componentInstance.loadNext.emit();
            await settle();
            expect(api.plan.mock.calls.map(call => call[1])).toEqual([undefined, 'cursor-1', undefined]);
            expect(text('.notice.compact')).toBe('Пока вы смотрели, колода изменилась. Мы обновили список.');
            expect(live()).toBe('Пока вы смотрели, колода изменилась. Мы обновили список.');
            expect(radioLabel(rowOf('Материал 1'), 'Не брать').checked).toBe(true);
        });

        it('shows a failed page with «Повторить» and tries the same cursor again', async () => {
            const summary = { ...readyPage([]).summary!, newMaterials: 60 };
            await open(readyPage(manyNew(50), { nextCursor: 'cursor-1', summary }));
            api.plan.mockReturnValueOnce(throwError(() => problem(500)));
            fixture.debugElement.query(By.directive(AutoLoadComponent)).componentInstance.loadNext.emit();
            await settle();
            expect(text('app-auto-load .notice.error p')).toBe('Не удалось загрузить обновление. Проверьте соединение и повторите.');
            api.plan.mockReturnValueOnce(of(readyPage(manyNew(55).slice(50), { nextCursor: null, summary })));
            root().querySelector<HTMLButtonElement>('app-auto-load .notice.error button')!.click();
            await settle();
            expect(api.plan).toHaveBeenLastCalledWith(DECK_ID, 'cursor-1');
            expect(rows()).toHaveLength(55);
        });
    });

    describe('opening a row', () => {
        it('fetches the sides once with the plan version, and draws a material in the shared comparison', async () => {
            const units = [material(4, 'CONFLICT')];
            await open(readyPage(units));
            api.unit.mockReturnValue(of(materialSides(units[0]!, DOC_MINE, DOC_AUTHOR)));
            const toggle = () => rowOf('Материал 4').querySelector<HTMLButtonElement>('button[aria-expanded]')!;
            toggle().click();
            await settle();
            expect(api.unit).toHaveBeenCalledTimes(1);
            expect(api.unit).toHaveBeenCalledWith(DECK_ID, 'MATERIAL', keyOf(4), PLAN_VERSION);
            const columns = rowOf('Материал 4').querySelector('app-compare-columns')!;
            expect(columns.querySelectorAll('app-compared-document')).toHaveLength(2);
            expect(columns.querySelector('[data-column=mine]')!.textContent).toContain('Мой вариант');
            expect(columns.querySelector('[data-column=author]')!.textContent).toContain('Вариант автора');
            expect(columns.querySelector('h4.eyebrow')).not.toBeNull();
            toggle().click();
            await settle();
            expect(rowOf('Материал 4').querySelector('app-compare-columns')).toBeNull();
            toggle().click();
            await settle();
            expect(api.unit).toHaveBeenCalledTimes(1);
            expect(rowOf('Материал 4').querySelector('app-compare-columns')).not.toBeNull();
        });

        it('says «Нет у вас» for a new unit and projects an exercise into words', async () => {
            const units = [exercise(2, 'NEW')];
            await open(readyPage(units));
            api.unit.mockReturnValue(of(exerciseSides(units[0]!)));
            rowOf('Упражнение 2').querySelector<HTMLButtonElement>('button[aria-expanded]')!.click();
            await settle();
            const body = rowOf('Упражнение 2');
            expect(body.querySelector('[data-column=mine]')!.textContent).toContain('Нет у вас');
            const lines = [...body.querySelectorAll('[data-column=author] .line')].map(line => [...line.children].map(child => child.textContent));
            expect(lines).toEqual([
                ['Тип', 'Выбрать ответ'],
                ['Вопрос', 'Что значит hablar?'],
                ['Варианты', 'Можно выбрать один', 'говорить (верный)', 'есть']
            ]);
        });

        it('shows the loading line, and a failure with «Повторить» that asks again', async () => {
            const units = [material(4, 'CONFLICT')];
            await open(readyPage(units));
            const pending = new Subject<ReturnType<typeof materialSides>>();
            api.unit.mockReturnValueOnce(pending);
            rowOf('Материал 4').querySelector<HTMLButtonElement>('button[aria-expanded]')!.click();
            await settle();
            expect(text('app-update-unit-row .body .hint')).toBe('Загружаем сравнение…');
            pending.error(problem(500));
            await settle();
            expect(text('app-update-unit-row .body .notice.error p')).toBe('Не удалось загрузить сравнение.');
            api.unit.mockReturnValueOnce(of(materialSides(units[0]!, DOC_MINE, null)));
            rowOf('Материал 4').querySelector<HTMLButtonElement>('.body .notice.error button')!.click();
            await settle();
            expect(api.unit).toHaveBeenCalledTimes(2);
            expect(rowOf('Материал 4').querySelector('[data-column=author]')!.textContent).toContain('Автор удалил');
        });

        it('rereads the plan when the plan changed under an open row', async () => {
            const units = [material(4, 'CONFLICT')];
            await open(readyPage(units));
            api.unit.mockReturnValueOnce(throwError(() => problem(409, 'UPDATE_PLAN_STALE')));
            api.plan.mockReturnValueOnce(of(readyPage(units, { planVersion: `sha256:${'cd'.repeat(32)}` })));
            rowOf('Материал 4').querySelector<HTMLButtonElement>('button[aria-expanded]')!.click();
            await settle();
            expect(api.plan).toHaveBeenCalledTimes(2);
            // The open comparison is gone with the old list, and the notice (also read out) says so.
            expect(text('.notice.compact')).toBe('Пока вы смотрели, колода изменилась. Мы обновили список, открытые сравнения свёрнуты.');
            expect(live()).toBe('Пока вы смотрели, колода изменилась. Мы обновили список, открытые сравнения свёрнуты.');
            expect(rowOf('Материал 4').querySelector('app-compare-columns')).toBeNull();
        });
    });

    describe('decisions', () => {
        it('starts a conflict and a unit changed again on «Оставить моё», a deletion on «Оставить у себя», and a new unit on nothing', async () => {
            await open(readyPage(mixed()));
            expect(radios(rowOf('Материал 1')).map(radio => radio.checked)).toEqual([false, false]);
            tab('Конфликты').click();
            await settle();
            expect(radios(rowOf('Материал 4')).map(radio => radio.checked)).toEqual([true, false]);
            // A unit changed again after a decision is answered like a conflict: keep mine or take the author's.
            expect(radios(rowOf('Материал 6')).map(radio => radio.labels![0]!.textContent!.trim())).toEqual(['Оставить моё', 'Взять вариант автора']);
            expect(radios(rowOf('Материал 6')).map(radio => radio.checked)).toEqual([true, false]);
            tab('Удалено автором').click();
            await settle();
            expect(radios(rowOf('Материал 5')).map(radio => radio.checked)).toEqual([true, false]);
        });

        it('shows what a unit needs as accepted and locked, with the reason, and refuses to skip it', async () => {
            await open(readyPage(mixed()));
            radioLabel(rowOf('Упражнение 2'), 'Принять').click();
            await settle();
            const needed = rowOf('Материал 1');
            expect(radioLabel(needed, 'Принять').checked).toBe(true);
            expect(needed.querySelector('fieldset')!.getAttribute('aria-busy')).toBe('true');
            // The reason sits directly under the decision, behind a visible stamp and a lock glyph, and describes the group.
            const reason = needed.querySelector<HTMLElement>('.decision .lock-reason')!;
            expect(reason.textContent!.replace(/\s+/gu, ' ').trim()).toBe('Обязательно Нужно для «Упражнение 2», которое вы принимаете, поэтому берём вместе с ним.');
            expect(reason.querySelector('.stamp app-glyph')).not.toBeNull();
            expect(reason.previousElementSibling!.tagName).toBe('APP-SEGMENTED-CHOICE');
            expect(needed.querySelector('fieldset')!.getAttribute('aria-describedby')!.split(' ')).toContain(reason.id);
            expect(rowOf('Упражнение 2').querySelector('.lock-reason')).toBeNull();
            radioLabel(needed, 'Не брать').click();
            await settle();
            expect(radioLabel(needed, 'Принять').checked).toBe(true);
            expect(radioLabel(needed, 'Не брать').checked).toBe(false);
            // The lock goes with the unit that caused it.
            radioLabel(rowOf('Упражнение 2'), 'Не брать').click();
            await settle();
            expect(needed.querySelector('fieldset')!.getAttribute('aria-busy')).toBeNull();
            expect(root().querySelector('.lock-reason')).toBeNull();
        });

        it('does not take a decision back that is already locked, and ignores a pick on a locked unit', async () => {
            await open(readyPage(mixed()));
            radioLabel(rowOf('Упражнение 2'), 'Принять').click();
            await settle();
            const picks = fixture.componentInstance.picks();
            fixture.componentInstance['pick'](fixture.componentInstance.units()[0]!, 'skip');
            expect(fixture.componentInstance.picks()).toBe(picks);
        });

        it('refuses «Принять всё безопасное» while a skipped unit is needed by one it would take, and says which', async () => {
            await open(readyPage(mixed()));
            radioLabel(rowOf('Материал 1'), 'Не брать').click();
            await settle();
            root().querySelector<HTMLButtonElement>('app-update-summary button.primary')!.click();
            await settle();
            expect(api.apply).not.toHaveBeenCalled();
            expect(text('.notice.error[role=alert]')).toBe('«Материал 1» нужно для «Упражнение 2», которое вы принимаете. Верните «Принять» для «Материал 1» или не берите «Упражнение 2».');
            radioLabel(rowOf('Упражнение 2'), 'Не брать').click();
            await settle();
            expect(root().querySelector('.notice.error[role=alert]')).toBeNull();
        });
    });

    describe('apply', () => {
        const applied = (extra: Partial<Extract<ApplyAnswer, { status: 'applied' }>> = {}): ApplyAnswer => ({ status: 'applied', applied: 3, declined: 1, pending: 2, alsoAccepted: 1, deckRevisionId: null, ...extra });
        const pressSafe = async () => { root().querySelector<HTMLButtonElement>('app-update-summary button.primary')!.click(); await settle(); };
        const pressSelected = async () => { button('Применить выбранное').click(); await settle(); };

        it('sends acceptSafe with a fresh command id and only the declines of new and corrected units', async () => {
            await open(readyPage(mixed()));
            api.apply.mockReturnValue(of(applied()));
            api.plan.mockReturnValue(of(readyPage([])));
            tab('Конфликты').click();
            await settle();
            radioLabel(rowOf('Материал 4'), 'Взять вариант автора').click();
            radioLabel(rowOf('Материал 6'), 'Взять вариант автора').click();
            tab('Удалено автором').click();
            await settle();
            radioLabel(rowOf('Материал 5'), 'Удалить и у меня').click();
            tab('Исправления').click();
            await settle();
            radioLabel(rowOf('Материал 3'), 'Не брать').click();
            await settle();
            // A line next to the primary button says which button applies the picks on conflicts and deletions.
            expect(text('app-update-summary .action')).toContain('Ваш выбор у конфликтов и удалений применит кнопка «Применить выбранное».');
            await pressSafe();
            const command = lastApply();
            expect(isCanonicalCommandId(command.commandId)).toBe(true);
            expect(command.planVersion).toBe(PLAN_VERSION);
            expect(command.acceptSafe).toBe(true);
            // Only the decline of the corrected unit goes; the picks on the conflict, the unit changed again and the deletion do not.
            expect(command.choices).toEqual([{ unitId: `MATERIAL:${keyOf(3)}`, choice: 'DECLINE' }]);
            expect(api.apply).toHaveBeenCalledWith(DECK_ID, command);
        });

        it('sends an empty list when nothing was picked', async () => {
            await open(readyPage(mixed()));
            api.apply.mockReturnValue(of(applied()));
            api.plan.mockReturnValue(of(readyPage([])));
            await pressSafe();
            expect(lastApply().acceptSafe).toBe(true);
            expect(lastApply().choices).toEqual([]);
        });

        it('sends «Применить выбранное» as acceptSafe false with every pick, defaults included, and is off with nothing picked', async () => {
            await open(readyPage(mixed()));
            const selected = button('Применить выбранное');
            expect(selected.getAttribute('aria-disabled')).toBe('true');
            expect(text('#update-selected-note')).toBe('Отметьте, что взять, и кнопка станет доступна.');
            selected.click();
            await settle();
            expect(api.apply).not.toHaveBeenCalled();
            api.apply.mockReturnValue(of(applied()));
            api.plan.mockReturnValue(of(readyPage([])));
            radioLabel(rowOf('Материал 1'), 'Принять').click();
            tab('Конфликты').click();
            await settle();
            radioLabel(rowOf('Материал 4'), 'Взять вариант автора').click();
            radioLabel(rowOf('Материал 4'), 'Оставить моё').click();
            await settle();
            expect(button('Применить выбранное').getAttribute('aria-disabled')).toBeNull();
            expect(text('#update-selected-note')).toBe('Отмечено: 2. Всё, чего вы не отметили, останется на потом.');
            await pressSelected();
            expect(lastApply().acceptSafe).toBe(false);
            expect(lastApply().choices).toEqual([{ unitId: `MATERIAL:${keyOf(1)}`, choice: 'ACCEPT' }, { unitId: `MATERIAL:${keyOf(4)}`, choice: 'KEEP_MINE' }]);
        });

        it('leaves out the pick on a locked unit: the server takes it with the unit that needs it', async () => {
            await open(readyPage(mixed()));
            api.apply.mockReturnValue(of(applied()));
            api.plan.mockReturnValue(of(readyPage([])));
            radioLabel(rowOf('Материал 1'), 'Принять').click();
            radioLabel(rowOf('Упражнение 2'), 'Принять').click();
            await settle();
            await pressSelected();
            expect(lastApply().choices).toEqual([{ unitId: `EXERCISE:${keyOf(2)}`, choice: 'ACCEPT' }]);
        });

        it('on 200 says what happened in plain words, rereads the plan, clears the picks and moves the focus to the summary', async () => {
            await open(readyPage(mixed()));
            api.apply.mockReturnValue(of(applied()));
            api.plan.mockReturnValueOnce(of(readyPage([material(7, 'NEW')])));
            radioLabel(rowOf('Материал 1'), 'Принять').click();
            await pressSafe();
            expect(toasts.echo).toHaveBeenCalledWith('Обновление применено: принято 3, вместе с ними ещё 1, не взято 1, осталось на потом 2.');
            expect(api.plan).toHaveBeenCalledTimes(2);
            expect(api.plan).toHaveBeenLastCalledWith(DECK_ID);
            expect(rowTitles()).toEqual(['Материал 7']);
            expect(fixture.componentInstance.picks().size).toBe(0);
            expect(document.activeElement).toBe(root().querySelector('app-update-summary h2'));
        });

        it('on 200 leaves out the parts that are zero, and shows the up-to-date state when nothing is left', async () => {
            await open(readyPage(mixed()));
            api.apply.mockReturnValue(of(applied({ applied: 6, declined: 0, pending: 0, alsoAccepted: 0 })));
            api.plan.mockReturnValueOnce(of(readyPage([], { state: 'UP_TO_DATE' })));
            await pressSafe();
            expect(toasts.echo).toHaveBeenCalledWith('Обновление применено: принято 6.');
            expect(text('h2')).toBe('У вас всё как у автора');
            expect(document.activeElement).toBe(root().querySelector('[data-focus-target]'));
        });

        it('keeps the focus on the pressed button while the request runs, and announces it once', async () => {
            await open(readyPage(mixed()));
            const answer = new Subject<ApplyAnswer>();
            api.apply.mockReturnValue(answer);
            await pressSafe();
            const primary = root().querySelector<HTMLButtonElement>('app-update-summary button.primary')!;
            expect(primary.getAttribute('aria-busy')).toBe('true');
            expect(primary.textContent!.trim()).toBe('Принимаем…');
            expect(live()).toBe('Применяем обновление…');
            primary.click();
            expect(api.apply).toHaveBeenCalledTimes(1);
            answer.error(problem(400));
            await settle();
            expect(live()).toBe('');
        });

        it('on 202 shows «Применяем обновление…» and polls the plan with a bounded backoff until it is no longer applying', async () => {
            vi.useFakeTimers();
            try {
                await open(readyPage(mixed()));
                api.apply.mockReturnValue(of({ status: 'applying' } as const));
                await pressSafe();
                expect(text('h2')).toBe('Применяем обновление…');
                expect(live()).toBe('Применяем обновление…');
                expect(root().querySelector('app-update-summary')).toBeNull();
                api.plan.mockReturnValueOnce(of(closedPage('APPLYING'))).mockReturnValueOnce(of(closedPage('APPLYING')));
                await vi.advanceTimersByTimeAsync(1500);
                expect(api.plan).toHaveBeenLastCalledWith(DECK_ID, null, 1);
                await vi.advanceTimersByTimeAsync(1999);
                expect(api.plan).toHaveBeenCalledTimes(2);
                await vi.advanceTimersByTimeAsync(1);
                expect(api.plan).toHaveBeenCalledTimes(3);
                api.plan.mockReturnValueOnce(of(readyPage([], { state: 'READY' }))).mockReturnValueOnce(of(readyPage([material(7, 'NEW')])));
                await vi.advanceTimersByTimeAsync(3000);
                await settle();
                expect(api.plan).toHaveBeenLastCalledWith(DECK_ID);
                expect(toasts.echo).toHaveBeenCalledWith('Обновление применено.');
                expect(rowTitles()).toEqual(['Материал 7']);
                await vi.advanceTimersByTimeAsync(60_000);
                expect(api.plan).toHaveBeenCalledTimes(5);
            } finally {
                vi.useRealTimers();
            }
        });

        it('stops asking after a bounded number of checks and offers «Проверить снова»', async () => {
            vi.useFakeTimers();
            try {
                await open(closedPage('APPLYING'));
                api.plan.mockReturnValue(of(closedPage('APPLYING')));
                await vi.advanceTimersByTimeAsync(10_000 * (FORK_POLL_LIMIT + 5));
                fixture.detectChanges();
                expect(api.plan).toHaveBeenCalledTimes(1 + FORK_POLL_LIMIT);
                expect(text('section.notice p')).toBe('Применение идёт дольше обычного. Страница больше не проверяет сама.');
                api.plan.mockReturnValue(of(readyPage([material(7, 'NEW')])));
                button('Проверить снова').click();
                await settle();
                await vi.advanceTimersByTimeAsync(0);
                fixture.detectChanges();
                expect(rowTitles()).toEqual(['Материал 7']);
            } finally {
                vi.useRealTimers();
            }
        });

        it('shows the failure of a background apply when the plan says so', async () => {
            vi.useFakeTimers();
            try {
                await open(closedPage('APPLYING'));
                api.plan.mockReturnValueOnce(of(closedPage('READY')));
                api.plan.mockReturnValueOnce(of(readyPage(mixed(), { lastApplyFailed: 'UPDATE_APPLY_FAILED' })));
                await vi.advanceTimersByTimeAsync(1500);
                await settle();
                expect(text('.notice.error')).toBe('Не удалось применить большое обновление. Ничего не потеряно: выберите и примените снова.');
                expect(toasts.echo).not.toHaveBeenCalled();
            } finally {
                vi.useRealTimers();
            }
        });

        it('on 409 UPDATE_PLAN_STALE says the deck changed, rereads the plan and keeps nothing pending', async () => {
            await open(readyPage(mixed()));
            api.apply.mockReturnValue(throwError(() => problem(409, 'UPDATE_PLAN_STALE')));
            api.plan.mockReturnValueOnce(of(readyPage([material(7, 'NEW')], { planVersion: `sha256:${'cd'.repeat(32)}` })));
            await pressSafe();
            expect(text('.notice.compact')).toBe('Пока вы смотрели, колода изменилась. Мы обновили список.');
            expect(rowTitles()).toEqual(['Материал 7']);
            api.apply.mockReturnValue(of(applied()));
            api.plan.mockReturnValue(of(readyPage([])));
            await pressSafe();
            expect(api.apply).toHaveBeenCalledTimes(2);
            expect(api.apply.mock.calls[1]![1].commandId).not.toBe(api.apply.mock.calls[0]![1].commandId);
            expect(api.apply.mock.calls[1]![1].planVersion).toBe(`sha256:${'cd'.repeat(32)}`);
        });

        it.each([['DECK_PREPARING', 'Готовим колоду…'], ['FORK_DETACHED', 'Колода отвязана от автора, обновлений нет']])(
            'on 409 %s rereads the plan and shows that state', async (code, title) => {
                await open(readyPage(mixed()));
                api.apply.mockReturnValue(throwError(() => problem(409, code)));
                api.plan.mockReturnValueOnce(of(closedPage(code === 'DECK_PREPARING' ? 'PREPARING' : 'DETACHED')));
                await pressSafe();
                expect(text('h2')).toBe(title);
            });

        it('on 400 says the choice cannot be applied and offers no retry', async () => {
            await open(readyPage(mixed()));
            api.apply.mockReturnValue(throwError(() => problem(400, 'INVALID_REQUEST')));
            await pressSafe();
            expect(text('.notice.error[role=alert] p')).toBe('Этот выбор нельзя применить. Проверьте отмеченное и повторите.');
            expect(root().querySelector('.notice.error[role=alert] button')).toBeNull();
        });

        it('retries an unknown outcome with the very same command, and a second press of the same button too', async () => {
            await open(readyPage(mixed()));
            api.apply.mockReturnValueOnce(throwError(() => problem(0))).mockReturnValueOnce(throwError(() => problem(503)));
            await pressSafe();
            expect(text('.notice.error[role=alert] p')).toBe('Не удалось узнать, применилось ли обновление. Повторите: ничего не будет применено дважды.');
            const first = lastApply();
            button('Повторить').click();
            await settle();
            expect(api.apply).toHaveBeenCalledTimes(2);
            expect(api.apply.mock.calls[1]![1]).toEqual(first);
            api.apply.mockReturnValueOnce(throwError(() => problem(0)));
            await pressSafe();
            expect(api.apply.mock.calls[2]![1].commandId).toBe(first.commandId);
            api.apply.mockReturnValue(of(applied()));
            api.plan.mockReturnValue(of(readyPage([])));
            button('Повторить').click();
            await settle();
            expect(api.apply.mock.calls[3]![1].commandId).toBe(first.commandId);
            expect(toasts.echo).toHaveBeenCalledTimes(1);
        });

        it('sends a new command id when the choices changed after an unknown outcome', async () => {
            await open(readyPage(mixed()));
            api.apply.mockReturnValueOnce(throwError(() => problem(0))).mockReturnValue(of(applied()));
            api.plan.mockReturnValue(of(readyPage(mixed())));
            await pressSafe();
            const first = lastApply();
            radioLabel(rowOf('Материал 1'), 'Не брать').click();
            radioLabel(rowOf('Упражнение 2'), 'Не брать').click();
            await pressSafe();
            expect(lastApply().commandId).not.toBe(first.commandId);
            expect(lastApply().choices).toHaveLength(2);
        });

        it('says a rate limit in words and keeps the command for «Повторить»', async () => {
            await open(readyPage(mixed()));
            api.apply.mockReturnValueOnce(throwError(() => problem(429)));
            await pressSafe();
            expect(text('.notice.error[role=alert] p')).toBe('Слишком много запросов. Подождите немного и повторите.');
            expect(button('Повторить')).toBeDefined();
        });

        it('applies nothing when the summary has nothing safe: the button does not send', async () => {
            await open(readyPage([material(4, 'CONFLICT')]));
            expect(text('app-update-summary .hint')).toBe('Безопасного для приёма сейчас нет.');
            root().querySelector<HTMLButtonElement>('app-update-summary button.primary')!.click();
            await settle();
            expect(api.apply).not.toHaveBeenCalled();
        });
    });

    describe('the final apply contract', () => {
        const applied = (): ApplyAnswer => ({ status: 'applied', applied: 3, declined: 1, pending: 2, alsoAccepted: 1, deckRevisionId: null });
        const pressSafe = async () => { root().querySelector<HTMLButtonElement>('app-update-summary button.primary')!.click(); await settle(); };
        const alertText = () => text('[role=alert]');

        it('sends a unit changed again as KEEP_MINE or ACCEPT, never as DECLINE', async () => {
            await open(readyPage(mixed()));
            api.apply.mockReturnValue(of(applied()));
            api.plan.mockReturnValue(of(readyPage([])));
            tab('Конфликты').click();
            await settle();
            radioLabel(rowOf('Материал 6'), 'Взять вариант автора').click();
            radioLabel(rowOf('Материал 6'), 'Оставить моё').click();
            await settle();
            button('Применить выбранное').click();
            await settle();
            expect(lastApply()).toMatchObject({ acceptSafe: false, choices: [{ unitId: `MATERIAL:${keyOf(6)}`, choice: 'KEEP_MINE' }] });
        });

        it('leaves the default of a unit changed again to acceptSafe, which never takes it', async () => {
            await open(readyPage(mixed()));
            api.apply.mockReturnValue(of(applied()));
            api.plan.mockReturnValue(of(readyPage([])));
            tab('Конфликты').click();
            await settle();
            radioLabel(rowOf('Материал 6'), 'Взять вариант автора').click();
            radioLabel(rowOf('Материал 6'), 'Оставить моё').click();
            await settle();
            await pressSafe();
            expect(lastApply()).toMatchObject({ acceptSafe: true, choices: [] });
        });

        it('says the failure of a background apply on an up-to-date plan too', async () => {
            await open(readyPage([], { state: 'UP_TO_DATE', lastApplyFailed: 'stale_head' }));
            expect(text('h2')).toBe('У вас всё как у автора');
            expect(text('section.notice .notice.error')).toBe('Не удалось применить большое обновление. Ничего не потеряно: выберите и примените снова.');
        });

        it('shows the status of a plan that is applying under a command, and waits for the job', async () => {
            vi.useFakeTimers();
            try {
                const running = closedPage('APPLYING');
                expect(running.commandId).toBe(APPLYING_COMMAND);
                await open(running);
                expect(text('h2')).toBe('Применяем обновление…');
                expect(live()).toBe('Применяем обновление…');
                expect(root().querySelector('app-update-summary')).toBeNull();
                api.plan.mockReturnValueOnce(of(readyPage([], { state: 'READY' }))).mockReturnValueOnce(of(readyPage(mixed())));
                await vi.advanceTimersByTimeAsync(1500);
                await settle();
                expect(root().querySelector('app-update-summary')).not.toBeNull();
            } finally {
                vi.useRealTimers();
            }
        });

        describe('a 400 that names its reason', () => {
            const exerciseId = `EXERCISE:${keyOf(2)}`;
            const materialId = `MATERIAL:${keyOf(1)}`;

            it('unknown_unit: the plan moved on, so the list is read again with the picks that still fit', async () => {
                await open(readyPage(mixed()));
                radioLabel(rowOf('Материал 1'), 'Не брать').click();
                radioLabel(rowOf('Упражнение 2'), 'Не брать').click();
                api.apply.mockReturnValue(throwError(() => refusal('unknown_unit', materialId)));
                api.plan.mockReturnValueOnce(of(readyPage([material(1, 'NEW'), material(7, 'NEW')], { planVersion: `sha256:${'cd'.repeat(32)}` })));
                await pressSafe();
                expect(api.plan).toHaveBeenCalledTimes(2);
                expect(text('.notice.compact')).toBe('Пока вы смотрели, колода изменилась. Мы обновили список.');
                expect(radioLabel(rowOf('Материал 1'), 'Не брать').checked).toBe(true);
            });

            it('choice_not_allowed: names the unit by its title and offers no retry', async () => {
                await open(readyPage(mixed()));
                api.apply.mockReturnValue(throwError(() => refusal('choice_not_allowed', materialId)));
                await pressSafe();
                expect(alertText()).toBe('Для «Материал 1» этот выбор недоступен. Выберите другое и повторите.');
                expect(root().querySelector('.notice.error[role=alert] button')).toBeNull();
            });

            it('exercise_needs_material: says the copy deleted the material the exercise belongs to', async () => {
                await open(readyPage(mixed()));
                api.apply.mockReturnValue(throwError(() => refusal('exercise_needs_material', exerciseId)));
                await pressSafe();
                expect(alertText()).toBe('Упражнение «Упражнение 2» нельзя взять: вы удалили материал, к которому оно относится.');
            });

            it('dependency_declined: names the unit and the dependency it needs', async () => {
                await open(readyPage(mixed()));
                api.apply.mockReturnValue(throwError(() => refusal('dependency_declined', exerciseId, materialId)));
                await pressSafe();
                expect(alertText()).toBe('«Материал 1» нужно для «Упражнение 2», которое вы принимаете. Верните «Принять» для «Материал 1» или не берите «Упражнение 2».');
            });

            it('material_still_used: names the material and the exercise the plan lists, or says exercises in general', async () => {
                await open(readyPage(mixed()));
                api.apply.mockReturnValueOnce(throwError(() => refusal('material_still_used', materialId, exerciseId)));
                await pressSafe();
                expect(alertText()).toBe('Материал «Материал 1» нельзя удалить: к нему ещё привязано упражнение «Упражнение 2». Удалите и его или оставьте материал у себя.');
                api.apply.mockReturnValueOnce(throwError(() => refusal('material_still_used', materialId)));
                await pressSafe();
                expect(alertText()).toBe('Материал «Материал 1» нельзя удалить: к нему ещё привязаны упражнения. Удалите и их или оставьте материал у себя.');
                api.apply.mockReturnValueOnce(throwError(() => refusal('material_still_used', `MATERIAL:${keyOf(99)}`)));
                await pressSafe();
                expect(alertText()).toBe('Этот материал нельзя удалить: к нему ещё привязаны упражнения. Удалите и их или оставьте материал у себя.');
            });

            it('publication_refused: says nothing was written', async () => {
                await open(readyPage(mixed()));
                api.apply.mockReturnValue(throwError(() => refusal('publication_refused', materialId)));
                await pressSafe();
                expect(alertText()).toBe('Не удалось применить обновление: результат не принят. Ничего не изменилось.');
            });

            it('names no unit it has not loaded, and says so in general words', async () => {
                await open(readyPage(mixed()));
                const unknown = `EXERCISE:${keyOf(99)}`;
                api.apply.mockReturnValueOnce(throwError(() => refusal('choice_not_allowed', unknown)));
                await pressSafe();
                expect(alertText()).toBe('Для одного из изменений этот выбор недоступен. Выберите другое и повторите.');
                api.apply.mockReturnValueOnce(throwError(() => refusal('exercise_needs_material', unknown)));
                await pressSafe();
                expect(alertText()).toBe('Это упражнение нельзя взять: вы удалили материал, к которому оно относится.');
                api.apply.mockReturnValueOnce(throwError(() => refusal('dependency_declined', unknown, materialId)));
                await pressSafe();
                expect(alertText()).toBe('Одно из изменений нужно другому, которое вы принимаете. Верните «Принять» для первого или не берите второе.');
            });

            it('duplicate_unit and a reason the client does not know: the general sentence', async () => {
                await open(readyPage(mixed()));
                api.apply.mockReturnValueOnce(throwError(() => refusal('duplicate_unit', materialId)));
                await pressSafe();
                expect(alertText()).toBe('Этот выбор нельзя применить. Проверьте отмеченное и повторите.');
                api.apply.mockReturnValueOnce(throwError(() => refusal('something_new')));
                await pressSafe();
                expect(alertText()).toBe('Этот выбор нельзя применить. Проверьте отмеченное и повторите.');
            });
        });

        it('on 422 RESOURCE_LIMIT_EXCEEDED asks to apply in parts', async () => {
            await open(readyPage(mixed()));
            api.apply.mockReturnValue(throwError(() => problem(422, 'RESOURCE_LIMIT_EXCEEDED', { limit: 10000 })));
            await pressSafe();
            expect(alertText()).toBe('Выбрано слишком много изменений сразу. Примените часть из них, потом остальное.');
            expect(root().querySelector('.notice.error[role=alert] button')).toBeNull();
        });

        it('on a busy server says so and keeps the command for «Повторить»', async () => {
            await open(readyPage(mixed()));
            api.apply.mockReturnValueOnce(throwError(() => busy()));
            await pressSafe();
            expect(alertText()).toContain('Сервер сейчас занят. Попробуйте через минуту.');
            const first = lastApply();
            api.apply.mockReturnValue(of(applied()));
            api.plan.mockReturnValue(of(readyPage([])));
            button('Повторить').click();
            await settle();
            expect(lastApply()).toEqual(first);
        });

        it('on 409 UPDATE_SOURCE_UNAVAILABLE rereads the plan and shows the closed source', async () => {
            await open(readyPage(mixed()));
            api.apply.mockReturnValue(throwError(() => problem(409, 'UPDATE_SOURCE_UNAVAILABLE')));
            api.plan.mockReturnValueOnce(of(closedPage('SOURCE_UNAVAILABLE')));
            await pressSafe();
            expect(text('app-unavailable-note .title')).toBe('Источник больше недоступен');
        });
    });

    describe('a busy server', () => {
        it('says «Попробуйте через минуту» when the first page could not be read, and reads again on «Повторить»', async () => {
            await open(throwError(() => busy()));
            expect(text('.notice.error h2')).toBe('Не удалось открыть обновление');
            expect(text('.notice.error p')).toBe('Сервер сейчас занят. Попробуйте через минуту.');
            api.plan.mockReturnValueOnce(of(readyPage(mixed())));
            button('Повторить').click();
            await settle();
            expect(root().querySelector('.notice.error')).toBeNull();
            expect(rows().length).toBeGreaterThan(0);
        });

        it('says it for a later page and for the sides of a row, each with its own «Повторить»', async () => {
            const summary = { ...readyPage([]).summary!, newMaterials: 60 };
            const units = Array.from({ length: 50 }, (_, index) => material(index + 1, 'NEW'));
            await open(readyPage(units, { nextCursor: 'cursor-1', summary }));
            api.plan.mockReturnValueOnce(throwError(() => busy()));
            fixture.debugElement.query(By.directive(AutoLoadComponent)).componentInstance.loadNext.emit();
            await settle();
            expect(text('app-auto-load .notice.error p')).toBe('Сервер сейчас занят. Попробуйте через минуту.');

            api.unit.mockReturnValueOnce(throwError(() => busy()));
            rowOf('Материал 1').querySelector<HTMLButtonElement>('button[aria-expanded]')!.click();
            await settle();
            expect(text('app-update-unit-row .body .notice.error p')).toBe('Сервер сейчас занят. Попробуйте через минуту.');
            api.unit.mockReturnValueOnce(of(materialSides(units[0]!, null, DOC_AUTHOR)));
            rowOf('Материал 1').querySelector<HTMLButtonElement>('.body .notice.error button')!.click();
            await settle();
            expect(rowOf('Материал 1').querySelector('app-compare-columns')).not.toBeNull();
        });
    });

    describe('media of a compared material', () => {
        it('reads the reader\'s files by the owner\'s route and the author\'s by the public route of the source code of the copy', async () => {
            const http = TestBed.inject(HttpTestingController);
            const units = [material(4, 'CONFLICT')];
            const image = (assetId: string, alt: string) => documentOf([nativeNode('image', { assetId, alt })]);
            const mine = '31901995-16ea-4f8b-8301-5d8e03004c72';
            const author = '41901995-16ea-4f8b-8301-5d8e03004c72';
            await open(readyPage(units));
            api.unit.mockReturnValue(of(materialSides(units[0]!, image(mine, 'Моя схема'), image(author, 'Схема автора'))));
            rowOf('Материал 4').querySelector<HTMLButtonElement>('button[aria-expanded]')!.click();
            await settle();
            http.expectOne(`/api/media-assets/${mine}/playback`).flush({ assetId: mine, state: 'READY', playback: { url: 'https://storage.example/mine.webp', mimeType: 'image/webp', expiresAt: '2099-01-01T00:00:00Z' }, poster: null, download: null });
            http.expectOne(`/api/public/decks/Kq7xT3mNpR/media/${author}`).flush({ assetId: author, state: 'READY', playback: { url: 'https://storage.example/author.webp', mimeType: 'image/webp', expiresAt: '2099-01-01T00:00:00Z' }, poster: null });
            await new Promise<void>(resolve => setTimeout(resolve, 0));
            await settle();
            expect(rowOf('Материал 4').querySelector('[data-column=mine] img')!.getAttribute('src')).toBe('https://storage.example/mine.webp');
            expect(rowOf('Материал 4').querySelector('[data-column=author] img')!.getAttribute('src')).toBe('https://storage.example/author.webp');
            http.verify();
        });
    });

    describe('the accessibility round', () => {
        const applied = (): ApplyAnswer => ({ status: 'applied', applied: 3, declined: 1, pending: 2, alsoAccepted: 1, deckRevisionId: null });
        const pressSafe = async () => { root().querySelector<HTMLButtonElement>('app-update-summary button.primary')!.click(); await settle(); };

        it('does not let the stored «Не брать» of a locked unit block the apply, and does not send it', async () => {
            await open(readyPage(mixed()));
            api.apply.mockReturnValue(of(applied()));
            api.plan.mockReturnValue(of(readyPage([])));
            radioLabel(rowOf('Материал 1'), 'Не брать').click();
            radioLabel(rowOf('Упражнение 2'), 'Принять').click();
            await settle();
            // The material is now locked to «Принять»; its old pick must neither block nor travel.
            expect(radioLabel(rowOf('Материал 1'), 'Принять').checked).toBe(true);
            await pressSafe();
            expect(api.apply).toHaveBeenCalledTimes(1);
            expect(root().querySelector('.notice.error[role=alert]')).toBeNull();
            // The exercise stands on the default of its class (left to acceptSafe) and the locked material's old pick does not travel.
            expect(lastApply().choices).toEqual([]);
        });

        it('does not send the stored pick of a locked unit with «Применить выбранное» either, and takes the unit that locks it', async () => {
            await open(readyPage(mixed()));
            api.apply.mockReturnValue(of(applied()));
            api.plan.mockReturnValue(of(readyPage([])));
            radioLabel(rowOf('Материал 1'), 'Не брать').click();
            radioLabel(rowOf('Упражнение 2'), 'Принять').click();
            await settle();
            button('Применить выбранное').click();
            await settle();
            expect(lastApply()).toMatchObject({ acceptSafe: false, choices: [{ unitId: `EXERCISE:${keyOf(2)}`, choice: 'ACCEPT' }] });
        });

        it('shows the line about the picks on conflicts only while there are some, and ties it to the primary button', async () => {
            await open(readyPage(mixed()));
            const line = () => root().querySelector('app-update-summary .action .hint:nth-of-type(2)');
            expect(line()).toBeNull();
            tab('Конфликты').click();
            await settle();
            radioLabel(rowOf('Материал 4'), 'Взять вариант автора').click();
            await settle();
            expect(line()!.textContent).toBe('Ваш выбор у конфликтов и удалений применит кнопка «Применить выбранное».');
            const primary = root().querySelector('app-update-summary button.primary')!;
            expect(primary.getAttribute('aria-describedby')!.split(' ')).toHaveLength(2);
            expect(root().querySelector(`#${primary.getAttribute('aria-describedby')!.split(' ')[1]}`)).toBe(line());
            radioLabel(rowOf('Материал 4'), 'Оставить моё').click();
            await settle();
            // The pick is still a pick (the other button sends it, defaults included).
            expect(line()).not.toBeNull();
        });

        it('keeps sending every pick, conflicts and deletions included, with «Применить выбранное»', async () => {
            await open(readyPage(mixed()));
            api.apply.mockReturnValue(of(applied()));
            api.plan.mockReturnValue(of(readyPage([])));
            tab('Конфликты').click();
            await settle();
            radioLabel(rowOf('Материал 4'), 'Взять вариант автора').click();
            tab('Удалено автором').click();
            await settle();
            radioLabel(rowOf('Материал 5'), 'Удалить и у меня').click();
            await settle();
            button('Применить выбранное').click();
            await settle();
            expect(lastApply()).toMatchObject({ acceptSafe: false, choices: [{ unitId: `MATERIAL:${keyOf(4)}`, choice: 'ACCEPT' }, { unitId: `MATERIAL:${keyOf(5)}`, choice: 'DELETE' }] });
        });

        it('explains «Конфликт» and «Снова изменено» under the tabs of the conflicts, and only there', async () => {
            await open(readyPage(mixed()));
            expect(root().querySelector('.tab-note')).toBeNull();
            tab('Конфликты').click();
            await settle();
            expect([...root().querySelectorAll('.tab-note .hint')].map(hint => hint.textContent)).toEqual([
                'Конфликт: вы и автор изменили одно и то же, по умолчанию остаётся ваш вариант.',
                'Снова изменено: автор изменил то, что вы уже решили, поэтому мы спрашиваем ещё раз.'
            ]);
        });

        it('makes an empty tab panel focusable and leaves a panel with rows to the controls inside it', async () => {
            await open(readyPage([material(1, 'NEW')]));
            const panel = () => root().querySelector('[role=tabpanel]')!;
            expect(panel().hasAttribute('tabindex')).toBe(false);
            tab('Конфликты').click();
            await settle();
            expect(panel().getAttribute('tabindex')).toBe('0');
        });

        it('keeps the counts of an apply on the page after the toast, under the summary heading, until the next apply', async () => {
            await open(readyPage(mixed()));
            api.apply.mockReturnValueOnce(of(applied()));
            api.plan.mockReturnValueOnce(of(readyPage([material(7, 'NEW'), material(8, 'NEW')])));
            await pressSafe();
            expect(text('.applied-line')).toBe('Обновление применено: принято 3, вместе с ними ещё 1, не взято 1, осталось на потом 2.');
            expect(root().querySelector('.update-page > .applied-line')!.nextElementSibling!.tagName).toBe('APP-UPDATE-SUMMARY');
            const first = new Subject<ApplyAnswer>();
            api.apply.mockReturnValueOnce(first);
            await pressSafe();
            expect(root().querySelector('.applied-line')).toBeNull();
        });

        it('shows the applied line under the heading of the up-to-date state too', async () => {
            await open(readyPage(mixed()));
            api.apply.mockReturnValue(of({ ...applied(), applied: 6, declined: 0, pending: 0, alsoAccepted: 0 }));
            api.plan.mockReturnValueOnce(of(readyPage([], { state: 'UP_TO_DATE' })));
            await pressSafe();
            expect(text('section.notice h2')).toBe('У вас всё как у автора');
            expect(text('section.notice .applied-line')).toBe('Обновление применено: принято 6.');
        });

        it('keeps «Обновление применено.» on the page after a background apply', async () => {
            vi.useFakeTimers();
            try {
                await open(closedPage('APPLYING'));
                api.plan.mockReturnValueOnce(of(readyPage([], { state: 'READY' }))).mockReturnValueOnce(of(readyPage([material(7, 'NEW')])));
                await vi.advanceTimersByTimeAsync(1500);
                await settle();
                expect(text('.applied-line')).toBe('Обновление применено.');
            } finally {
                vi.useRealTimers();
            }
        });

        it('says that the page stopped asking when it does, and moves the focus to the heading when «Проверить снова» is pressed', async () => {
            vi.useFakeTimers();
            try {
                await open(closedPage('APPLYING'));
                api.plan.mockReturnValue(of(closedPage('APPLYING')));
                await vi.advanceTimersByTimeAsync(10_000 * (FORK_POLL_LIMIT + 5));
                fixture.detectChanges();
                expect(live()).toBe('Применение идёт дольше обычного. Страница больше не проверяет сама.');
                document.body.append(root());
                button('Проверить снова').focus();
                button('Проверить снова').click();
                fixture.detectChanges();
                await vi.advanceTimersByTimeAsync(0);
                fixture.detectChanges();
                expect(live()).toBe('Применяем обновление…');
                expect(document.activeElement).toBe(root().querySelector('[data-focus-target]'));
                root().remove();
            } finally {
                vi.useRealTimers();
            }
        });

        it('has one name for a closed source: the note, with no second hidden heading, and the focus goes to its title', async () => {
            await open(closedPage('SOURCE_UNAVAILABLE'));
            expect(root().querySelectorAll('h2')).toHaveLength(0);
            expect(root().querySelector('.visually-hidden:not([role=status])')).toBeNull();
            expect(text('app-unavailable-note .title')).toBe('Источник больше недоступен');
            api.plan.mockReturnValueOnce(of(closedPage('SOURCE_UNAVAILABLE')));
            fixture.componentInstance.reload({ focus: true });
            await settle();
            await settle();
            expect(document.activeElement).toBe(root().querySelector('app-unavailable-note .title'));
        });
    });
});

describe('UPDATE_TEXT', () => {
    it('uses no long dash in any string it can produce', () => {
        const strings: string[] = [];
        const visit = (value: unknown): void => {
            if (typeof value === 'string') strings.push(value);
            else if (typeof value === 'function') strings.push(String((value as (...args: unknown[]) => unknown)('Название', 1, 2, 3, 4)));
            else if (value !== null && typeof value === 'object') Object.values(value).forEach(visit);
        };
        visit(UPDATE_TEXT);
        expect(strings.length).toBeGreaterThan(100);
        for (const line of strings) expect(line, line).not.toMatch(/[‒-―−]/u);
    });
});
