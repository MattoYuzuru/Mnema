import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';

import catalog from '../../../../../contracts/community/catalog.json';
import { AuthService } from '../../auth.service';
import { AdminSession } from './admin-api.service';
import { AdminCommunityPageComponent, canLeaveAdminCommunity } from './admin-community-page.component';
import { clone, ownerId } from './admin-test-data';

const A = 'Kq7xT3mNpR', B = 'Zp4WcA8vQe', C = 'Mn5bRt2yHd';
const entry = (code: string, title: string, listed = true) => ({ code, title, listed, addedAt: '2026-10-12T10:00:00Z' });
const shelf = (rowVersion = '3', entries = [entry(A, 'Испанский язык: базовые глаголы'), entry(B, 'Столицы мира', false), entry(C, 'Японские числа')]) => ({ rowVersion, entries });
const flags = catalog.anomalies.list.response;

let http: HttpTestingController;
let fixture: ComponentFixture<AdminCommunityPageComponent>;
let root: HTMLElement;

const shelfRead = (): TestRequest => http.expectOne(request => request.method === 'GET' && request.url === '/api/admin/community/editorial');
const flagsRead = (): TestRequest => http.expectOne(request => request.method === 'GET' && request.url === '/api/admin/community/anomalies');

async function settle(): Promise<void> {
    for (let round = 0; round < 6; round++) await Promise.resolve();
    fixture.detectChanges();
}
async function setup(state: object = shelf(), flagPage: object = flags): Promise<void> {
    TestBed.configureTestingModule({
        providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([]),
            { provide: AuthService, useValue: { status: signal('authenticated'), user: () => ({ accountId: ownerId }) } }]
    });
    TestBed.inject(AdminSession).access.set({ owner: true, permissions: { events: true, moderation: true, promos: true, support: true } });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AdminCommunityPageComponent);
    root = fixture.nativeElement as HTMLElement;
    document.body.append(root);
    fixture.detectChanges();
    shelfRead().flush(state);
    flagsRead().flush(flagPage);
    await settle();
}
const rowOf = (code: string): HTMLElement => root.querySelector<HTMLElement>(`.shelf-list li[data-code="${code}"]`)!;
const codes = (): string[] => [...root.querySelectorAll('.shelf-list li')].map(row => row.getAttribute('data-code')!);
const action = (code: string, name: 'up' | 'down' | 'remove'): HTMLButtonElement => rowOf(code).querySelector<HTMLButtonElement>(`[data-action="${name}"]`)!;
const saveButton = (): HTMLButtonElement => [...root.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent!.trim() === 'Сохранить')!;
const status = (): string => root.querySelector('.status-region')!.textContent!.trim();
async function press(button: HTMLElement): Promise<void> { button.click(); await settle(); }
async function add(text: string): Promise<void> {
    const input = root.querySelector<HTMLInputElement>('#shelf-add-input')!;
    input.value = text;
    input.dispatchEvent(new Event('input'));
    root.querySelector('form.shelf-add')!.dispatchEvent(new Event('submit', { cancelable: true }));
    await settle();
}
const putRequest = (): TestRequest => http.expectOne(request => request.method === 'PUT' && request.url === '/api/admin/community/editorial');

afterEach(() => { http?.verify(); root?.remove(); fixture?.destroy(); vi.restoreAllMocks(); });

describe('owner console community page: «Выбор Мнемы»', () => {
    it('lists the shelf in order with the code, a stamp for a deck outside the catalogue and disabled ends', async () => {
        await setup();
        expect(root.querySelector('h1')!.textContent).toBe('Сообщество');
        expect(codes()).toEqual([A, B, C]);
        expect(rowOf(A).textContent).toContain('Испанский язык: базовые глаголы');
        expect(rowOf(A).textContent).toContain(A);
        expect(rowOf(A).querySelector('.stamp')).toBeNull();
        expect(rowOf(B).querySelector('.stamp')!.textContent).toBe('Не в каталоге');
        expect(action(A, 'up').getAttribute('aria-disabled')).toBe('true');
        expect(action(A, 'down').getAttribute('aria-disabled')).toBeNull();
        expect(action(C, 'down').getAttribute('aria-disabled')).toBe('true');
        expect(action(B, 'up').getAttribute('aria-label')).toBe('Выше: Столицы мира');
        expect(action(B, 'remove').getAttribute('aria-label')).toBe('Убрать: Столицы мира');
        expect(saveButton().getAttribute('aria-disabled')).toBe('true');
    });

    it('moves a row with the buttons, keeps focus on the pressed button at the new place and announces the place', async () => {
        await setup();
        await press(action(B, 'up'));
        expect(codes()).toEqual([B, A, C]);
        expect(document.activeElement).toBe(action(B, 'up'));
        expect(action(B, 'up').getAttribute('aria-disabled')).toBe('true');
        expect(status()).toBe('Столицы мира: место 1 из 3.');
        await press(action(B, 'down'));
        expect(codes()).toEqual([A, B, C]);
        expect(root.textContent).not.toContain('Есть несохранённые изменения');
        expect(saveButton().getAttribute('aria-disabled')).toBe('true');
    });

    it('does nothing when an aria-disabled end button is pressed', async () => {
        await setup();
        await press(action(A, 'up'));
        await press(action(C, 'down'));
        expect(codes()).toEqual([A, B, C]);
        expect(status()).toBe('');
    });

    it('saves the whole list with If-Match and a command id, then reads the actual list', async () => {
        await setup();
        await press(action(C, 'up'));
        expect(saveButton().getAttribute('aria-disabled')).toBeNull();
        expect(root.textContent).toContain('Есть несохранённые изменения');
        await press(saveButton());
        const request = putRequest();
        expect(request.request.headers.get('If-Match')).toBe('"3"');
        expect(request.request.body.codes).toEqual([A, C, B]);
        expect(request.request.body.commandId).toMatch(/^[\da-f]{8}(?:-[\da-f]{4}){3}-[\da-f]{12}$/u);
        expect(saveButton().getAttribute('aria-busy')).toBe('true');
        expect(action(A, 'down').getAttribute('aria-disabled')).toBe('true');
        request.flush(null, { status: 204, statusText: 'No Content' });
        await settle();
        expect(status()).toBe('Сохранено');
        shelfRead().flush(shelf('4', [entry(A, 'Испанский язык: базовые глаголы'), entry(C, 'Японские числа'), entry(B, 'Столицы мира', false)]));
        await settle();
        expect(codes()).toEqual([A, C, B]);
        expect(status()).toBe('Сохранено');
        expect(saveButton().getAttribute('aria-disabled')).toBe('true');
        await press(action(B, 'up'));
        await press(saveButton());
        expect(putRequest().request.headers.get('If-Match')).toBe('"4"');
    });

    it('says «Нечего сохранять» when Сохранить is pressed on a clean list, and sends nothing', async () => {
        await setup();
        expect(root.querySelectorAll('.status-region')).toHaveLength(2);
        await press(saveButton());
        expect(status()).toBe('Нечего сохранять');
        await press(action(C, 'up'));
        expect(status()).not.toBe('Нечего сохранять');
    });

    it('adds a deck by a public link or a bare code, at the end, and refuses what is not a deck or already there', async () => {
        await setup();
        await add('https://mnema.app/d/Hd9Ts5pLwX/ispanskii');
        expect(codes()).toEqual([A, B, C, 'Hd9Ts5pLwX']);
        expect(rowOf('Hd9Ts5pLwX').textContent).toContain('Колода Hd9Ts5pLwX');
        expect(rowOf('Hd9Ts5pLwX').textContent).toContain('название появится после сохранения');
        expect(root.querySelector<HTMLInputElement>('#shelf-add-input')!.value).toBe('');
        expect(status()).toContain('Hd9Ts5pLwX добавлена в конец списка');
        await add(A);
        expect(root.querySelector('#shelf-add-error')!.textContent).toBe('Эта колода уже на полке.');
        expect(root.querySelector('#shelf-add-error')!.getAttribute('role')).toBe('alert');
        expect(root.querySelector('#shelf-add-input')!.getAttribute('aria-invalid')).toBe('true');
        expect(root.querySelector('#shelf-add-input')!.getAttribute('aria-describedby')).toBe('shelf-add-hint shelf-add-error');
        await add('привет');
        expect(root.querySelector('#shelf-add-error')!.textContent).toContain('Введите ссылку на колоду');
        await add('Kq7xT3mNp0');
        expect(root.querySelector('#shelf-add-error')!.textContent).toContain('Введите ссылку на колоду');
        expect(codes()).toHaveLength(4);
        root.querySelector<HTMLInputElement>('#shelf-add-input')!.dispatchEvent(new Event('input'));
        await settle();
        expect(root.querySelector('#shelf-add-error')).toBeNull();
    });

    it('allows at most 24 decks: add is aria-disabled with the reason, and removing one frees it', async () => {
        const many = Array.from({ length: 24 }, (_, index) => entry(`Kq7xT3m${String.fromCharCode(65 + index % 8)}${(index % 9) + 1}${String.fromCharCode(97 + Math.floor(index / 3))}`, `Колода ${index + 1}`));
        await setup(shelf('9', many));
        const submit = root.querySelector<HTMLButtonElement>('form.shelf-add button[type=submit]')!;
        expect(submit.getAttribute('aria-disabled')).toBe('true');
        expect(root.querySelector('#shelf-add-hint')!.textContent).toBe('На полке уже 24 колод. Уберите одну, чтобы добавить другую.');
        await add('Hd9Ts5pLwX');
        expect(codes()).toHaveLength(24);
        expect(root.querySelector('#shelf-add-error')!.textContent).toBe('На полке уже 24 колод. Уберите одну, чтобы добавить другую.');
        expect(root.querySelector('#shelf-add-error')!.getAttribute('role')).toBe('alert');
        await press(action(many[0].code, 'remove'));
        expect(codes()).toHaveLength(23);
        expect(submit.getAttribute('aria-disabled')).toBeNull();
        await add('Hd9Ts5pLwX');
        expect(codes()).toHaveLength(24);
    });

    it('removes a row, moves focus to the neighbour and falls back to the field when the list is empty', async () => {
        await setup(shelf('3', [entry(A, 'Первая'), entry(B, 'Вторая')]));
        await press(action(A, 'remove'));
        expect(codes()).toEqual([B]);
        expect(document.activeElement).toBe(action(B, 'remove'));
        expect(status()).toBe('Первая: убрана из списка. Нажмите «Сохранить».');
        await press(action(B, 'remove'));
        expect(codes()).toEqual([]);
        expect(document.activeElement).toBe(root.querySelector('#shelf-add-input'));
        expect(root.textContent).toContain('На полке пока нет колод.');
        await press(saveButton());
        expect(putRequest().request.body.codes).toEqual([]);
    });

    it('412 reloads the actual list, drops the edits and says so', async () => {
        await setup();
        await press(action(C, 'up'));
        await press(saveButton());
        putRequest().flush({ code: 'VERSION_CONFLICT' }, { status: 412, statusText: 'Precondition Failed' });
        await settle();
        shelfRead().flush(shelf('4', [entry(C, 'Японские числа'), entry(A, 'Испанский язык: базовые глаголы')]));
        await settle();
        expect(codes()).toEqual([C, A]);
        expect(root.querySelector('.notice.warning')!.textContent).toContain('Список изменился, вот актуальный. Повторите изменения.');
        expect(saveButton().getAttribute('aria-disabled')).toBe('true');
    });

    it('400 marks the named rows and focuses the first of them; a new edit starts a new command', async () => {
        await setup();
        await press(action(C, 'up'));
        await press(saveButton());
        const first = putRequest();
        first.flush({ code: 'INVALID_REQUEST', codes: [A] }, { status: 400, statusText: 'Bad Request' });
        await settle();
        expect(rowOf(A).querySelector('.field-error')!.textContent).toContain('не публичная или не опубликована');
        expect(rowOf(B).querySelector('.field-error')).toBeNull();
        expect(action(A, 'remove').getAttribute('aria-describedby')).toBe(rowOf(A).querySelector('.field-error')!.id);
        expect(root.querySelector('#shelf-save-error')!.textContent).toContain('отмеченные колоды');
        expect(document.activeElement).toBe(action(A, 'remove'));
        expect(saveButton().getAttribute('aria-disabled')).toBeNull();
        await press(action(A, 'remove'));
        expect(root.querySelector('.field-error')).toBeNull();
        await press(saveButton());
        const second = putRequest();
        expect(second.request.body.commandId).not.toBe(first.request.body.commandId);
        expect(second.request.body.codes).toEqual([C, B]);
        second.flush(null, { status: 204, statusText: 'No Content' });
        await settle();
        shelfRead().flush(shelf('5', [entry(C, 'Японские числа'), entry(B, 'Столицы мира', false)]));
        await settle();
    });

    it('400 without codes asks to check the links and moves focus to the message', async () => {
        await setup();
        await press(action(C, 'up'));
        await press(saveButton());
        putRequest().flush({ code: 'INVALID_REQUEST' }, { status: 400, statusText: 'Bad Request' });
        await settle();
        expect(root.querySelector('#shelf-save-error')!.textContent).toContain('сервер не принял одну из колод');
        expect(document.activeElement).toBe(root.querySelector('#shelf-save-error'));
    });

    it('repeats the same command after an unknown outcome, and a changed list is a new one', async () => {
        await setup();
        await press(action(C, 'up'));
        await press(saveButton());
        const first = putRequest();
        first.flush(null, { status: 503, statusText: 'Unavailable' });
        await settle();
        expect(root.querySelector('#shelf-save-error')!.textContent).toContain('Результат не подтверждён');
        await press(saveButton());
        const repeat = putRequest();
        expect(repeat.request.body.commandId).toBe(first.request.body.commandId);
        repeat.flush(null, { status: 503, statusText: 'Unavailable' });
        await settle();
        await press(action(A, 'down'));
        await press(saveButton());
        const changed = putRequest();
        expect(changed.request.body.commandId).not.toBe(first.request.body.commandId);
        changed.flush(null, { status: 409, statusText: 'Conflict' });
        await settle();
        expect(root.querySelector('#shelf-save-error')!.textContent).toContain('Сервер отклонил команду');
        await press(saveButton());
        expect(putRequest().request.body.commandId).not.toBe(changed.request.body.commandId);
    });

    it('says a failed read and retries it; a forbidden read closes the list', async () => {
        TestBed.configureTestingModule({
            providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([]),
                { provide: AuthService, useValue: { status: signal('authenticated'), user: () => ({ accountId: ownerId }) } }]
        });
        TestBed.inject(AdminSession).access.set({ owner: true, permissions: { events: true, moderation: true, promos: true, support: true } });
        http = TestBed.inject(HttpTestingController);
        fixture = TestBed.createComponent(AdminCommunityPageComponent);
        root = fixture.nativeElement as HTMLElement;
        document.body.append(root);
        fixture.detectChanges();
        shelfRead().flush(null, { status: 500, statusText: 'Error' });
        flagsRead().flush(flags);
        await settle();
        expect(root.querySelector('.notice.error')!.textContent).toContain('Не удалось загрузить полку');
        expect(root.querySelector('.shelf-list')).toBeNull();
        root.querySelector<HTMLButtonElement>('.notice.error button')!.click();
        shelfRead().flush(shelf());
        await settle();
        expect(codes()).toEqual([A, B, C]);
    });

    it('refuses a malformed read as an error, never as an empty shelf', async () => {
        await setup({ rowVersion: '3', entries: [{ code: 'x' }] });
        expect(root.querySelector('.notice.error')!.textContent).toContain('Не удалось загрузить полку');
        expect(root.querySelector('.shelf-list')).toBeNull();
    });

    it('refuses to leave while saving, says so in the status and puts the focus on Сохранить', async () => {
        await setup();
        await press(action(C, 'up'));
        await press(saveButton());
        const confirm = vi.spyOn(window, 'confirm');
        expect(canLeaveAdminCommunity(fixture.componentInstance)).toBe(false);
        fixture.detectChanges();
        expect(confirm).not.toHaveBeenCalled();
        expect(status()).toBe('Идёт сохранение, подождите');
        expect(document.activeElement).toBe(saveButton());
        putRequest().flush(null, { status: 204, statusText: 'No Content' });
        await settle();
        shelfRead().flush(shelf('4', [entry(A, 'Испанский язык: базовые глаголы'), entry(C, 'Японские числа'), entry(B, 'Столицы мира', false)]));
        await settle();
    });

    it('asks before leaving with unsaved changes, and not otherwise', async () => {
        await setup();
        const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
        expect(canLeaveAdminCommunity(fixture.componentInstance)).toBe(true);
        expect(confirm).not.toHaveBeenCalled();
        await press(action(C, 'up'));
        expect(canLeaveAdminCommunity(fixture.componentInstance)).toBe(false);
        expect(confirm).toHaveBeenCalledWith('Несохранённые изменения полки «Выбор Мнемы» будут отброшены. Покинуть страницу?');
        confirm.mockReturnValue(true);
        expect(canLeaveAdminCommunity(fixture.componentInstance)).toBe(true);
        fixture.detectChanges();
        expect(root.querySelector('.notice.warning')!.textContent).toContain('несохранённые изменения');
        const event = new Event('beforeunload', { cancelable: true });
        window.dispatchEvent(event);
        expect(event.defaultPrevented).toBe(true);
    });
});

describe('owner console community page: «Флаги накруток»', () => {
    it('lists the flags with the numbers in words, the state and a link to the public deck', async () => {
        await setup();
        const rows = [...root.querySelectorAll('.flag-row')].map(row => row.textContent!.replace(/\s+/gu, ' ').trim());
        expect(rows).toHaveLength(2);
        expect(rows[0]).toContain('Испанский язык: базовые глаголы');
        expect(rows[0]).toContain('Копий за 7 дней: 42');
        expect(rows[0].replace(/\u00a0/gu, ' ')).toContain('Новых аккаунтов: 83 %');
        expect(rows[0]).toContain('Открыт');
        expect(rows[0]).toContain('Просмотрено');
        expect(rows[1]).toContain('Просмотрен 6 окт');
        expect(rows[1]).not.toContain('Просмотрено');
        const link = root.querySelector<HTMLAnchorElement>('.flag-row h3 a')!;
        expect(link.getAttribute('href')).toMatch(/\/d\/Kq7xT3mNpR$/u);
        expect(link.target).toBe('_blank');
        expect(link.rel).toBe('noopener noreferrer');
        expect(root.querySelectorAll('.flag-row button')).toHaveLength(1);
        expect(root.querySelector('.flag-row button')!.getAttribute('aria-label')).toBe('Просмотрено: Испанский язык: базовые глаголы');
    });

    it('says «Флагов нет.» for an empty list and renders no empty list element', async () => {
        await setup(shelf(), { entries: [], next: null });
        expect(root.textContent).toContain('Флагов нет.');
        expect(root.querySelector('.flag-row')).toBeNull();
        expect(root.querySelector('.flag-list')).toBeNull();
    });

    it('dismisses a flag with its own command id, shows the state and keeps focus on the row heading', async () => {
        await setup();
        const button = root.querySelector<HTMLButtonElement>('.flag-row button')!;
        button.click();
        fixture.detectChanges();
        expect(button.getAttribute('aria-disabled')).toBe('true');
        const request = http.expectOne(`/api/admin/community/anomalies/${A}:dismiss`);
        expect(request.request.method).toBe('POST');
        expect(request.request.body.commandId).toMatch(/^[\da-f]{8}(?:-[\da-f]{4}){3}-[\da-f]{12}$/u);
        button.click();
        http.expectNone(`/api/admin/community/anomalies/${A}:dismiss`);
        request.flush(null, { status: 204, statusText: 'No Content' });
        await settle();
        const row = root.querySelector('.flag-row')!;
        expect(row.querySelector('button')).toBeNull();
        expect(row.querySelector('.stamp')!.textContent).toContain('Просмотрен');
        expect(row.textContent).not.toContain('Открыт');
        expect(document.activeElement).toBe(row.querySelector('h3'));
        expect(root.querySelectorAll('.status-region')[1].textContent).toBe('Флаг «Испанский язык: базовые глаголы» отмечен просмотренным.');
    });

    it('repeats the same command after an unknown outcome and reports a refusal in the row', async () => {
        await setup();
        const click = async () => { root.querySelector<HTMLButtonElement>('.flag-row button')!.click(); await settle(); };
        await click();
        const first = http.expectOne(`/api/admin/community/anomalies/${A}:dismiss`);
        first.flush(null, { status: 500, statusText: 'Error' });
        await settle();
        const error = root.querySelector('.flag-row .field-error')!;
        expect(error.textContent).toContain('Результат не подтверждён');
        expect(error.id).toBe(`flag-error-${A}`);
        expect(root.querySelector('.flag-row button')!.getAttribute('aria-describedby')).toBe(error.id);
        await click();
        const repeat = http.expectOne(`/api/admin/community/anomalies/${A}:dismiss`);
        expect(repeat.request.body.commandId).toBe(first.request.body.commandId);
        repeat.flush({ code: 'RESOURCE_NOT_FOUND' }, { status: 404, statusText: 'Not Found' });
        await settle();
        expect(root.querySelector('.flag-row .field-error')!.textContent).toContain('Флаг не найден');
        expect(root.querySelector('.flag-row button')).not.toBeNull();
        await click();
        const fresh = http.expectOne(`/api/admin/community/anomalies/${A}:dismiss`);
        expect(fresh.request.body.commandId).not.toBe(first.request.body.commandId);
        fresh.flush(null, { status: 204, statusText: 'No Content' });
        await settle();
        expect(root.querySelector('.flag-row .field-error')).toBeNull();
    });

    it('continues by itself with the cursor and keeps the rows when a page fails', async () => {
        await setup(shelf(), { ...clone(flags), next: 'c2' });
        const more = () => fixture.debugElement.query(By.css('app-auto-load')).triggerEventHandler('loadNext');
        more();
        const failed = http.expectOne(request => request.url === '/api/admin/community/anomalies' && request.params.get('cursor') === 'c2');
        failed.flush(null, { status: 500, statusText: 'Error' });
        await settle();
        expect(root.querySelectorAll('.flag-row')).toHaveLength(2);
        expect(root.textContent).toContain('Следующие флаги не загрузились.');
        more();
        const page = http.expectOne(request => request.url === '/api/admin/community/anomalies' && request.params.get('cursor') === 'c2');
        page.flush({ entries: [{ ...clone(flags.entries[0]), code: C, title: 'Столицы Европы' }, flags.entries[0]], next: null });
        await settle();
        expect(root.querySelectorAll('.flag-row')).toHaveLength(3);
    });
});
