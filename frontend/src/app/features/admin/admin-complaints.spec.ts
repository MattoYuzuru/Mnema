import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, Type, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';

import contract from '../../../../../contracts/moderation/complaints.json';
import { AuthService } from '../../auth.service';
import { AdminSession } from './admin-api.service';
import { AdminComplaintDetailPageComponent } from './admin-complaint-detail-page.component';
import { AdminComplaintJournalPageComponent } from './admin-complaint-journal-page.component';
import { AdminComplaintsPageComponent } from './admin-complaints-page.component';
import { clone, ownerId } from './admin-test-data';

const first = contract.queue.answer.items[0];
const second = contract.queue.answer.items[1];
const base = contract.complaint.answer;
const openDeck = (patch: Record<string, unknown> = {}, deck: Record<string, unknown> = {}): typeof base => ({
    ...clone(base), status: 'OPEN', decision: null, decidedAt: null, decidedBy: null, overdue: false,
    deck: { ...clone(base.deck), hiddenByModeration: false, hiddenAt: null, ...deck }, takedowns: { count: 0, truncated: false, items: [] },
    changed: undefined, ...patch
} as unknown as typeof base);
const openProfile = (): object => ({
    ...clone(base), complaintId: second.complaintId, targetKind: 'PROFILE', reason: 'PERSONAL_DATA', status: 'OPEN', decision: null, decidedAt: null,
    decidedBy: null, deck: null, profile: { accountId: second.profileAccountId, username: second.profileUsername },
    takedowns: { count: 0, truncated: false, items: [] }, changed: undefined
});
const id = first.complaintId;
const NOW = Date.parse('2026-10-11T00:00:00Z');

let http: HttpTestingController;
let fixture: ComponentFixture<unknown>;
let root: HTMLElement;
let params: BehaviorSubject<ReturnType<typeof convertToParamMap>>;
let query: BehaviorSubject<ReturnType<typeof convertToParamMap>>;

@Component({ template: '' })
class Stub {}

async function setup<T>(type: Type<T>, routeParams: Record<string, string> = {}, queryParams: Record<string, string> = {}): Promise<void> {
    TestBed.configureTestingModule({
        providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([{ path: '**', component: Stub }]),
            { provide: AuthService, useValue: { status: signal('authenticated'), user: () => ({ accountId: ownerId }) } }]
    });
    const route = TestBed.inject(ActivatedRoute);
    params = new BehaviorSubject(convertToParamMap(routeParams));
    query = new BehaviorSubject(convertToParamMap(queryParams));
    Object.defineProperty(route, 'paramMap', { value: params.asObservable() });
    Object.defineProperty(route, 'queryParamMap', { value: query.asObservable() });
    TestBed.inject(AdminSession).access.set({ owner: true, permissions: { events: true, moderation: true, promos: true, support: true } });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(type);
    root = fixture.nativeElement as HTMLElement;
    // Attached, so the focus can be asserted
    document.body.append(root);
    fixture.detectChanges();
}
async function settle(): Promise<void> {
    for (let round = 0; round < 4; round++) await Promise.resolve();
    fixture.detectChanges();
}
function button(text: string): HTMLButtonElement {
    const found = [...root.querySelectorAll<HTMLButtonElement>('button')].find(candidate => candidate.textContent?.trim().includes(text));
    expect(found, text).toBeDefined();
    return found!;
}
function click(text: string): void { button(text).click(); fixture.detectChanges(); }
const queueUrl = (status: string, cursor?: string) => (request: { url: string; params: { get(name: string): string | null } }) =>
    request.url === '/api/admin/complaints' && request.params.get('status') === status && request.params.get('limit') === '50' && request.params.get('cursor') === (cursor ?? null);

beforeEach(() => { vi.useFakeTimers({ toFake: ['Date'] }); vi.setSystemTime(NOW); });
afterEach(() => { http?.verify(); root?.remove(); fixture?.destroy(); vi.useRealTimers(); });

describe('complaint queue', () => {
    it('lists open complaints with the deadline in words, the reason and a link to each', async () => {
        await setup(AdminComplaintsPageComponent);
        const overdue = { ...clone(first), complaintId: '0199c478-b000-7000-8000-000000000009', overdue: true, dueAt: '2026-10-08T00:00:00Z' };
        http.expectOne(queueUrl('OPEN')).flush({ items: [overdue, first, second], nextCursor: null });
        await settle();
        const rows = [...root.querySelectorAll('tbody tr')].map(row => row.textContent!.replace(/\s+/g, ' ').trim());
        expect(rows).toHaveLength(3);
        expect(rows[0]).toContain('Просрочена на 3 дня');
        expect(rows[1]).toContain('Срок через 10 дней');
        expect(rows[2]).toContain('Срок через 21 день');
        expect(rows[1]).toContain('Английский: путешествия');
        expect(rows[1]).toContain('код Kq7xT3mNpR');
        expect(rows[1]).toContain('Нарушены авторские права');
        expect(rows[2]).toContain('Профиль @ivan.k');
        expect(rows[2]).toContain('Раскрыты личные данные');
        expect(root.querySelectorAll<HTMLAnchorElement>('tbody th a')[1].getAttribute('href')).toBe(`/manage/complaints/${first.complaintId}`);
        expect(root.querySelector('h1')!.textContent).toBe('Жалобы');
        expect(root.querySelector('caption')!.textContent).toContain('самый ранний срок первым');
    });

    it('says a day-old overdue complaint is still overdue and one due today expires today', async () => {
        await setup(AdminComplaintsPageComponent);
        const today = { ...clone(first), dueAt: '2026-10-11T08:00:00Z' };
        const justOverdue = { ...clone(first), complaintId: '0199c478-b000-7000-8000-000000000008', overdue: true, dueAt: '2026-10-10T20:00:00Z' };
        http.expectOne(queueUrl('OPEN')).flush({ items: [justOverdue, today], nextCursor: null });
        await settle();
        const rows = [...root.querySelectorAll('tbody tr')].map(row => row.textContent!);
        expect(rows[0]).toContain('Просрочена');
        expect(rows[0]).not.toContain('на 0');
        expect(rows[1]).toContain('Срок через 1 день');
    });

    it('switches to the decided complaints through the address and shows what each decision did', async () => {
        await setup(AdminComplaintsPageComponent);
        http.expectOne(queueUrl('OPEN')).flush({ items: [first], nextCursor: null });
        await settle();
        const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
        [...root.querySelectorAll<HTMLInputElement>('app-segmented-choice input')].find(radio => radio.closest('label')!.textContent!.trim() === 'Решённые')!.click();
        expect(navigate).toHaveBeenCalledWith(['/manage/complaints'], { queryParams: { status: 'DECIDED' } });
        query.next(convertToParamMap({ status: 'DECIDED' }));
        const decided = { ...clone(first), status: 'DECIDED', decision: 'HIDDEN', decidedAt: '2026-10-12T09:30:00Z' };
        http.expectOne(queueUrl('DECIDED')).flush({ items: [decided], nextCursor: null });
        await settle();
        expect(root.querySelector('thead')!.textContent).toContain('Решение');
        expect(root.querySelector('tbody')!.textContent).toContain('Колода скрыта из Сообщества');
        expect(root.querySelector('tbody')!.textContent).toContain('Решена');
    });

    it('continues by itself with the cursor, drops duplicates and keeps the rows when a page fails', async () => {
        await setup(AdminComplaintsPageComponent);
        http.expectOne(queueUrl('OPEN')).flush({ items: [first], nextCursor: 'c2' });
        await settle();
        const more = () => fixture.debugElement.query(By.css('app-auto-load')).triggerEventHandler('loadNext');
        more();
        http.expectOne(queueUrl('OPEN', 'c2')).flush({}, { status: 503, statusText: 'Unavailable' });
        await settle();
        expect(root.querySelectorAll('tbody tr')).toHaveLength(1);
        expect(root.querySelector('app-auto-load [role=alert]')!.textContent).toContain('Следующие жалобы не загрузились');
        click('Повторить');
        http.expectOne(queueUrl('OPEN', 'c2')).flush({ items: [first, second], nextCursor: null });
        await settle();
        expect(root.querySelectorAll('tbody tr')).toHaveLength(2);
        expect(root.textContent).not.toContain('Показать ещё');
    });

    it('says plainly when the queue is empty, and offers a retry when it cannot be read', async () => {
        await setup(AdminComplaintsPageComponent);
        http.expectOne(queueUrl('OPEN')).flush({}, { status: 500, statusText: 'x' });
        await settle();
        expect(root.querySelector('[role=alert]')!.textContent).toContain('Не удалось загрузить жалобы');
        click('Повторить');
        http.expectOne(queueUrl('OPEN')).flush({ items: [], nextCursor: null });
        await settle();
        expect(root.querySelector('tbody')!.textContent).toContain('Открытых жалоб нет.');
    });

    it('links to the decision journal', async () => {
        await setup(AdminComplaintsPageComponent);
        http.expectOne(queueUrl('OPEN')).flush({ items: [], nextCursor: null });
        await settle();
        expect([...root.querySelectorAll<HTMLAnchorElement>('header a')].map(link => link.getAttribute('href'))).toEqual(['/manage/complaints/journal']);
    });
});

describe('complaint detail', () => {
    const detailUrl = `/api/admin/complaints/${id}`;

    it('shows who and what the complaint is about without any name, and the way to the account pages', async () => {
        await setup(AdminComplaintDetailPageComponent, { complaintId: id });
        http.expectOne(detailUrl).flush(openDeck());
        await settle();
        const text = root.textContent!.replace(/\s+/g, ' ');
        expect(text).toContain('Нарушены авторские права');
        expect(text).toContain(base.text);
        expect(text).toContain('Английский: путешествия');
        expect(text).toContain('Срок через 10 дней');
        expect(text).toContain('Ему о решении не сообщается');
        expect(text).toContain('Открытых жалоб на колоду');
        const hrefs = [...root.querySelectorAll<HTMLAnchorElement>('a')].map(link => link.getAttribute('href'));
        expect(hrefs).toContain(`/manage/users/${base.reporterId}`);
        expect(hrefs).toContain(`/manage/users/${base.deck.ownerId}`);
        expect(root.querySelector('a[target=_blank]')!.getAttribute('rel')).toBe('noopener noreferrer');
        expect([...root.querySelectorAll('.actions button')].map(item => item.textContent!.trim()))
            .toEqual(['Отклонить жалобу', 'Скрыть из Сообщества', 'Снять материалы по закону…']);
    });

    it('says overdue in words, with the date, not by colour', async () => {
        await setup(AdminComplaintDetailPageComponent, { complaintId: id });
        http.expectOne(detailUrl).flush(openDeck({ overdue: true, dueAt: '2026-10-08T00:00:00Z' }));
        await settle();
        const warning = root.querySelector('.notice.warning')!;
        expect(warning.textContent).toContain('Просрочена на 3 дня');
        expect(warning.textContent).toContain('Срок рассмотрения вышел');
    });

    it('asks twice before rejecting: the first press sends nothing, «Отмена» goes back, the second sends the decision', async () => {
        await setup(AdminComplaintDetailPageComponent, { complaintId: id });
        http.expectOne(detailUrl).flush(openDeck());
        await settle();
        click('Отклонить жалобу');
        expect(root.querySelector('form .notice.warning')!.textContent).toContain('Отклонить жалобу?');
        expect(root.querySelector('form .notice.warning')!.getAttribute('role')).toBe('alert');
        expect(root.querySelector('form .notice.warning')!.textContent).toContain('Решение попадёт в журнал.');
        click('Отмена');
        expect(root.querySelector('form')).toBeNull();
        click('Отклонить жалобу');
        click('Подтвердить отклонение');
        const request = http.expectOne(`${detailUrl}:decide`);
        expect(request.request.body).toEqual(contract.decide.requests.reject);
        request.flush({ ...openDeck(), status: 'DECIDED', decision: 'REJECTED', decidedAt: '2026-10-11T08:00:00Z', decidedBy: ownerId, changed: true });
        await settle();
        expect(root.querySelector('.notice.success')!.textContent).toBe('Жалоба отклонена и закрыта.');
        expect(root.querySelector('#decision-title')!.nextElementSibling!.textContent).toContain('Жалоба отклонена.');
        expect(root.querySelectorAll('.actions button')).toHaveLength(0);
        expect(document.activeElement === null || root.contains(document.activeElement) || document.activeElement === document.body).toBe(true);
    });

    it('hides the deck after a warning that says what readers, the author and the copies see, then offers to return it', async () => {
        await setup(AdminComplaintDetailPageComponent, { complaintId: id });
        http.expectOne(detailUrl).flush(openDeck());
        await settle();
        click('Скрыть из Сообщества');
        const warning = root.querySelector('form .notice.warning')!.textContent!;
        expect(warning).toContain('Другие читатели перестанут её видеть');
        expect(warning).toContain('Автор получит уведомление');
        expect(warning).toContain('копии других людей останутся');
        click('Подтвердить скрытие');
        const request = http.expectOne(`${detailUrl}:decide`);
        expect(request.request.body).toEqual(contract.decide.requests.hide);
        request.flush({ ...openDeck({}, { hiddenByModeration: true, hiddenAt: '2026-10-11T08:00:00Z' }), status: 'DECIDED', decision: 'HIDDEN', decidedAt: '2026-10-11T08:00:00Z', decidedBy: ownerId, changed: true });
        await settle();
        expect(root.querySelector('.notice.success')!.textContent).toBe('Колода скрыта из Сообщества. Автор получил уведомление.');
        expect(root.textContent).toContain('Скрыта');
        click('Вернуть в Сообщество');
        expect(root.querySelector('form .notice.warning')!.textContent).toContain('Вернуть колоду в Сообщество?');
        click('Подтвердить возврат');
        const unhide = http.expectOne(`${detailUrl}:unhide`);
        expect(unhide.request.body).toBeNull();
        unhide.flush({ ...openDeck({ status: 'DECIDED', decision: 'HIDDEN', decidedAt: '2026-10-11T08:00:00Z' }), changed: true });
        await settle();
        expect(root.querySelector('.notice.success')!.textContent).toBe('Колода возвращена в Сообщество.');
        expect([...root.querySelectorAll('button')].some(item => item.textContent!.includes('Вернуть в Сообщество'))).toBe(false);
    });

    it('says nothing changed when the API answers a repeat', async () => {
        await setup(AdminComplaintDetailPageComponent, { complaintId: id });
        http.expectOne(detailUrl).flush(openDeck());
        await settle();
        click('Отклонить жалобу');
        click('Подтвердить отклонение');
        http.expectOne(`${detailUrl}:decide`).flush({ ...openDeck(), status: 'DECIDED', decision: 'REJECTED', decidedAt: '2026-10-11T08:00:00Z', decidedBy: ownerId, changed: false });
        await settle();
        expect(root.querySelector('.notice.success')!.textContent).toBe('Ничего не изменилось: это решение уже было принято.');
    });

    describe('legal takedown', () => {
        const units = (kind: 'ITEM' | 'EXERCISE', rows: readonly object[], next: string | null = null) => ({ kind, total: rows.length, units: rows, nextCursor: next });
        const item = (index: number, patch: object = {}) => ({ kind: 'ITEM', key: `55555555-5555-4555-8555-00000000000${index}`, revisionId: `66666666-6666-4666-8666-00000000000${index}`,
            ordinal: index - 1, title: `Материал ${index}`, takenDown: false, ...patch });
        const exercise = (index: number) => ({ kind: 'EXERCISE', key: `77777777-7777-4777-8777-00000000000${index}`, revisionId: `88888888-8888-4888-8888-00000000000${index}`,
            ordinal: index - 1, type: 'FREE_RESPONSE', prompt: 'Как сказать «где вокзал»?', takenDown: false });
        const unitUrl = (kind: string, cursor?: string) => (request: { url: string; params: { get(name: string): string | null } }) =>
            request.url === `${detailUrl}/units` && request.params.get('kind') === kind && request.params.get('limit') === '50' && request.params.get('cursor') === (cursor ?? null);

        async function openPicker(): Promise<void> {
            await setup(AdminComplaintDetailPageComponent, { complaintId: id });
            http.expectOne(detailUrl).flush(openDeck());
            await settle();
            click('Снять материалы по закону');
            http.expectOne(unitUrl('ITEM')).flush(units('ITEM', [item(1), item(2, { takenDown: true }), item(3)]));
            http.expectOne(unitUrl('EXERCISE')).flush(units('EXERCISE', [exercise(1)]));
            await settle();
        }
        const boxes = (): HTMLInputElement[] => [...root.querySelectorAll<HTMLInputElement>('app-admin-takedown-picker input[type=checkbox]')];

        it('lists the published materials and exercises, marks the ones already taken down and keeps the confirmation disabled until something is chosen', async () => {
            await openPicker();
            expect(root.querySelector('app-admin-takedown-picker h3')!.textContent).toBe('Что снять');
            expect([...root.querySelectorAll('app-admin-takedown-picker fieldset.check-group > legend')].map(legend => legend.textContent)).toEqual(['Материалы колоды', 'Упражнения колоды']);
            expect(root.querySelector('app-admin-takedown-picker fieldset ul')!.hasAttribute('aria-label')).toBe(false);
            expect(root.querySelector('app-admin-takedown-picker')!.textContent).toContain('перестанут показываться везде');
            expect([...root.querySelectorAll('app-admin-takedown-picker ul')[0].querySelectorAll('li')].map(row => row.textContent!.replace(/\s+/g, ' ').trim()))
                .toEqual(['1. Материал 1', '2. Материал 2 Уже снято', '3. Материал 3']);
            expect(boxes()[1].disabled).toBe(true);
            expect(root.querySelectorAll('app-admin-takedown-picker ul')[1].textContent).toContain('Ввести ответ: Как сказать «где вокзал»?');
            expect(button('Подтвердить снятие').getAttribute('aria-disabled')).toBe('true');
            expect(root.querySelector('app-admin-takedown-picker .summary')!.textContent).toBe('Пока ничего не отмечено.');
            expect([...root.querySelectorAll('app-segmented-choice label')].map(label => label.textContent!.trim())).toEqual(['Материалы · 3', 'Упражнения · 1']);
        });

        it('sends exactly the revisions that were listed, with «hide» when asked, after a warning that counts them', async () => {
            await openPicker();
            boxes()[0].click();
            boxes()[2].click();
            fixture.detectChanges();
            // the exercise list lives behind the same segmented choice
            [...root.querySelectorAll<HTMLInputElement>('app-segmented-choice input')].find(radio => radio.closest('label')!.textContent!.includes('Упражнения'))!.click();
            fixture.detectChanges();
            boxes().find(box => box.closest('li')!.textContent!.includes('Ввести ответ'))!.click();
            fixture.detectChanges();
            expect(root.querySelector('app-admin-takedown-picker .summary')!.textContent).toBe('Отмечено: 2 материала и 1 упражнение.');
            expect(root.querySelector('form .notice.warning')!.textContent).toContain('Снять по закону: 2 материала и 1 упражнение?');
            expect(root.querySelector('form .notice.warning')!.hasAttribute('role')).toBe(false);
            expect(root.querySelector('form .notice.warning')!.textContent).toContain('нельзя отменить в кабинете');
            [...root.querySelectorAll<HTMLInputElement>('form > label.check-row input')][0].click();
            fixture.detectChanges();
            click('Подтвердить снятие');
            const request = http.expectOne(`${detailUrl}:decide`);
            expect(request.request.body).toEqual({
                decision: 'TAKEN_DOWN', hide: true,
                units: [{ kind: 'ITEM', key: item(1).key, revisionId: item(1).revisionId }, { kind: 'ITEM', key: item(3).key, revisionId: item(3).revisionId },
                    { kind: 'EXERCISE', key: exercise(1).key, revisionId: exercise(1).revisionId }]
            });
            request.flush({ ...openDeck({}, { hiddenByModeration: true, hiddenAt: '2026-10-11T08:00:00Z' }), status: 'DECIDED', decision: 'TAKEN_DOWN', decidedAt: '2026-10-11T08:00:00Z',
                decidedBy: ownerId, takedowns: base.takedowns, changed: true });
            await settle();
            expect(root.querySelector('.notice.success')!.textContent).toBe('Материалы сняты. Автор получил уведомление.');
            expect(root.querySelector('#takedowns-title')!.textContent).toBe('Снято по этой жалобе · 2');
            expect(root.querySelector('app-admin-takedown-picker')).toBeNull();
            expect(button('Добавить к снятию').textContent).toContain('Добавить к снятию…');
        });

        it('keeps the choice when the reader switches between materials and exercises, and does not offer «hide» again for a decided complaint', async () => {
            await openPicker();
            boxes()[0].click();
            const radios = [...root.querySelectorAll<HTMLInputElement>('app-segmented-choice input')];
            radios[1].click();
            fixture.detectChanges();
            radios[0].click();
            fixture.detectChanges();
            expect(boxes()[0].checked).toBe(true);
            expect(root.querySelector('app-admin-takedown-picker .summary')!.textContent).toBe('Отмечено: 1 материал.');
        });

        it('continues each list with its own cursor', async () => {
            await setup(AdminComplaintDetailPageComponent, { complaintId: id });
            http.expectOne(detailUrl).flush(openDeck());
            await settle();
            click('Снять материалы по закону');
            http.expectOne(unitUrl('ITEM')).flush(units('ITEM', [item(1)], 'c2'));
            http.expectOne(unitUrl('EXERCISE')).flush(units('EXERCISE', []));
            await settle();
            fixture.debugElement.query(By.css('app-admin-takedown-picker app-auto-load')).triggerEventHandler('loadNext');
            http.expectOne(unitUrl('ITEM', 'c2')).flush(units('ITEM', [item(1), item(3)]));
            await settle();
            expect(boxes()).toHaveLength(2);
            expect(root.querySelectorAll('app-admin-takedown-picker ul')[1].textContent).toBe('');
            expect(root.querySelector('app-admin-takedown-picker .empty-state')!.textContent).toContain('нет упражнений');
        });
    });

    it('treats a decided complaint as closed except for more takedown units, and does not offer «Вернуть» for a hide that a takedown made', async () => {
        await setup(AdminComplaintDetailPageComponent, { complaintId: id });
        http.expectOne(detailUrl).flush(base);
        await settle();
        expect(base.deck.hiddenByModeration).toBe(true);
        expect([...root.querySelectorAll('.actions button')].map(item => item.textContent!.trim())).toEqual(['Добавить к снятию…']);
        expect(root.querySelector('#decision-title')!.nextElementSibling!.textContent).toContain('Материалы сняты по закону.');
        expect(root.querySelector('.hint:last-of-type, section .hint')).not.toBeNull();
        expect(root.textContent).toContain('Колода скрыта, но не этой жалобой');
        expect(root.querySelectorAll('tbody tr')).toHaveLength(2);
        expect(root.querySelector('tbody')!.textContent).toContain('Материал');
        expect(root.querySelector('tbody')!.textContent).toContain('Упражнение');
        expect(root.textContent).toContain('120 материалов, 85 упражнений');
    });

    it('offers «Вернуть в Сообщество» only for a complaint decided HIDDEN while the deck is hidden', async () => {
        const hiddenBy = (decision: string, hidden: boolean) => ({ ...openDeck({}, { hiddenByModeration: hidden, hiddenAt: hidden ? '2026-10-11T08:00:00Z' : null }), status: 'DECIDED', decision,
            decidedAt: '2026-10-11T08:00:00Z', decidedBy: ownerId });
        const unhideOffered = async (value: object) => {
            await setup(AdminComplaintDetailPageComponent, { complaintId: id });
            http.expectOne(detailUrl).flush(value);
            await settle();
            const offered = [...root.querySelectorAll('button')].some(item => item.textContent!.includes('Вернуть в Сообщество'));
            fixture.destroy(); root.remove(); TestBed.resetTestingModule();
            return offered;
        };
        expect(await unhideOffered(hiddenBy('HIDDEN', true))).toBe(true);
        expect(await unhideOffered(hiddenBy('HIDDEN', false))).toBe(false);
        expect(await unhideOffered(hiddenBy('REJECTED', true))).toBe(false);
        expect(await unhideOffered(hiddenBy('TAKEN_DOWN', true))).toBe(false);
        expect(await unhideOffered(openDeck({}, { hiddenByModeration: true, hiddenAt: '2026-10-11T08:00:00Z' }))).toBe(false);
    });

    it('says an open complaint of an already hidden deck can still decide «Скрыть», to lift the hide later through it', async () => {
        await setup(AdminComplaintDetailPageComponent, { complaintId: id });
        http.expectOne(detailUrl).flush(openDeck({}, { hiddenByModeration: true, hiddenAt: '2026-10-11T08:00:00Z' }));
        await settle();
        expect([...root.querySelectorAll('.actions button')].map(item => item.textContent!.trim())).toContain('Скрыть из Сообщества');
        expect(root.textContent).toContain('Колода уже скрыта. Решение «Скрыть из Сообщества» на этой жалобе нужно, чтобы потом вернуть колоду через неё.');
    });

    it('gives a plain message and reloads when the API answers 409 COMPLAINT_NOT_HIDDEN to «Вернуть»', async () => {
        await setup(AdminComplaintDetailPageComponent, { complaintId: id });
        const hidden = { ...openDeck({}, { hiddenByModeration: true, hiddenAt: '2026-10-11T08:00:00Z' }), status: 'DECIDED', decision: 'HIDDEN', decidedAt: '2026-10-11T08:00:00Z', decidedBy: ownerId };
        http.expectOne(detailUrl).flush(hidden);
        await settle();
        click('Вернуть в Сообщество');
        click('Подтвердить возврат');
        http.expectOne(`${detailUrl}:unhide`).flush({ code: 'COMPLAINT_NOT_HIDDEN' }, { status: 409, statusText: 'Conflict' });
        await settle();
        expect(root.querySelector('.notice.error')!.textContent).toBe('Вернуть колоду не получилось: по этой жалобе она не скрыта. Загружаем жалобу заново.');
        expect(root.querySelector('.notice.error')!.textContent).not.toMatch(/409|COMPLAINT/u);
        http.expectOne(detailUrl).flush({ ...hidden, deck: { ...hidden.deck, hiddenByModeration: false, hiddenAt: null } });
        await settle();
        expect([...root.querySelectorAll('button')].some(item => item.textContent!.includes('Вернуть в Сообщество'))).toBe(false);
        expect(document.activeElement).toBe(root.querySelector('#decision-title'));
    });

    describe('focus', () => {
        it('moves to the warning when a decision is asked for, and back to the button that was pressed on «Отмена»', async () => {
            await setup(AdminComplaintDetailPageComponent, { complaintId: id });
            http.expectOne(detailUrl).flush(openDeck());
            await settle();
            const pressed = button('Скрыть из Сообщества');
            pressed.focus();
            pressed.click();
            fixture.detectChanges();
            expect(document.activeElement).toBe(root.querySelector('#confirm-warning'));
            expect(root.querySelector('#confirm-warning')!.getAttribute('tabindex')).toBe('-1');
            click('Отмена');
            expect(document.activeElement).toBe(root.querySelector('[data-action="HIDDEN"]'));
        });

        it('moves to the warning of a takedown too, which the picker does not replace', async () => {
            await setup(AdminComplaintDetailPageComponent, { complaintId: id });
            http.expectOne(detailUrl).flush(openDeck());
            await settle();
            click('Снять материалы по закону');
            http.expectOne(request => request.url === `${detailUrl}/units` && request.params.get('kind') === 'ITEM').flush({ kind: 'ITEM', total: 0, units: [], nextCursor: null });
            http.expectOne(request => request.url === `${detailUrl}/units` && request.params.get('kind') === 'EXERCISE').flush({ kind: 'EXERCISE', total: 0, units: [], nextCursor: null });
            await settle();
            expect(document.activeElement).toBe(root.querySelector('#confirm-warning'));
            click('Отмена');
            expect(document.activeElement).toBe(root.querySelector('[data-action="TAKEN_DOWN"]'));
        });

        it('lands on the result, a status region that is always on the page, after a decision', async () => {
            await setup(AdminComplaintDetailPageComponent, { complaintId: id });
            http.expectOne(detailUrl).flush(openDeck());
            await settle();
            const region = root.querySelector<HTMLElement>('#decision-status')!;
            expect(region.getAttribute('role')).toBe('status');
            expect(region.textContent).toBe('');
            click('Отклонить жалобу');
            click('Подтвердить отклонение');
            http.expectOne(`${detailUrl}:decide`).flush({ ...openDeck(), status: 'DECIDED', decision: 'REJECTED', decidedAt: '2026-10-11T08:00:00Z', decidedBy: ownerId, changed: true });
            await settle();
            expect(root.querySelector('#decision-status')).toBe(region);
            expect(region.textContent).toContain('Жалоба отклонена и закрыта.');
            expect(document.activeElement).toBe(region);
        });

        it('lands on the decision heading after a 409, because the buttons that were pressed are gone', async () => {
            await setup(AdminComplaintDetailPageComponent, { complaintId: id });
            http.expectOne(detailUrl).flush(openDeck());
            await settle();
            click('Скрыть из Сообщества');
            click('Подтвердить скрытие');
            http.expectOne(`${detailUrl}:decide`).flush({ code: 'COMPLAINT_DECIDED' }, { status: 409, statusText: 'Conflict' });
            await settle();
            http.expectOne(detailUrl).flush(base);
            await settle();
            expect(document.activeElement).toBe(root.querySelector('#decision-title'));
        });
    });

    it('shows a profile complaint with the way to the account directory and a hand-off instead of hiding', async () => {
        const profileId = second.complaintId;
        await setup(AdminComplaintDetailPageComponent, { complaintId: profileId });
        http.expectOne(`/api/admin/complaints/${profileId}`).flush(openProfile());
        await settle();
        expect(root.textContent).toContain('@ivan.k');
        expect(root.querySelector<HTMLAnchorElement>(`a[href="/manage/users/${second.profileAccountId}"]`)).not.toBeNull();
        expect([...root.querySelectorAll('.actions button')].map(item => item.textContent!.trim())).toEqual(['Отклонить жалобу', 'Продолжить в разделе «Пользователи»']);
        click('Продолжить в разделе «Пользователи»');
        expect(root.querySelector('form .notice.warning')!.textContent).toContain('блокировку или скрытие профиля вы делаете там');
        click('Подтвердить и закрыть жалобу');
        const request = http.expectOne(`/api/admin/complaints/${profileId}:decide`);
        expect(request.request.body).toEqual(contract.decide.requests.referAccount);
        request.flush({ ...openProfile(), status: 'DECIDED', decision: 'ACCOUNT_REFERRED', decidedAt: '2026-10-11T08:00:00Z', decidedBy: ownerId, changed: true });
        await settle();
        expect(root.querySelector('.notice.success')!.textContent).toBe('Жалоба закрыта. Продолжите в разделе «Пользователи».');
    });

    it('reloads the complaint when it was decided in the meantime (409) and tells the owner', async () => {
        await setup(AdminComplaintDetailPageComponent, { complaintId: id });
        http.expectOne(detailUrl).flush(openDeck());
        await settle();
        click('Скрыть из Сообщества');
        click('Подтвердить скрытие');
        http.expectOne(`${detailUrl}:decide`).flush({ code: 'COMPLAINT_DECIDED' }, { status: 409, statusText: 'Conflict' });
        await settle();
        expect(root.querySelector('.notice.error')!.textContent).toContain('Жалоба уже решена другим решением');
        http.expectOne(detailUrl).flush(base);
        await settle();
        expect(root.querySelector('form')).toBeNull();
        expect(root.querySelector('#decision-title')!.nextElementSibling!.textContent).toContain('Материалы сняты по закону.');
    });

    it('keeps the confirmation open after an unknown outcome and may send the same decision again', async () => {
        await setup(AdminComplaintDetailPageComponent, { complaintId: id });
        http.expectOne(detailUrl).flush(openDeck());
        await settle();
        click('Отклонить жалобу');
        click('Подтвердить отклонение');
        http.expectOne(`${detailUrl}:decide`).flush({}, { status: 503, statusText: 'Unavailable' });
        await settle();
        expect(root.querySelector('.notice.error')!.textContent).toContain('Результат не подтверждён');
        expect(root.querySelector('form')).not.toBeNull();
        click('Подтвердить отклонение');
        http.expectOne(`${detailUrl}:decide`).flush({ ...openDeck(), status: 'DECIDED', decision: 'REJECTED', decidedAt: '2026-10-11T08:00:00Z', decidedBy: ownerId, changed: true });
        await settle();
        expect(root.querySelector('.notice.success')).not.toBeNull();
        expect(root.querySelector('.notice.error')).toBeNull();
    });

    it('does not send twice while a decision is on its way, and protects the page from closing', async () => {
        await setup(AdminComplaintDetailPageComponent, { complaintId: id });
        http.expectOne(detailUrl).flush(openDeck());
        await settle();
        click('Отклонить жалобу');
        click('Подтвердить отклонение');
        fixture.detectChanges();
        expect(button('Выполняем…').getAttribute('aria-busy')).toBe('true');
        click('Выполняем…');
        const event = new Event('beforeunload', { cancelable: true });
        window.dispatchEvent(event);
        expect(event.defaultPrevented).toBe(true);
        const request = http.expectOne(`${detailUrl}:decide`);
        request.flush({ ...openDeck(), status: 'DECIDED', decision: 'REJECTED', decidedAt: '2026-10-11T08:00:00Z', decidedBy: ownerId, changed: true });
        await settle();
    });

    it('says the complaint is not found for an address that is no complaint or a 404, without a request for the first', async () => {
        await setup(AdminComplaintDetailPageComponent, { complaintId: 'not-a-uuid' });
        await settle();
        expect(root.textContent).toContain('Такой жалобы нет');
        params.next(convertToParamMap({ complaintId: id }));
        http.expectOne(detailUrl).flush({}, { status: 404, statusText: 'Not Found' });
        await settle();
        expect(root.textContent).toContain('Такой жалобы нет');
    });

    it('clears the private view after a forbidden answer and offers a retry for an outage', async () => {
        await setup(AdminComplaintDetailPageComponent, { complaintId: id });
        http.expectOne(detailUrl).flush({}, { status: 500, statusText: 'x' });
        await settle();
        expect(root.querySelector('[role=alert]')!.textContent).toContain('Не удалось загрузить жалобу');
        click('Повторить');
        http.expectOne(detailUrl).flush(openDeck());
        await settle();
        click('Отклонить жалобу');
        click('Подтвердить отклонение');
        http.expectOne(`${detailUrl}:decide`).flush({}, { status: 403, statusText: 'Forbidden' });
        await settle();
        expect(root.querySelector('h2#complaint-facts')).toBeNull();
        expect(TestBed.inject(AdminSession).access()).toBeNull();
    });
});

describe('takedown journal', () => {
    const journalUrl = (cursor?: string) => (request: { url: string; params: { get(name: string): string | null } }) =>
        request.url === '/api/admin/complaints/journal' && request.params.get('limit') === '50' && request.params.get('cursor') === (cursor ?? null);

    it('lists every decision with its action in words, the actor and the time, newest first', async () => {
        await setup(AdminComplaintJournalPageComponent);
        const unhidden = { ...clone(contract.journal.answer.items[0]), journalId: '0199c478-d000-7000-8000-000000000002', action: 'UNHIDDEN', unitCount: 0 };
        http.expectOne(journalUrl()).flush({ items: [...contract.journal.answer.items, unhidden], nextCursor: null });
        await settle();
        const rows = [...root.querySelectorAll('tbody tr')].map(row => row.textContent!.replace(/\s+/g, ' ').trim());
        expect(rows).toHaveLength(2);
        expect(rows[0]).toContain('Материалы сняты по закону');
        expect(rows[0]).toContain('Новых снятий: 2');
        expect(rows[0]).toContain('Код Kq7xT3mNpR');
        expect(rows[0]).toContain(contract.journal.answer.items[0].actorId);
        expect(rows[1]).toContain('Колода возвращена в Сообщество');
        expect(root.querySelector<HTMLAnchorElement>('tbody th a')!.getAttribute('href')).toBe(`/manage/complaints/${first.complaintId}`);
        expect(root.querySelector('h1')!.textContent).toBe('Журнал решений по жалобам');
    });

    it('continues by itself and says when nothing has been decided yet', async () => {
        await setup(AdminComplaintJournalPageComponent);
        http.expectOne(journalUrl()).flush({ items: contract.journal.answer.items, nextCursor: 'j2' });
        await settle();
        fixture.debugElement.query(By.css('app-auto-load')).triggerEventHandler('loadNext');
        http.expectOne(journalUrl('j2')).flush({ items: [], nextCursor: null });
        await settle();
        expect(root.querySelectorAll('tbody tr')).toHaveLength(1);
        click('Обновить');
        http.expectOne(journalUrl()).flush({ items: [], nextCursor: null });
        await settle();
        expect(root.querySelector('tbody')!.textContent).toContain('Решений пока нет.');
    });

    it('offers a retry when the journal cannot be read', async () => {
        await setup(AdminComplaintJournalPageComponent);
        http.expectOne(journalUrl()).flush({}, { status: 500, statusText: 'x' });
        await settle();
        expect(root.querySelector('[role=alert]')!.textContent).toContain('Не удалось загрузить журнал');
    });
});
