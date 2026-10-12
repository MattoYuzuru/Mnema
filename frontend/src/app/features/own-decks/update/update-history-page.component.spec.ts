import { HttpErrorResponse } from '@angular/common/http';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject, Subject, of, throwError } from 'rxjs';

import { ToastService } from '../../../core/notifications/toast.service';
import { SpyObj, spyObj } from '../../../../testing/mocks';
import { isCanonicalCommandId } from '../own-deck.models';
import { UpdateSeenState } from './update-seen-state';
import type { OwnDeck } from '../own-deck.models';
import { OwnDecksApiService } from '../own-decks-api.service';
import { UpdateHistoryApiService } from './update-history-api.service';
import { HistoryEntry, HistoryUnit, Reverted, UpdateHistory } from './update-history.models';
import { UpdateHistoryPageComponent } from './update-history-page.component';
import { DECK_ID, keyOf } from './update-test-data';

const problem = (status: number, code?: string, extra: Record<string, unknown> = {}) => new HttpErrorResponse({ status, error: code === undefined ? null : { code, ...extra } });
const refusal = (reason: string, extra: Record<string, unknown> = {}) => problem(400, 'INVALID_REQUEST', { reason, ...extra });

const entryId = (index: number) => `44444444-4444-4444-8444-${index.toString().padStart(12, '0')}`;

function unit(kind: HistoryUnit['kind'], index: number, change: HistoryUnit['change'], extra: Partial<HistoryUnit> = {}): HistoryUnit {
    const carries = kind === 'MATERIAL' || kind === 'EXERCISE' || kind === 'OBJECTIVE';
    return {
        unitId: `${kind}:${keyOf(index)}`, kind, key: keyOf(index), change, title: kind === 'ORDER' ? null : `${kind === 'EXERCISE' ? 'Упражнение' : 'Материал'} ${index}`,
        state: carries ? 'CURRENT' : null, revertible: kind === 'MATERIAL' || kind === 'EXERCISE', ...extra
    };
}

function entry(index: number, extra: Partial<HistoryEntry> = {}): HistoryEntry {
    return {
        deckRevisionId: entryId(index), mode: 'AUTO', appliedAt: new Date(Date.now() - 2 * 3_600_000).toISOString(), seen: false, counts: { applied: 3, pending: 2 },
        units: [unit('MATERIAL', 1, 'CHANGED')], unitsTruncated: false, ...extra
    };
}

describe('UpdateHistoryPageComponent', () => {
    let fixture: ComponentFixture<UpdateHistoryPageComponent>;
    let api: SpyObj<UpdateHistoryApiService>;
    let decks: SpyObj<OwnDecksApiService>;
    let toasts: SpyObj<ToastService>;
    let params: BehaviorSubject<ReturnType<typeof convertToParamMap>>;

    const root = () => fixture.nativeElement as HTMLElement;
    const text = (selector: string) => root().querySelector(selector)?.textContent?.replace(/\s+/gu, ' ').trim();
    const button = (label: string) => [...root().querySelectorAll<HTMLButtonElement>('button')].find(candidate => candidate.textContent!.trim() === label)!;
    const revertButtons = () => [...root().querySelectorAll<HTMLButtonElement>('button[data-revert]')];
    const live = () => root().querySelector('.history-page > p.visually-hidden[role=status]')!.textContent;
    const confirmPanel = () => root().querySelector('.revert-confirm');
    const history = (...entries: HistoryEntry[]): UpdateHistory => ({ deckId: DECK_ID, entries });
    const answer = (commandId: string, units: readonly string[]): Reverted => ({ commandId, deckRevisionId: entryId(900), units });
    const lastRevert = () => api.revert.mock.lastCall!;

    async function settle(): Promise<void> {
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
    }

    async function open(...entries: HistoryEntry[]): Promise<void> {
        api.history.mockReturnValue(of(history(...entries)));
        fixture = TestBed.createComponent(UpdateHistoryPageComponent);
        await settle();
    }

    beforeEach(async () => {
        api = spyObj<UpdateHistoryApiService>({ history: vi.fn().mockName('history'), markSeen: vi.fn().mockName('markSeen'), revert: vi.fn().mockName('revert') });
        api.markSeen.mockReturnValue(of(true as const));
        decks = spyObj<OwnDecksApiService>({ detail: vi.fn().mockName('detail') });
        decks.detail.mockReturnValue(of({ deckId: DECK_ID, metadata: { title: 'Моя испанская копия', description: '' }, fork: { detached: false, updateAvailable: false } } as unknown as OwnDeck));
        toasts = spyObj<ToastService>({ echo: vi.fn().mockName('echo') });
        params = new BehaviorSubject(convertToParamMap({ deckId: DECK_ID }));
        await TestBed.configureTestingModule({
            imports: [UpdateHistoryPageComponent],
            providers: [
                provideRouter([{ path: '**', children: [] }]),
                provideHttpClient(),
                provideHttpClientTesting(),
                { provide: UpdateHistoryApiService, useValue: api },
                { provide: OwnDecksApiService, useValue: decks },
                { provide: ToastService, useValue: toasts },
                { provide: ActivatedRoute, useValue: { paramMap: params } }
            ]
        }).compileComponents();
    });

    describe('the screen', () => {
        it('names itself and the copy, links back to the deck and asks for the longest history the contract allows once', async () => {
            await open(entry(1));
            expect(text('h1')).toBe('История обновлений');
            expect(root().querySelector('h1')!.id).toBe('history-title');
            expect(text('.eyebrow')).toBe('Моя испанская копия');
            expect(text('a.back-link')).toBe('← К колоде');
            expect(root().querySelector('a.back-link')!.getAttribute('href')).toBe(`/decks/${DECK_ID}`);
            expect(api.history).toHaveBeenCalledTimes(1);
            expect(api.history).toHaveBeenCalledWith(DECK_ID, 20);
        });

        it('shows a loading line first, then the entries newest first, each with its mode, time, counts and units', async () => {
            const pending = new Subject<UpdateHistory>();
            api.history.mockReturnValue(pending);
            fixture = TestBed.createComponent(UpdateHistoryPageComponent);
            await settle();
            expect(text('p.status')).toBe('Загружаем историю…');
            const old = entry(2, { mode: 'MANUAL', seen: true, appliedAt: '2026-09-01T09:30:00.000Z', counts: { applied: 1, pending: 0 } });
            pending.next(history(entry(1), old));
            await settle();
            expect(root().querySelector('p.status')).toBeNull();
            const entries = [...root().querySelectorAll('ol.entries > li > article')];
            expect(entries).toHaveLength(2);
            expect(entries[0]!.querySelector('h2')!.textContent).toBe('Автоматически, 2 часа назад');
            expect(entries[1]!.querySelector('h2')!.textContent).toMatch(/^Вручную, 01\.09\.2026, \d{2}:\d{2}$/u);
            expect(entries[0]!.querySelector('.entry-head .stamp')).toBeNull();
            expect(entries[0]!.querySelector('.counts')!.textContent).toBe('Принято изменений: 3. Ждали вашего решения: 2.');
            expect(entries[1]!.querySelector('.counts')!.textContent).toBe('Принято изменений: 1.');
            for (const article of entries) {
                const heading = article.querySelector('h2')!;
                expect(article.getAttribute('aria-labelledby')).toBe(heading.id);
                expect(heading.querySelector('time')).not.toBeNull();
            }
            expect(root().querySelectorAll('h1')).toHaveLength(1);
        });

        it('writes the mode and a relative phrase in the heading, the exact date under it, and only the exact date for an old entry', async () => {
            await open(entry(1), entry(2, { appliedAt: '2026-09-01T09:30:00.000Z', mode: 'MANUAL' }));
            const headings = [...root().querySelectorAll('h2')];
            expect(headings[0]!.textContent).toBe('Автоматически, 2 часа назад');
            expect(headings[0]!.querySelector('time')!.textContent).toBe('2 часа назад');
            expect(headings[0]!.querySelector('time')!.getAttribute('datetime')).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/u);
            const exact = [...root().querySelectorAll('.entry > p.when time')];
            expect(exact).toHaveLength(1);
            expect(exact[0]!.textContent).toMatch(/^\d{2}\.\d{2}\.\d{4}, \d{2}:\d{2}$/u);
            expect(headings[1]!.textContent).toMatch(/^Вручную, \d{2}\.\d{2}\.\d{4}, \d{2}:\d{2}$/u);
            expect(headings[1]!.querySelector('time')!.getAttribute('datetime')).toBe('2026-09-01T09:30:00.000Z');
        });

        it('says each kind of change and each state of a unit in words, and offers «Вернуть как было» only where the server allows it', async () => {
            await open(entry(1, {
                units: [
                    unit('MATERIAL', 1, 'ADDED'),
                    unit('EXERCISE', 2, 'CHANGED'),
                    unit('MATERIAL', 3, 'REMOVED'),
                    unit('MATERIAL', 4, 'CHANGED', { state: 'RETURNED', revertible: false }),
                    unit('EXERCISE', 5, 'CHANGED', { state: 'CHANGED_SINCE', revertible: false }),
                    unit('METADATA', 6, 'CHANGED', { title: 'Новое название', state: null, revertible: false }),
                    unit('OBJECTIVE', 7, 'CHANGED', { title: 'Цель', revertible: false }),
                    unit('ORDER', 8, 'REORDERED', { revertible: false })
                ]
            }));
            const rows = [...root().querySelectorAll('app-update-history-unit')];
            const stamps = rows.map(row => row.querySelector('.stamp')?.textContent ?? null);
            expect(stamps).toEqual(['Добавлено', 'Исправлено', 'Удалено', 'Исправлено', 'Исправлено', 'Исправлено', 'Исправлено', null]);
            expect(rows.map(row => row.querySelector('.title')!.textContent)).toEqual([
                'Материал 1', 'Упражнение 2', 'Материал 3', 'Материал 4', 'Упражнение 5', 'Новое название', 'Цель', 'Изменился порядок материалов'
            ]);
            expect(rows.map(row => row.querySelector('.state')?.textContent ?? null)).toEqual([null, null, null, 'Вы уже вернули', 'Вы изменили после обновления', null, null, null]);
            expect(root().querySelector('ul.units article')).toBeNull();
            expect(root().querySelectorAll('ul.units > li')).toHaveLength(8);
            expect(revertButtons().map(item => item.getAttribute('aria-label'))).toEqual([
                'Вернуть как было: «Материал 1»', 'Вернуть как было: «Упражнение 2»', 'Вернуть как было: «Материал 3»'
            ]);
            expect(revertButtons().every(item => item.textContent!.trim() === 'Вернуть как было')).toBe(true);
        });

        it('names a unit without a title by its kind and says when an entry lists only some of its units', async () => {
            await open(entry(1, { units: [unit('EXERCISE', 1, 'REMOVED', { title: '' })], unitsTruncated: true }));
            expect(text('app-update-history-unit .title')).toBe('Упражнение недоступно');
            expect(text('.entry > p.hint:not(.when)')).toBe('Показаны не все изменения этого обновления.');
        });

        it('says there were no updates, and offers the review screen only while the author has something waiting', async () => {
            await open();
            expect(text('p.empty')).toBe('Обновлений пока не было.');
            expect(root().querySelector('a[href$="/update"]')).toBeNull();
            decks.detail.mockReturnValue(of({ deckId: DECK_ID, metadata: { title: 'К', description: '' }, fork: { detached: false, updateAvailable: true } } as unknown as OwnDeck));
            await open(entry(1));
            const link = root().querySelector<HTMLAnchorElement>('a[href$="/update"]')!;
            expect(link.textContent!.trim()).toBe('Посмотреть обновление');
            expect(link.getAttribute('href')).toBe(`/decks/${DECK_ID}/update`);
        });

        it('names «Вернуть как было» of units with the same title in two updates by the time of each, and leaves a title that is unique alone', async () => {
            await open(
                entry(1, { appliedAt: '2026-10-01T09:30:00.000Z', units: [unit('MATERIAL', 1, 'CHANGED', { title: 'Спряжение' }), unit('MATERIAL', 2, 'CHANGED', { title: 'Прошедшее время' })] }),
                entry(2, { appliedAt: '2026-09-01T09:30:00.000Z', units: [unit('MATERIAL', 1, 'CHANGED', { title: 'Спряжение' })] })
            );
            const labels = revertButtons().map(item => item.getAttribute('aria-label')!);
            expect(labels[0]).toMatch(/^Вернуть как было: «Спряжение», обновление от 01\.10\.2026, \d{2}:\d{2}$/u);
            expect(labels[1]).toBe('Вернуть как было: «Прошедшее время»');
            expect(labels[2]).toMatch(/^Вернуть как было: «Спряжение», обновление от 01\.09\.2026, \d{2}:\d{2}$/u);
            expect(new Set(labels).size).toBe(3);
        });

        it('says a detached copy keeps its history but cannot return anything', async () => {
            decks.detail.mockReturnValue(of({ deckId: DECK_ID, metadata: { title: 'К', description: '' }, fork: { detached: true, updateAvailable: false } } as unknown as OwnDeck));
            await open(entry(1, { units: [unit('MATERIAL', 1, 'CHANGED', { revertible: false })] }));
            expect(text('.notice.compact')).toBe('Копия отвязана от автора: изменения можно посмотреть, но вернуть их уже нельзя.');
            expect(revertButtons()).toHaveLength(0);
        });

        it('says a history that failed to load, retries it, and does not offer a retry for a copy that is not there', async () => {
            api.history.mockReturnValueOnce(throwError(() => problem(500)));
            fixture = TestBed.createComponent(UpdateHistoryPageComponent);
            await settle();
            expect(text('h2')).toBe('Не удалось открыть историю');
            expect(text('section.notice p')).toBe('Не удалось загрузить историю. Проверьте соединение и повторите.');
            api.history.mockReturnValueOnce(of(history(entry(1))));
            button('Повторить').click();
            await settle();
            expect(root().querySelector('section.notice.error')).toBeNull();
            expect(root().querySelectorAll('ol.entries > li')).toHaveLength(1);

            api.history.mockReturnValueOnce(throwError(() => problem(404, 'RESOURCE_NOT_FOUND')));
            fixture = TestBed.createComponent(UpdateHistoryPageComponent);
            await settle();
            expect(text('section.notice p')).toBe('Колода не найдена или больше недоступна.');
            expect(root().querySelector('button')).toBeNull();
        });

        it('treats an id that is not a deck id as a deck that is not there, without asking the server', async () => {
            params.next(convertToParamMap({ deckId: 'x' }));
            fixture = TestBed.createComponent(UpdateHistoryPageComponent);
            await settle();
            expect(text('section.notice p')).toBe('Колода не найдена или больше недоступна.');
            expect(api.history).not.toHaveBeenCalled();
        });
    });

    describe('«Что нового» is seen', () => {
        it('marks each unseen automatic entry seen once, and no manual or already seen one', async () => {
            await open(entry(1), entry(2, { seen: true }), entry(3, { mode: 'MANUAL' }), entry(4));
            expect(api.markSeen.mock.calls.map(call => call.slice(0, 2))).toEqual([[DECK_ID, entryId(1)], [DECK_ID, entryId(4)]]);
        });

        it('does not ask again for an entry it already marked when the history is read again, and asks again for one whose mark failed', async () => {
            api.markSeen.mockImplementation((_deck, id) => id === entryId(2) ? throwError(() => problem(500)) : of(true as const));
            await open(entry(1), entry(2));
            expect(api.markSeen).toHaveBeenCalledTimes(2);
            fixture.componentInstance.reload();
            await settle();
            expect(api.markSeen.mock.calls.map(call => call[1])).toEqual([entryId(1), entryId(2), entryId(2)]);
            expect(root().querySelector('.notice.error')).toBeNull();
        });

        it('hides the notice in the hub at once (this tab\'s seen state), and puts it back when the server did not hear of it', async () => {
            const state = TestBed.inject(UpdateSeenState);
            api.markSeen.mockImplementation((_deck, id) => id === entryId(2) ? throwError(() => problem(500)) : of(true as const));
            await open(entry(1), entry(2), entry(3, { seen: true }));
            expect([...state.seen()]).toEqual([entryId(1)]);
        });

        it('marks nothing for an empty history', async () => {
            await open();
            expect(api.markSeen).not.toHaveBeenCalled();
        });
    });

    describe('taking a unit back', () => {
        const material = unit('MATERIAL', 1, 'CHANGED');
        const exercise = unit('EXERCISE', 2, 'CHANGED');

        it.each([
            ['ADDED', 'Уберём «Материал 1» из вашей колоды.'],
            ['CHANGED', 'Вернём «Материал 1» к тому, что было до обновления.'],
            ['REMOVED', 'Вернём «Материал 1» в вашу колоду на прежнее место.']
        ] as const)('asks in one sentence what a %s unit will become before it sends anything', async (change, sentence) => {
            await open(entry(1, { units: [{ ...material, change }] }));
            revertButtons()[0]!.click();
            await settle();
            expect(text('.revert-confirm .ask')).toBe(sentence);
            expect(confirmPanel()!.getAttribute('role')).toBe('group');
            expect(confirmPanel()!.getAttribute('aria-labelledby')).toBe(root().querySelector('.revert-confirm .ask')!.id);
            // The group takes the focus and is read whole: named by the question, described by everything else.
            expect(document.activeElement).toBe(confirmPanel());
            const described = confirmPanel()!.getAttribute('aria-describedby')!.split(' ').map(id => root().querySelector(`#${id}`)!.textContent);
            expect(described).toEqual(['Следующее обновление не вернёт это обратно, пока автор не изменит его снова.']);
            expect([...confirmPanel()!.querySelectorAll('button')].map(item => item.textContent!.trim())).toEqual(['Вернуть', 'Отмена']);
            expect(revertButtons()).toHaveLength(0);
            expect(api.revert).not.toHaveBeenCalled();
        });

        it('sends the command with a fresh command id, then says it in a toast only, and reads the history and the deck again', async () => {
            await open(entry(1));
            decks.detail.mockClear();
            api.history.mockClear();
            api.revert.mockReturnValue(of(answer('x', [material.unitId])));
            revertButtons()[0]!.click();
            await settle();
            button('Вернуть').click();
            await settle();
            const [deckId, revisionId, unitId, command] = lastRevert();
            expect([deckId, revisionId, unitId]).toEqual([DECK_ID, entryId(1), material.unitId]);
            expect(isCanonicalCommandId(command.commandId)).toBe(true);
            expect(command.with).toEqual([]);
            expect(toasts.echo).toHaveBeenCalledWith('Вернули «Материал 1».');
            // One announcement: the toast. The live region of the page stays silent.
            expect(live()).toBe('');
            expect(api.history).toHaveBeenCalledTimes(1);
            expect(decks.detail).toHaveBeenCalledTimes(1);
            expect(confirmPanel()).toBeNull();
        });

        it('says how many more units came back with it', async () => {
            await open(entry(1));
            api.revert.mockReturnValue(of(answer('x', [material.unitId, exercise.unitId, `EXERCISE:${keyOf(3)}`])));
            revertButtons()[0]!.click();
            await settle();
            button('Вернуть').click();
            await settle();
            expect(toasts.echo).toHaveBeenCalledWith('Вернули «Материал 1» и ещё 2.');
        });

        it('moves the focus to the reverted row, which now says «Вы уже вернули», and to the heading when the row is gone', async () => {
            await open(entry(1));
            api.revert.mockReturnValue(of(answer('x', [material.unitId])));
            api.history.mockReturnValue(of(history(entry(1, { units: [{ ...material, state: 'RETURNED', revertible: false }] }))));
            revertButtons()[0]!.click();
            await settle();
            button('Вернуть').click();
            await settle();
            await settle();
            const row = root().querySelector<HTMLElement>(`li[data-unit="${entryId(1)}|${material.unitId}"]`)!;
            expect(row.getAttribute('tabindex')).toBe('-1');
            expect(row.querySelector('.state')!.textContent).toBe('Вы уже вернули');
            expect(document.activeElement).toBe(row);

            api.history.mockReturnValue(of(history(entry(1, { units: [] }))));
            api.revert.mockReturnValue(of(answer('x', [material.unitId])));
            await open(entry(1));
            api.history.mockReturnValue(of(history(entry(1, { units: [] }))));
            revertButtons()[0]!.click();
            await settle();
            button('Вернуть').click();
            await settle();
            await settle();
            expect(document.activeElement).toBe(root().querySelector('h1'));
        });

        it('cancels with the button or Escape, sends nothing and gives the focus back to «Вернуть как было»', async () => {
            await open(entry(1));
            revertButtons()[0]!.click();
            await settle();
            button('Отмена').click();
            await settle();
            expect(confirmPanel()).toBeNull();
            expect(api.revert).not.toHaveBeenCalled();
            expect(document.activeElement).toBe(revertButtons()[0]);
            revertButtons()[0]!.click();
            await settle();
            confirmPanel()!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
            await settle();
            expect(confirmPanel()).toBeNull();
            expect(document.activeElement).toBe(revertButtons()[0]);
        });

        it('keeps the button busy while the request runs and starts no second return meanwhile', async () => {
            await open(entry(1, { units: [material, exercise] }));
            const pending = new Subject<Reverted>();
            api.revert.mockReturnValue(pending);
            revertButtons()[0]!.click();
            await settle();
            button('Вернуть').click();
            await settle();
            const busy = confirmPanel()!.querySelector('.button.primary')!;
            expect(busy.textContent!.trim()).toBe('Возвращаем…');
            expect(busy.getAttribute('aria-busy')).toBe('true');
            expect(busy.getAttribute('aria-disabled')).toBe('true');
            expect(confirmPanel()!.querySelector<HTMLButtonElement>('.button.quiet')!.disabled).toBe(true);
            expect(revertButtons()[0]!.getAttribute('aria-disabled')).toBe('true');
            revertButtons()[0]!.click();
            (busy as HTMLButtonElement).click();
            await settle();
            expect(api.revert).toHaveBeenCalledTimes(1);
        });

        describe('a unit that goes back together with others', () => {
            const needed = (units: readonly string[], unitId = material.unitId) => refusal('unit_needed', { unitId, units });

            async function openAndAsk(...units: HistoryUnit[]): Promise<void> {
                await open(entry(1, { units: units.length > 0 ? units : [material, exercise] }));
                revertButtons()[0]!.click();
                await settle();
            }

            it('shows «Вместе с этим вернём к прежнему виду: …» and a second confirmation, and sends the named units in `with` under a new command', async () => {
                await openAndAsk();
                api.revert.mockReturnValueOnce(throwError(() => needed([exercise.unitId])));
                button('Вернуть').click();
                await settle();
                const first = lastRevert()[3];
                expect(text('.revert-confirm .ask')).toBe('Вернём «Материал 1» к тому, что было до обновления.');
                expect(text('.revert-confirm p:nth-of-type(2)')).toBe('Вместе с этим вернём к прежнему виду: «Упражнение 2».');
                expect(text('.revert-confirm .hint')).toBe('Они связаны между собой, поэтому возвращаются вместе.');
                expect([...confirmPanel()!.querySelectorAll('button')].map(item => item.textContent!.trim())).toEqual(['Вернуть всё это', 'Отмена']);
                // The group takes the focus again and is described by every paragraph but the question, the new line included.
                expect(document.activeElement).toBe(confirmPanel());
                const described = confirmPanel()!.getAttribute('aria-describedby')!.split(' ').map(id => root().querySelector(`#${id}`)!.textContent);
                expect(described).toEqual(['Вместе с этим вернём к прежнему виду: «Упражнение 2».', 'Они связаны между собой, поэтому возвращаются вместе.',
                    'Следующее обновление не вернёт это обратно, пока автор не изменит его снова.']);
                expect(live()).toBe('');

                api.revert.mockReturnValueOnce(of(answer('x', [material.unitId, exercise.unitId])));
                button('Вернуть всё это').click();
                await settle();
                const second = lastRevert()[3];
                expect(second.with).toEqual([exercise.unitId]);
                expect(isCanonicalCommandId(second.commandId)).toBe(true);
                expect(second.commandId).not.toBe(first.commandId);
                expect(toasts.echo).toHaveBeenCalledWith('Вернули «Материал 1» и ещё 1.');
                expect(confirmPanel()).toBeNull();
            });

            it('names a unit the entry does not list by its kind, and says what happens to each by what the update did to it', async () => {
                await openAndAsk(material);
                api.revert.mockReturnValueOnce(throwError(() => needed([exercise.unitId, `EXERCISE:${keyOf(9)}`])));
                button('Вернуть').click();
                await settle();
                expect(text('.revert-confirm p:nth-of-type(2)')).toBe('Вместе с этим вернутся: «Упражнение недоступно», «Упражнение недоступно».');
            });

            it('tells apart what is removed, what comes back and what is returned to its earlier form, one line each', async () => {
                const added = unit('EXERCISE', 3, 'ADDED');
                const removed = unit('MATERIAL', 4, 'REMOVED');
                const changed = unit('EXERCISE', 5, 'CHANGED');
                await openAndAsk(material, added, removed, changed);
                api.revert.mockReturnValueOnce(throwError(() => needed([added.unitId, removed.unitId, changed.unitId])));
                button('Вернуть').click();
                await settle();
                const lines = [...confirmPanel()!.querySelectorAll('p')].map(line => line.textContent);
                expect(lines.slice(1, 4)).toEqual([
                    'Вместе с этим вернём к прежнему виду: «Упражнение 5».',
                    'Вместе с этим вернём: «Материал 4».',
                    'Вместе с этим уберём: «Упражнение 3».'
                ]);
                expect(confirmPanel()!.getAttribute('aria-describedby')!.split(' ')).toHaveLength(5);
            });

            it('asks again for what a second answer adds, and gives up when it names nothing new', async () => {
                await openAndAsk();
                api.revert.mockReturnValueOnce(throwError(() => needed([exercise.unitId])));
                button('Вернуть').click();
                await settle();
                api.revert.mockReturnValueOnce(throwError(() => needed([exercise.unitId, `EXERCISE:${keyOf(3)}`])));
                button('Вернуть всё это').click();
                await settle();
                expect(lastRevert()[3].with).toEqual([exercise.unitId]);
                expect(text('.revert-confirm p:nth-of-type(2)')).toBe('Вместе с этим вернём к прежнему виду: «Упражнение 2».');
                expect(text('.revert-confirm p:nth-of-type(3)')).toBe('Вместе с этим вернутся: «Упражнение недоступно».');
                api.revert.mockReturnValueOnce(throwError(() => needed([exercise.unitId])));
                button('Вернуть всё это').click();
                await settle();
                expect(lastRevert()[3].with).toEqual([exercise.unitId, `EXERCISE:${keyOf(3)}`]);
                expect(text('.revert-confirm [role=alert]')).toBe('Не удалось вернуть. Ничего не изменилось.');
            });

            it('does not offer a group the server cannot take in one command', async () => {
                await openAndAsk();
                const many = Array.from({ length: 21 }, (_, index) => `EXERCISE:${keyOf(100 + index)}`);
                api.revert.mockReturnValueOnce(throwError(() => needed(many)));
                button('Вернуть').click();
                await settle();
                expect(text('.revert-confirm [role=alert]')).toBe('Не удалось вернуть. Ничего не изменилось.');
                expect(confirmPanel()!.textContent).not.toContain('Вместе с этим');
            });
        });

        describe('when the return does not go through', () => {
            async function ask(): Promise<void> {
                await open(entry(1, { units: [unit('MATERIAL', 1, 'CHANGED'), unit('EXERCISE', 2, 'CHANGED')] }));
                revertButtons()[0]!.click();
                await settle();
            }
            const fail = async (error: HttpErrorResponse) => {
                api.revert.mockReturnValueOnce(throwError(() => error));
                button('Вернуть').click();
                await settle();
            };

            it('explains a unit changed after the update (409 UPDATE_REVERT_STALE with the unit), closes the question and reads the history and the deck again', async () => {
                await ask();
                api.history.mockClear();
                decks.detail.mockClear();
                await fail(problem(409, 'UPDATE_REVERT_STALE', { unitId: `MATERIAL:${keyOf(1)}` }));
                expect(text('.notice.compact')).toBe('После обновления вы уже меняли это, поэтому вернуть нельзя. Мы обновили список.');
                // The question that had the focus is gone: the notice takes it.
                const notice = root().querySelector('.notice.compact')!;
                expect(notice.getAttribute('tabindex')).toBe('-1');
                expect(document.activeElement).toBe(notice);
                // Focus announces it; the live region does not repeat the same sentence.
                expect(live()).toBe('');
                expect(confirmPanel()).toBeNull();
                expect(api.history).toHaveBeenCalledTimes(1);
                expect(decks.detail).toHaveBeenCalledTimes(1);
                expect(toasts.echo).not.toHaveBeenCalled();
            });

            it('explains a copy that moved while the command was built (409 UPDATE_REVERT_STALE without a unit) and reads again', async () => {
                await ask();
                await fail(problem(409, 'UPDATE_REVERT_STALE'));
                expect(text('.notice.compact')).toBe('Пока вы смотрели, колода изменилась. Мы обновили список, попробуйте ещё раз.');
            });

            it('says a copy detached meanwhile cannot take anything back (409 FORK_DETACHED), once, and reads again', async () => {
                await ask();
                api.history.mockClear();
                decks.detail.mockReturnValue(of({ deckId: DECK_ID, metadata: { title: 'К', description: '' }, fork: { detached: true, updateAvailable: false } } as unknown as OwnDeck));
                await fail(problem(409, 'FORK_DETACHED'));
                expect([...root().querySelectorAll('.notice.compact')].map(item => item.textContent!.trim())).toEqual(['Колода отвязана от автора, поэтому вернуть изменения нельзя. Мы обновили список.']);
                expect(document.activeElement).toBe(root().querySelector('.notice.compact'));
                expect(api.history).toHaveBeenCalledTimes(1);
            });

            it('says a copy that is still preparing and an update that is gone', async () => {
                await ask();
                await fail(problem(409, 'DECK_PREPARING'));
                expect(text('.notice.compact')).toBe('Копия ещё готовится. Вернуть изменения можно, когда она будет готова.');
                revertButtons()[0]!.click();
                await settle();
                await fail(problem(404, 'RESOURCE_NOT_FOUND'));
                expect(text('.notice.compact')).toBe('Это обновление или колода больше недоступны. Мы обновили список.');
            });

            it('focuses the notice for a copy still preparing and for an update that is gone', async () => {
                await ask();
                await fail(problem(409, 'DECK_PREPARING'));
                expect(document.activeElement).toBe(root().querySelector('.notice.compact'));
                revertButtons()[0]!.click();
                await settle();
                await fail(problem(404, 'RESOURCE_NOT_FOUND'));
                expect(document.activeElement).toBe(root().querySelector('.notice.compact'));
            });

            it('clears the notice when the next return starts', async () => {
                await ask();
                await fail(problem(409, 'UPDATE_REVERT_STALE'));
                expect(root().querySelector('.notice.compact')).not.toBeNull();
                revertButtons()[0]!.click();
                await settle();
                expect(root().querySelector('.notice.compact')).toBeNull();
            });

            it.each([
                ['material_still_used', { unitId: `MATERIAL:${keyOf(1)}`, dependency: `EXERCISE:${keyOf(2)}` }, 'Нельзя убрать материал: к нему привязано ваше упражнение «Упражнение 2», которое не было частью обновления.'],
                ['material_still_used', { unitId: `MATERIAL:${keyOf(1)}`, dependency: `EXERCISE:${keyOf(50)}` }, 'Нельзя убрать материал: к нему привязано ваше упражнение, которое не было частью обновления.'],
                ['unit_not_returnable', {}, 'Это изменение нельзя вернуть по отдельности.'],
                ['publication_refused', {}, 'Не удалось вернуть. Ничего не изменилось.'],
                ['duplicate_unit', {}, 'Не удалось вернуть. Ничего не изменилось.']
            ])('keeps the question open and says why for a 400 %s', async (reason, extra, sentence) => {
                await ask();
                await fail(refusal(reason, extra));
                expect(text('.revert-confirm [role=alert]')).toBe(sentence);
                expect(confirmPanel()).not.toBeNull();
                expect(button('Вернуть')).toBeDefined();
                expect(api.history).toHaveBeenCalledTimes(1);
            });

            it('says too many requests and lets the same command go again', async () => {
                await ask();
                await fail(problem(429, 'RATE_LIMITED'));
                expect(text('.revert-confirm [role=alert]')).toBe('Слишком много запросов. Подождите немного и повторите.');
                const first = lastRevert()[3];
                api.revert.mockReturnValueOnce(of(answer('x', [`MATERIAL:${keyOf(1)}`])));
                button('Вернуть').click();
                await settle();
                expect(lastRevert()[3]).toEqual(first);
            });

            it.each([[0], [500], [503]])('retries the very same command after an unknown outcome (status %i)', async status => {
                await ask();
                await fail(problem(status));
                expect(text('.revert-confirm [role=alert]')).toBe('Не удалось узнать, вернулось ли. Повторите: ничего не будет возвращено дважды.');
                const first = lastRevert()[3];
                const retry = button('Повторить');
                expect(retry.classList.contains('primary')).toBe(true);
                api.revert.mockReturnValueOnce(of(answer('x', [`MATERIAL:${keyOf(1)}`])));
                retry.click();
                await settle();
                expect(api.revert).toHaveBeenCalledTimes(2);
                expect(lastRevert()[3].commandId).toBe(first.commandId);
                expect(lastRevert()[3].with).toEqual(first.with);
                expect(toasts.echo).toHaveBeenCalledWith('Вернули «Материал 1».');
            });

            it('retries the same command with the same `with` after an unknown outcome of the second step', async () => {
                await ask();
                await fail(refusal('unit_needed', { unitId: `MATERIAL:${keyOf(1)}`, units: [`EXERCISE:${keyOf(2)}`] }));
                api.revert.mockReturnValueOnce(throwError(() => problem(0)));
                button('Вернуть всё это').click();
                await settle();
                const second = lastRevert()[3];
                expect(second.with).toEqual([`EXERCISE:${keyOf(2)}`]);
                api.revert.mockReturnValueOnce(of(answer('x', [`MATERIAL:${keyOf(1)}`, `EXERCISE:${keyOf(2)}`])));
                button('Повторить').click();
                await settle();
                expect(lastRevert()[3]).toEqual(second);
            });

            it('treats an answer that does not match the contract (not an HTTP failure) as an unknown outcome', async () => {
                await ask();
                api.revert.mockReturnValueOnce(throwError(() => new Error('protocol')));
                button('Вернуть').click();
                await settle();
                expect(text('.revert-confirm [role=alert]')).toBe('Не удалось узнать, вернулось ли. Повторите: ничего не будет возвращено дважды.');
                expect(button('Повторить')).toBeDefined();
            });
        });
    });
});
