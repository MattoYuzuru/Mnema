import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';

import { ToastService } from '../../core/notifications/toast.service';
import { spyObj, type SpyObj } from '../../../testing/mocks';
import { OwnDecksApiService } from '../own-decks/own-decks-api.service';
import { GenerationApiService } from './generation-api.service';
import { CreatedSession, ExercisePlanItem, parseEventsPage, parseSessionDetail } from './generation.models';
import {
    activeStep, deckFixture, eventsEnvelope, examples, ids, materialsPlanSession, planApprovedSession, planReadySession, planTargets, problemResponse
} from './generation-test-data';
import { WorkshopPlanComponent } from './workshop-plan.component';
import { WorkshopSessionStore } from './workshop-session.store';

const NBSP = '\u00a0';

describe('WorkshopPlanComponent (AI-14, #295)', () => {
    let fixture: ComponentFixture<WorkshopPlanComponent>;
    let api: SpyObj<GenerationApiService>;
    let toast: { echo: ReturnType<typeof vi.fn> };
    let store: WorkshopSessionStore;

    const root = (): HTMLElement => fixture.nativeElement as HTMLElement;
    const text = (node: Element | null | undefined): string => (node?.textContent ?? '').replace(/\s+/g, ' ').replaceAll(NBSP, ' ').trim();
    const rows = (): HTMLElement[] => [...root().querySelectorAll<HTMLElement>('ol.rows > li')];
    const titles = (): string[] => rows().map(row => text(row.querySelector('.row-title, .title-input') as HTMLElement | null) || (row.querySelector<HTMLInputElement>('.title-input')?.value ?? ''));
    const total = (): string => text(root().querySelector('.total'));
    const button = (label: string, scope: ParentNode = root()): HTMLButtonElement | undefined =>
        [...scope.querySelectorAll<HTMLButtonElement>('button')].find(item => text(item) === label);
    const range = (row: number): HTMLInputElement => rows()[row]!.querySelector<HTMLInputElement>('input[type=range]')!;
    const chips = (row: number): Record<string, boolean> => Object.fromEntries([...rows()[row]!.querySelectorAll<HTMLInputElement>('.chip input')]
        .map(input => [text(input.closest('label')), input.checked]));

    async function settle(): Promise<void> {
        fixture.detectChanges();
        await vi.advanceTimersByTimeAsync(0);
        fixture.detectChanges();
    }

    async function open(session: Record<string, unknown> = planReadySession()): Promise<void> {
        TestBed.resetTestingModule();
        vi.useFakeTimers();
        vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
        api = spyObj<GenerationApiService>({ getSession: vi.fn(), listEvents: vi.fn(), getArtifact: vi.fn(), cancelSession: vi.fn(), approvePlan: vi.fn() });
        toast = { echo: vi.fn() };
        api.getSession.mockReturnValue(of(parseSessionDetail(session)));
        api.listEvents.mockReturnValue(of(parseEventsPage(eventsEnvelope([], '0', { state: 'PLAN_READY', rowVersion: '3' }))));
        TestBed.configureTestingModule({ providers: [WorkshopSessionStore, { provide: GenerationApiService, useValue: api }, { provide: ToastService, useValue: toast },
            { provide: OwnDecksApiService, useValue: { detail: () => of(deckFixture) } }] });
        store = TestBed.inject(WorkshopSessionStore);
        store.open(ids.deckId, ids.sessionId);
        fixture = TestBed.createComponent(WorkshopPlanComponent);
        await settle();
    }

    const launched = (session: Record<string, unknown> = planApprovedSession()): CreatedSession => ({ session: parseSessionDetail(session), replayed: false });
    const input = (node: HTMLInputElement, value: string): void => { node.value = value; node.dispatchEvent(new Event('input', { bubbles: true })); fixture.detectChanges(); };

    afterEach(() => vi.useRealTimers());

    describe('while Мнема makes the plan', () => {
        it('says so quietly and offers «Отменить», which stops the session', async () => {
            await open(planReadySession(session => { session.state = 'PLANNING'; session.plan = null; session.rowVersion = '1'; }));
            expect(text(root().querySelector('h2'))).toBe('Мнема составляет план…');
            expect(root().querySelector('[role=status], [aria-live]')).toBeNull();
            expect(root().querySelector('ol')).toBeNull();
            api.cancelSession.mockReturnValue(of(parseSessionDetail(planReadySession(session => { session.state = 'CANCELLED'; session.rowVersion = '2'; session.endReason = 'USER_CANCELLED'; }))));
            button('Отменить')!.click();
            await settle();
            expect(api.cancelSession).toHaveBeenCalledWith(ids.deckId, ids.sessionId, expect.any(String));
            expect(store.session()!.state).toBe('CANCELLED');
        });

        it('shows the same wait when the session is ready but its plan has not been read yet', async () => {
            await open(planReadySession(session => { session.plan = null; }));
            expect(text(root().querySelector('h2'))).toBe('Мнема составляет план…');
        });
    });

    describe('the plan of exercises', () => {
        it('lists every row with its material, the model\'s reason as secondary text, its mechanics and its count', async () => {
            await open();
            expect(text(root().querySelector('h2'))).toBe('План упражнений');
            expect(root().querySelector('ol.rows')?.getAttribute('aria-label')).toContain('План');
            expect(titles()).toEqual(['Статистика и ANALYZE', 'Seq Scan и Index Scan']);
            expect(text(rows()[0]!.querySelector('.why'))).toBe('Упражнений на этот материал пока нет.');
            expect(chips(0)).toEqual({ 'Вспомнить и сверить': false, 'Ввести ответ': false, 'Заполнить пропуски': true, 'Выбрать ответ': true,
                'Сопоставить элементы': false, 'Восстановить порядок': false, 'Распределить по группам': false });
            expect(range(0).min).toBe('1');
            expect(range(0).max).toBe('10');
            expect(range(0).value).toBe('3');
            expect(range(0).getAttribute('aria-valuetext')).toBe(`3${NBSP}упражнения`);
            expect(range(0).getAttribute('aria-label')).toBe('Количество упражнений: Статистика и ANALYZE');
            expect(rows()[0]!.querySelector('output')?.textContent).toBe(`3${NBSP}упражнения`);
            expect(root().querySelectorAll('.row-remove')).toHaveLength(2);
            expect(root().querySelector('.row-remove')?.getAttribute('aria-label')).toBe('Убрать из плана: Статистика и ANALYZE');
        });

        it('shows what the plan cost apart as paid, and the live total with the share of the limit', async () => {
            await open();
            expect(text(root().querySelector('.paid'))).toBe('Составление плана: ≈ 5,6 % лимита — уже списано. Отдельно от упражнений.');
            expect(total()).toBe('Всего 6 упражнений · ≈ 2,8 % лимита');
            expect(root().querySelector('.total')?.getAttribute('role')).toBe('status');
            expect(button('Запустить по плану')!.getAttribute('aria-describedby')).toBe('plan-total');
        });

        it('has no «Авто» and keeps at least one mechanic in a row: the last one stays, and the row says why', async () => {
            await open();
            expect(rows()[0]!.textContent).not.toContain('Авто');
            const choice = [...rows()[0]!.querySelectorAll<HTMLInputElement>('.chip input')].find(box => text(box.closest('label')) === 'Выбрать ответ')!;
            choice.click();
            fixture.detectChanges();
            expect(chips(0)['Выбрать ответ']).toBe(false);
            const cloze = [...rows()[0]!.querySelectorAll<HTMLInputElement>('.chip input')].find(box => text(box.closest('label')) === 'Заполнить пропуски')!;
            cloze.click();
            fixture.detectChanges();
            expect(cloze.checked).toBe(true);
            expect(chips(0)['Заполнить пропуски']).toBe(true);
            expect(text(rows()[0]!.querySelector('.hint'))).toBe('Нужен хотя бы один тип: последний остаётся.');
        });

        it('prices every edit at once: a count, a removed row, a returned row', async () => {
            await open();
            input(range(0), '10');
            expect(rows()[0]!.querySelector('output')?.textContent).toBe(`10${NBSP}упражнений`);
            expect(range(0).getAttribute('aria-valuetext')).toBe(`10${NBSP}упражнений`);
            expect(total()).toBe('Всего 13 упражнений · ≈ 5,8 % лимита');
            (rows()[1]!.querySelector('.row-remove') as HTMLButtonElement).click();
            fixture.detectChanges();
            expect(titles()).toEqual(['Статистика и ANALYZE']);
            expect(total()).toBe('Всего 10 упражнений · ≈ 4,4 % лимита');
            const off = root().querySelector('details.off-plan')!;
            expect(text(off.querySelector('summary'))).toBe('Материалы вне плана (1)');
            expect(text(off.querySelector('li'))).toContain('Seq Scan и Index Scan');
            expect(text(off.querySelector('li'))).toContain('упражнений в колоде: 2');
            button('Вернуть', off)!.click();
            fixture.detectChanges();
            expect(titles()).toEqual(['Статистика и ANALYZE', 'Seq Scan и Index Scan']);
            // The row comes back as it was before it was removed.
            expect(chips(1)['Выбрать ответ']).toBe(true);
            expect(chips(1)['Восстановить порядок']).toBe(true);
            expect(range(1).value).toBe('3');
            expect(root().querySelector('details.off-plan')).toBeNull();
        });

        it('moves focus to the next row when one is removed, and to the title when none is left', async () => {
            await open();
            document.body.appendChild(root());
            (rows()[0]!.querySelector('.row-remove') as HTMLButtonElement).click();
            await settle();
            await vi.advanceTimersByTimeAsync(0);
            expect(document.activeElement).toBe(root().querySelector('.row-remove'));
            (rows()[0]!.querySelector('.row-remove') as HTMLButtonElement).click();
            await settle();
            await vi.advanceTimersByTimeAsync(0);
            expect(document.activeElement).toBe(root().querySelector('.plan-title'));
            expect(text(root().querySelector('.notice'))).toContain('В плане не осталось строк');
            root().remove();
        });

        it('says what the server did to the model\'s plan', async () => {
            await open(planReadySession(session => { session.plan.notes = [{ code: 'TRIMMED_TO_BUDGET', text: 'План обрезан по бюджету: последние пункты не поместились.' }]; }));
            expect(text(root().querySelector('.notice[role=note]'))).toBe('План обрезан по бюджету: последние пункты не поместились.');
        });

        it('explains a plan above the limits in words while it is edited, without changing the numbers', async () => {
            await open(planReadySession(session => {
                session.plan.items[0].count = 10; session.plan.items[1].count = 10;
                session.plan.targets = Array.from({ length: 7 }, (_, index) => ({ memberKey: index === 0 ? planTargets.second : index === 1 ? planTargets.first : `44444444-4444-4444-8444-44444444444${index + 1}`, title: `Материал ${index}`, exercises: 0 }));
                session.plan.items = session.plan.targets.map((target: any) => ({ memberKey: target.memberKey, title: target.title, mechanics: ['CLOZE'], count: 10, why: '' }));
            }));
            expect(text(root().querySelector('.notice.error'))).toBe('За один раз — не больше 60 упражнений, сейчас 70: уберите строки или уменьшите числа.');
            expect(total()).toContain('Всего 70 упражнений');
            expect(range(0).value).toBe('10');
        });

        it('says when the hold made for the plan has lapsed: the launch reserves the limit again and may be refused', async () => {
            await open();
            expect(root().textContent).not.toContain('уже освободился');
            await open(planReadySession(session => { session.plan.cost.holdActive = false; }));
            expect(text(root().querySelector('.notice[role=note]'))).toContain('Лимит, отложенный под этот план, уже освободился.');
            expect(text(root().querySelector('.notice[role=note]'))).toContain('запуск не начнётся и ничего не спишется');
        });

        it('warns when the plan costs more than the hold that was made for it, and says the launch may be refused', async () => {
            await open();
            for (let row = 0; row < 2; row++) input(range(row), '10');
            expect(text(root().querySelector('.notice[role=note]'))).toContain('дороже, чем Мнема отложила под него');
        });
    });

    describe('launching', () => {
        it('sends exactly the rows left, in order, with the version the owner saw, then the Workshop runs', async () => {
            await open();
            input(range(0), '4');
            (rows()[0]!.querySelector('.chip input') as HTMLInputElement).click();
            (rows()[1]!.querySelector('.row-remove') as HTMLButtonElement).click();
            fixture.detectChanges();
            api.approvePlan.mockReturnValue(of(launched()));
            button('Запустить по плану')!.click();
            await settle();
            expect(api.approvePlan).toHaveBeenCalledTimes(1);
            const [deck, session, plan, sentRows, version, commandId] = api.approvePlan.mock.calls[0]!;
            const sent = sentRows.map(row => row.item as ExercisePlanItem);
            expect([deck, session, version]).toEqual([ids.deckId, ids.sessionId, '3']);
            expect(plan.kind).toBe('EXERCISES');
            expect(sent.map(item => [item.memberKey, item.count])).toEqual([[planTargets.second, 4]]);
            expect(sent[0]!.mechanics).toEqual(['SELF_CHECK', 'CLOZE', 'CHOICE']);
            expect(commandId).toMatch(/^[0-9a-f-]{36}$/u);
            expect(store.session()!.state).toBe('RUNNING');
            expect(store.session()!.artifacts).toHaveLength(6);
            expect(toast.echo).toHaveBeenCalledWith('План запущен');
        });

        it('sends the same command again after an unknown outcome, and a new one after the plan was edited', async () => {
            await open();
            api.approvePlan.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 0 })));
            button('Запустить по плану')!.click();
            await settle();
            expect(text(root().querySelector('[role=alert]'))).toContain('будет отправлена та же команда');
            button('Запустить по плану')!.click();
            await settle();
            expect(api.approvePlan.mock.calls[1]![5]).toBe(api.approvePlan.mock.calls[0]![5]);
            input(range(0), '5');
            button('Запустить по плану')!.click();
            await settle();
            expect(api.approvePlan.mock.calls[2]![5]).not.toBe(api.approvePlan.mock.calls[0]![5]);
            expect(root().querySelector('[role=alert]')).not.toBeNull();
        });

        it('explains a refusal in words and keeps the plan the owner edited: above the session limit', async () => {
            await open();
            input(range(0), '9');
            api.approvePlan.mockReturnValue(throwError(() => problemResponse(422, { code: 'RESOURCE_LIMIT_EXCEEDED', limit: 'EXERCISES_PER_SESSION', limits: { maxExercisesPerSession: 60 } })));
            button('Запустить по плану')!.click();
            await settle();
            expect(text(root().querySelector('[role=alert]'))).toBe('За один раз можно создать не больше 60 упражнений. Уменьшите числа или уберите строки. Ничего не создано и не списано.');
            expect(range(0).value).toBe('9');
            expect(store.session()!.state).toBe('PLAN_READY');
            // Any edit takes the old complaint away.
            input(range(0), '8');
            expect(root().querySelector('[role=alert]')).toBeNull();
        });

        it('explains that the balance does not cover the plan, and that nothing changed', async () => {
            await open();
            api.approvePlan.mockReturnValue(throwError(() => problemResponse(409, { code: 'USAGE_LIMIT_REACHED' })));
            button('Запустить по плану')!.click();
            await settle();
            expect(text(root().querySelector('[role=alert]'))).toContain('Не хватает лимита ИИ на этот план');
            expect(text(root().querySelector('[role=alert]'))).toContain('Ничего не изменилось и не списано');
        });

        it('reads the session again after a stale version or a plan that is no longer waiting, and says so', async () => {
            await open();
            api.approvePlan.mockReturnValue(throwError(() => problemResponse(412, { code: 'VERSION_CONFLICT' })));
            api.getSession.mockClear();
            button('Запустить по плану')!.click();
            await settle();
            expect(text(root().querySelector('[role=alert]'))).toContain('План изменился, пока вы его правили');
            expect(api.getSession).toHaveBeenCalledTimes(1);
            api.approvePlan.mockReturnValue(throwError(() => problemResponse(409, { code: 'GENERATION_STATE_CONFLICT', reason: 'ILLEGAL_STATE' })));
            button('Запустить по плану')!.click();
            await settle();
            expect(text(root().querySelector('[role=alert]'))).toContain('уже запущен или остановлен');
        });

        it('does not send an empty plan: it says so and keeps «Вернуть» in reach', async () => {
            await open();
            for (let step = 0; step < 2; step++) { (rows()[0]!.querySelector('.row-remove') as HTMLButtonElement).click(); fixture.detectChanges(); }
            button('Запустить по плану')!.click();
            await settle();
            expect(api.approvePlan).not.toHaveBeenCalled();
            expect(text(root().querySelector('[role=alert]'))).toContain('В плане не осталось строк');
            expect(root().querySelector('details.off-plan')).not.toBeNull();
        });

        it('does not send a second launch while the first is on its way', async () => {
            await open();
            const pending = new Subject<CreatedSession>();
            api.approvePlan.mockReturnValue(pending);
            button('Запустить по плану')!.click();
            await settle();
            expect(button('Запускаем…')!.getAttribute('aria-disabled')).toBe('true');
            button('Запускаем…')!.click();
            await settle();
            expect(api.approvePlan).toHaveBeenCalledTimes(1);
            pending.next(launched());
            pending.complete();
            await settle();
            expect(store.session()!.state).toBe('RUNNING');
        });

        it('cancels with «Отменить» and says the plan stays paid', async () => {
            await open();
            expect(text(root().querySelector('.cancel-note'))).toContain('План уже оплачен');
            api.cancelSession.mockReturnValue(of(parseSessionDetail(planReadySession(session => { session.state = 'CANCELLED'; session.rowVersion = '4'; session.endReason = 'USER_CANCELLED'; }))));
            button('Отменить')!.click();
            await settle();
            expect(api.cancelSession).toHaveBeenCalledTimes(1);
            expect(store.session()!.state).toBe('CANCELLED');
        });
    });

    describe('the draft and the server\'s plan', () => {
        it('keeps the edits while a poll finds the same version of the session', async () => {
            await open();
            input(range(0), '7');
            api.getSession.mockReturnValue(of(parseSessionDetail(planReadySession())));
            await store.refresh();
            await settle();
            expect(range(0).value).toBe('7');
            expect(root().textContent).not.toContain('План обновился на сервере');
        });

        it('makes the draft again when the version moves on, and says that the edits were dropped', async () => {
            await open();
            input(range(0), '7');
            api.getSession.mockReturnValue(of(parseSessionDetail(planReadySession(session => { session.rowVersion = '5'; session.plan.items[0].count = 2; }))));
            await store.refresh();
            await settle();
            expect(range(0).value).toBe('2');
            expect(text(root().querySelector('.notice[role=note]'))).toContain('План обновился на сервере');
            // The launch is pinned to the version the owner now sees.
            api.approvePlan.mockReturnValue(of(launched()));
            button('Запустить по плану')!.click();
            await settle();
            expect(api.approvePlan.mock.calls[0]![4]).toBe('5');
        });

        it('shows nothing of the plan once it is launched', async () => {
            await open();
            store.session.set(parseSessionDetail(planApprovedSession()));
            await settle();
            expect(root().querySelector('ol')).toBeNull();
        });
    });

    describe('the plan of materials', () => {
        it('lists a topic and an effort per row, the note it comes from and the reason', async () => {
            await open(materialsPlanSession());
            expect(text(root().querySelector('h2'))).toBe('План материалов');
            expect(text(root().querySelector('.paid'))).toBe('Составление плана: ≈ 5,6 % лимита — уже списано. Отдельно от материалов.');
            expect(rows().map(row => row.querySelector<HTMLInputElement>('.title-input')!.value))
                .toEqual(['Seq Scan: когда он быстрее', 'Статистика и ANALYZE', 'Общая картина планировщика']);
            expect(rows().map(row => text(row.querySelector('.why')))).toEqual(['По заметке: Seq Scan читает всю таблицу', 'По заметке: ANALYZE обновляет статистику', 'Общая картина планировщика']
                .map((value, index) => index === 2 ? '' : value));
            expect(rows().map(row => [...row.querySelectorAll<HTMLInputElement>('input[type=radio]')].find(radio => radio.checked)?.labels?.[0]?.textContent?.trim()))
                .toEqual(['Кратко', 'Средне', 'Подробно']);
            expect(rows()[0]!.querySelector('legend')?.textContent).toBe('Подробность: Seq Scan: когда он быстрее');
            expect(total()).toBe('Всего 3 материала · ≈ 10 % лимита');
        });

        it('prices an effort change and a removed row at once, and sends the topics and efforts the owner left', async () => {
            await open(materialsPlanSession());
            const radios = [...rows()[0]!.querySelectorAll<HTMLInputElement>('input[type=radio]')];
            radios.find(radio => radio.labels?.[0]?.textContent?.trim() === 'Подробно')!.click();
            fixture.detectChanges();
            expect(total()).toBe('Всего 3 материала · ≈ 15 % лимита');
            input(rows()[1]!.querySelector<HTMLInputElement>('.title-input')!, '  Статистика ANALYZE ');
            (rows()[2]!.querySelector('.row-remove') as HTMLButtonElement).click();
            fixture.detectChanges();
            expect(total()).toBe('Всего 2 материала · ≈ 8,9 % лимита');
            api.approvePlan.mockReturnValue(of(launched(materialsPlanSession(session => { session.state = 'RUNNING'; session.rowVersion = '4'; session.plan.approved = true; }))));
            button('Запустить по плану')!.click();
            await settle();
            const sent = api.approvePlan.mock.calls[0]![3].map(row => row.item as any);
            expect(sent.map(item => [item.source, item.title, item.effort])).toEqual([[expect.any(String), 'Seq Scan: когда он быстрее', 'DETAILED'], [expect.any(String), '  Статистика ANALYZE ', 'MEDIUM']]);
        });

        it('does not send a row without a topic: it marks the field, says so and puts focus there', async () => {
            await open(materialsPlanSession());
            document.body.appendChild(root());
            input(rows()[1]!.querySelector<HTMLInputElement>('.title-input')!, '   ');
            button('Запустить по плану')!.click();
            await settle();
            await vi.advanceTimersByTimeAsync(0);
            expect(api.approvePlan).not.toHaveBeenCalled();
            expect(rows()[1]!.querySelector('.title-input')?.getAttribute('aria-invalid')).toBe('true');
            expect(text(rows()[1]!.querySelector('.field-error'))).toBe('Напишите тему.');
            expect(text(root().querySelector('[role=alert]'))).toContain('У каждой строки должна быть тема');
            expect(document.activeElement).toBe(rows()[1]!.querySelector('.title-input'));
            input(rows()[1]!.querySelector<HTMLInputElement>('.title-input')!, 'Тема');
            expect(rows()[1]!.querySelector('.title-input')?.getAttribute('aria-invalid')).toBeNull();
            root().remove();
        });

        it('explains too many materials in words', async () => {
            await open(materialsPlanSession());
            api.approvePlan.mockReturnValue(throwError(() => problemResponse(422, { code: 'RESOURCE_LIMIT_EXCEEDED', limit: 'ARTIFACTS_PER_SESSION', limits: { maxArtifactsPerSession: 20 } })));
            button('Запустить по плану')!.click();
            await settle();
            expect(text(root().querySelector('[role=alert]'))).toContain('не больше 20 материалов');
        });
    });

    it('is the contract example the Workshop gets: the PLAN_READY session of the contract has a plan this component can show', async () => {
        await open(examples['sessionDetailPlanReady']);
        expect(rows()).toHaveLength(2);
        expect(activeStep.kind).toBe('TEXT_DRAFT');
    });
});
