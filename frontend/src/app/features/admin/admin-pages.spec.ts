import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Component, Type, signal } from '@angular/core';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { appConfig } from '../../app.config';
import { AuthService } from '../../auth.service';
import { AdminApiService, AdminSession } from './admin-api.service';
import { AdminAuditPageComponent } from './admin-audit-page.component';
import { AdminPromosPageComponent } from './admin-promos-page.component';
import { AdminReportPageComponent } from './admin-report-page.component';
import { AdminShellComponent } from './admin-shell.component';
import { AdminSupportPageComponent } from './admin-support-page.component';
import { AdminUsersPageComponent } from './admin-users-page.component';
import { clone, ownerId, promo, wire } from './admin-test-data';
let http: HttpTestingController;
let fixture: ComponentFixture<any>;
let root: HTMLElement;
let params: BehaviorSubject<ReturnType<typeof convertToParamMap>>;
let query: BehaviorSubject<ReturnType<typeof convertToParamMap>>;
let authStatus = signal('authenticated');
const identity = `${appConfig.authServerUrl}/api/accounts/admin`;
const ticketId = wire.support.conversation.ticket.id;
@Component({ template: '<h1 tabindex="-1">Пользователи</h1>' })
class AdminDestinationStub {}
async function setup<T>(type: Type<T>, routeParams: Record<string, string> = {}, queryParams: Record<string, string> = {}): Promise<void> {
    authStatus = signal('authenticated');
    TestBed.configureTestingModule({
        providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([]), {
                provide: AuthService,
                useValue: {
                    status: authStatus,
                    user: () => ({
                        accountId: ownerId
                    })
                }
            }]
    });
    const route = TestBed.inject(ActivatedRoute);
    params = new BehaviorSubject(convertToParamMap(routeParams));
    query = new BehaviorSubject(convertToParamMap(queryParams));
    Object.defineProperty(route, 'paramMap', {
        value: params.asObservable()
    });
    Object.defineProperty(route, 'queryParamMap', {
        value: query.asObservable()
    });
    TestBed.inject(AdminSession).access.set({
        owner: true,
        permissions: {
            events: true,
            moderation: true,
            promos: true,
            support: true
        }
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(type);
    root = fixture.nativeElement;
    fixture.detectChanges();
}
async function settle(): Promise<void> {
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();
    fixture.detectChanges();
}
function click(text: string): void {
    const button = [...root.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent?.includes(text));
    expect(button, text).toBeDefined();
    button!.click();
    fixture.detectChanges();
}
function fill(name: string, value: string): void {
    const field = root.querySelector<HTMLInputElement>(`[formControlName="${name}"]`)!;
    expect(field, name).not.toBeNull();
    field.value = value;
    field.dispatchEvent(new Event(field.tagName === 'SELECT' ? 'change' : 'input'));
    fixture.detectChanges();
}
function flushTickets(): void {
    http.expectOne(request => request.url === '/api/admin/support/tickets').flush(wire.support.tickets);
}
function flushThread(value: object = wire.support.conversation): void {
    http.expectOne(request => request.url === `/api/admin/support/tickets/${ticketId}`).flush(value);
}
async function admitThread(): Promise<void> {
    flushTickets();
    flushThread();
    await settle();
}
function flushReport(value: object = wire.report): void {
    http.expectOne(request => request.url === '/api/admin/console/report').flush(value);
}
afterEach(() => {
    http?.verify();
    fixture?.destroy();
});
describe('owner console shell', () => {
    it('exposes a closed mobile disclosure using one named navigation list', async () => {
        await setup(AdminShellComponent); http.expectOne('/api/admin/console/access').flush(wire.access); await settle();
        const toggle = root.querySelector<HTMLButtonElement>('.admin-nav-toggle')!;
        const links = root.querySelector<HTMLElement>('#admin-navigation-links')!;
        expect(toggle.getAttribute('aria-controls')).toBe(links.id);
        expect(toggle.getAttribute('aria-expanded')).toBe('false'); expect(links.classList.contains('is-open')).toBe(false);
        expect(root.querySelectorAll('nav a')).toHaveLength(6);
        toggle.click(); fixture.detectChanges();
        expect(toggle.getAttribute('aria-expanded')).toBe('true'); expect(links.classList.contains('is-open')).toBe(true);
        toggle.click(); fixture.detectChanges(); expect(toggle.getAttribute('aria-expanded')).toBe('false');
    });
    it('keeps the disclosure open on rejected navigation and closes it after a successful navigation', async () => {
        await setup(AdminShellComponent); http.expectOne('/api/admin/console/access').flush(wire.access); await settle();
        const router = TestBed.inject(Router);
        router.resetConfig([{ path: 'manage/users', component: AdminDestinationStub, canActivate: [() => false] }]);
        const toggle = root.querySelector<HTMLButtonElement>('.admin-nav-toggle')!;
        toggle.click(); fixture.detectChanges();
        expect(await router.navigateByUrl('/manage/users')).toBe(false); fixture.detectChanges();
        expect(toggle.getAttribute('aria-expanded')).toBe('true');
        router.resetConfig([{ path: 'manage/users', component: AdminDestinationStub }]);
        expect(await router.navigateByUrl('/manage/users')).toBe(true); fixture.detectChanges();
        expect(toggle.getAttribute('aria-expanded')).toBe('false');
    });
    it('rejects the initial owner response when authentication changed before it arrived', async () => {
        await setup(AdminShellComponent);
        const pending = http.expectOne('/api/admin/console/access');
        authStatus.set('anonymous');
        await settle();
        pending.flush(wire.access);
        await settle();
        expect(root.querySelector('router-outlet')).toBeNull();
        expect(root.textContent).toContain('Доступ закрыт');
    });
    it('destroys the private outlet when authentication ends, without depending on navigation', async () => {
        await setup(AdminShellComponent);
        http.expectOne('/api/admin/console/access').flush(wire.access);
        await settle();
        expect(root.querySelector('router-outlet')).not.toBeNull();
        authStatus.set('anonymous');
        await settle();
        expect(root.querySelector('router-outlet')).toBeNull();
        expect(root.textContent).toContain('Доступ закрыт');
    });
    it('defers private pages until server owner authorization and clears session on destroy', async () => {
        await setup(AdminShellComponent);
        expect(root.querySelector('router-outlet')).toBeNull();
        http.expectOne('/api/admin/console/access').flush(wire.access);
        await settle();
        expect(root.querySelector('router-outlet')).not.toBeNull();
        expect(root.querySelectorAll('nav a')).toHaveLength(6);
        fixture.destroy();
        expect(TestBed.inject(AdminSession).access()).toBeNull();
    });
    it('denies nonowners without rendering private pages', async () => {
        await setup(AdminShellComponent);
        http.expectOne('/api/admin/console/access').flush({}, {
            status: 403,
            statusText: 'Forbidden'
        });
        await settle();
        expect(root.textContent).toContain('Доступ закрыт');
        expect(root.querySelector('router-outlet')).toBeNull();
    });
    it('allows recovery from an access-check outage', async () => {
        await setup(AdminShellComponent);
        http.expectOne('/api/admin/console/access').flush({}, {
            status: 503,
            statusText: 'Unavailable'
        });
        await settle();
        click('Повторить проверку');
        http.expectOne('/api/admin/console/access').flush(wire.access);
        await settle();
        expect(root.querySelector('router-outlet')).not.toBeNull();
    });
});
describe('owner report', () => {
    it('labels financial absence, empty population, estimates and retention without invented revenue', async () => {
        await setup(AdminReportPageComponent);
        flushReport();
        await settle();
        expect(root.textContent).toContain('Нет источника');
        expect(root.textContent).toContain('оценка');
        expect(root.textContent).toContain('Нет данных');
        expect(root.textContent).toContain('не суммируются');
        expect(root.textContent).toContain('90 дней');
    });
    it('honestly warns of expired rows and truncated route groups, and includes source-linked drilldowns', async () => {
        const report = clone(wire.report);
        report.ai.rangeIncludesExpiredData = true;
        report.ai.routeGroupsTruncated = true;
        report.usage.topUsers = [{
                accountId: wire.user.accountId,
                events: 4,
                creditsDebited: 12
            }] as never;
        await setup(AdminReportPageComponent);
        flushReport(report);
        await settle();
        expect(root.textContent).toContain('старше срока');
        expect(root.textContent).toContain('256');
        expect(root.querySelector<HTMLAnchorElement>(`a[href^="/manage/users/${wire.user.accountId}"]`)).not.toBeNull();
    });
    it('validates dates without sending and ignores an old report after changing period', async () => {
        await setup(AdminReportPageComponent);
        const old = http.expectOne(request => request.url === '/api/admin/console/report');
        query.next(convertToParamMap({
            from: '2026-10-01',
            through: '2026-10-02'
        }));
        const current = http.expectOne(request => request.url === '/api/admin/console/report');
        current.flush(wire.report);
        await settle();
        old.flush({
            ...wire.report,
            usage: {
                ...wire.report.usage,
                activeUsers: 999
            }
        });
        await settle();
        expect(root.textContent).not.toContain('999');
        fill('from', '2026-10-09');
        fill('through', '2026-10-01');
        click('Показать период');
        await settle();
        expect(root.textContent).toContain('Выберите существующие даты');
        http.expectNone(request => request.url === '/api/admin/console/report');
    });
    it('recovers report read errors without showing stale figures', async () => {
        await setup(AdminReportPageComponent);
        http.expectOne(request => request.url === '/api/admin/console/report').flush({}, {
            status: 503,
            statusText: 'Unavailable'
        });
        await settle();
        expect(root.textContent).toContain('Не удалось');
        click('Обновить');
        flushReport();
        await settle();
        expect(root.textContent).toContain('За что платим');
    });
});
describe('user directory and context', () => {
    it('uses email as the accessible name when both public names are absent', async () => {
        await setup(AdminUsersPageComponent);
        http.expectOne(request => request.url === `${identity}/directory`).flush({
            accounts: [{
                    ...wire.directory.accounts[0],
                    displayName: null,
                    profileUsername: null
                }],
            next: null
        });
        await settle();
        expect(root.querySelector('tbody a')?.textContent).toBe('synthetic@example.test');
    });
    it('does not resurrect details from a late successful account read after access is denied', async () => {
        await setup(AdminUsersPageComponent, {
            accountId: wire.user.accountId
        });
        const account = http.expectOne(`${identity}/directory/${wire.user.accountId}`);
        http.expectOne(request => request.url === `${identity}/directory`).flush({}, {
            status: 403,
            statusText: 'Forbidden'
        });
        await settle();
        account.flush(wire.directory.accounts[0]);
        await settle();
        expect(root.textContent).not.toContain('synthetic@example.test');
        http.expectNone(request => request.url === `/api/admin/console/users/${wire.user.accountId}`);
    });
    it('uses literal search and resets cursor when filters apply', async () => {
        await setup(AdminUsersPageComponent);
        http.expectOne(request => request.url === `${identity}/directory`).flush(wire.directory);
        await settle();
        const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
        fill('query', 'reader%_');
        click('Применить');
        await settle();
        expect(navigate).toHaveBeenCalledWith(['/manage/users'], {
            queryParams: expect.objectContaining({
                query: 'reader%_'
            })
        });
        expect(root.querySelector<HTMLAnchorElement>(`a[href^="/manage/users/${wire.user.accountId}"]`)).not.toBeNull();
    });
    it('keeps account facts when Learning report fails and requires a reason before banning', async () => {
        await setup(AdminUsersPageComponent, {
            accountId: wire.user.accountId
        });
        http.expectOne(request => request.url === `${identity}/directory`).flush(wire.directory);
        http.expectOne(`${identity}/directory/${wire.user.accountId}`).flush(wire.directory.accounts[0]);
        await settle();
        http.expectOne(request => request.url === `/api/admin/console/users/${wire.user.accountId}`).flush({}, {
            status: 503,
            statusText: 'Unavailable'
        });
        await settle();
        expect(root.textContent).toContain('synthetic@example.test');
        expect(root.textContent).toContain('Сведения аккаунта');
        click('Заблокировать');
        root.querySelector('form.editor')!.dispatchEvent(new Event('submit', {
            bubbles: true,
            cancelable: true
        }));
        await settle();
        expect(root.textContent).toContain('Укажите причину');
        http.expectNone(request => request.method === 'POST');
    });
    it('renders stored allowance coverage and never equates promo access with payment', async () => {
        await setup(AdminUsersPageComponent, {
            accountId: wire.user.accountId
        });
        http.expectOne(request => request.url === `${identity}/directory`).flush(wire.directory);
        http.expectOne(`${identity}/directory/${wire.user.accountId}`).flush(wire.directory.accounts[0]);
        await settle();
        http.expectOne(request => request.url === `/api/admin/console/users/${wire.user.accountId}`).flush(wire.user);
        await settle();
        expect(root.querySelector('app-usage-meter')).not.toBeNull();
        expect(root.textContent).toContain('не подтверждают текущий тариф');
        expect(root.textContent).toContain('PROMO не означает оплату');
        expect(root.textContent).toContain('Текущий доступ');
        expect(root.textContent).toContain('60 с');
        expect(root.textContent).toContain('2 дек. 2026');
        expect(root.textContent).toContain('1 нояб. 2026');
    });
    it('clears account facts after a forbidden directory read', async () => {
        await setup(AdminUsersPageComponent);
        http.expectOne(request => request.url === `${identity}/directory`).flush({}, {
            status: 403,
            statusText: 'Forbidden'
        });
        await settle();
        expect(root.textContent).toContain('Доступ закрыт');
        expect(root.textContent).not.toContain('synthetic@example.test');
    });
});
describe('promo operations', () => {
    async function setupPromo(): Promise<void> {
        await setup(AdminPromosPageComponent);
        http.expectOne('/api/admin/promo-codes').flush({
            codes: [promo],
            next: null
        });
        await settle();
    }
    it('protects dirty input on browser close but permits departure after authentication ends', async () => {
        await setupPromo();
        fill('channel', 'unfinished');
        const dirty = new Event('beforeunload', {
            cancelable: true
        });
        window.dispatchEvent(dirty);
        expect(dirty.defaultPrevented).toBe(true);
        authStatus.set('anonymous');
        await settle();
        const ended = new Event('beforeunload', {
            cancelable: true
        });
        window.dispatchEvent(ended);
        expect(ended.defaultPrevented).toBe(false);
        expect(fixture.componentInstance.canLeave()).toBe(true);
    });
    it('retains a command as uncertain when the returned code belongs to another valid promo', async () => {
        await setupPromo();
        click('Создать промокод');
        const request = http.expectOne('/api/admin/promo-codes');
        request.flush({
            ...promo,
            code: 'WRONGCODE123'
        });
        await settle();
        expect(root.querySelector('input[readonly]')).toBeNull();
        expect(root.textContent).toContain('Повторить ту же команду');
        expect(fixture.componentInstance.canLeave()).toBe(false);
    });
    it('validates conditional discount dates and max activations before creation', async () => {
        await setupPromo();
        fill('type', 'DISCOUNT_PERCENT');
        click('Создать промокод');
        await settle();
        expect(root.textContent).toContain('Для скидки укажите конец');
        http.expectNone(request => request.method === 'POST');
        fill('type', 'TIER_MONTHS');
        fill('amount', '25');
        click('Создать промокод');
        await settle();
        expect(root.textContent).toContain('от 1 до 24');
    });
    it('freezes the full create command on unknown outcome and only retries its exact code/body/key', async () => {
        await setupPromo();
        click('Создать промокод');
        const first = http.expectOne('/api/admin/promo-codes');
        const body = first.request.body;
        const key = first.request.headers.get('Idempotency-Key');
        first.error(new ProgressEvent('error'));
        await settle();
        expect(fixture.componentInstance.canLeave()).toBe(false);
        expect((root.querySelector('[formControlName=type]') as HTMLSelectElement).disabled).toBe(true);
        click('Повторить ту же команду');
        const second = http.expectOne('/api/admin/promo-codes');
        expect(second.request.body).toEqual(body);
        expect(second.request.headers.get('Idempotency-Key')).toBe(key);
        second.flush({
            ...promo,
            code: body.code
        });
        await settle();
        http.expectOne('/api/admin/promo-codes').flush({}, {
            status: 503,
            statusText: 'Unavailable'
        });
        await settle();
        expect(root.textContent).toContain('Промокод создан');
        expect(root.textContent).toContain('Список промокодов');
        expect(root.textContent).not.toContain('Повторить ту же команду');
        expect((root.querySelector('input[readonly]') as HTMLInputElement).value).toBe(body.code);
        click('Код сохранён');
        expect(root.querySelector('input[readonly]')).toBeNull();
    });
    it('switches target state without deleting prior redemptions and preserves form on list failure', async () => {
        await setupPromo();
        click('Выключить');
        const request = http.expectOne(`/api/admin/promo-codes/${promo.codeId}`);
        expect(request.request.body).toEqual({
            enabled: false
        });
        request.flush({
            ...promo,
            enabled: false
        });
        await settle();
        expect(root.textContent).toContain('выданный доступ сохранён');
        fill('channel', 'community');
        click('Обновить');
        http.expectOne('/api/admin/promo-codes').flush({}, {
            status: 503,
            statusText: 'Unavailable'
        });
        await settle();
        expect((root.querySelector('[formControlName=channel]') as HTMLInputElement).value).toBe('community');
    });
    it('does not call admin promo APIs without the real admin permission', async () => {
        await setup(AdminPromosPageComponent);
        http.expectOne('/api/admin/promo-codes').flush({
            codes: [],
            next: null
        });
        await settle();
        TestBed.inject(AdminSession).access.set({
            owner: true,
            permissions: {
                events: true,
                moderation: false,
                promos: false,
                support: false
            }
        });
        fixture.detectChanges();
        expect(root.textContent).toContain('нет административного разрешения');
        expect(root.querySelector('form')).toBeNull();
    });
});
describe('support workspace', () => {
    it('protects an unsubmitted private draft on close without persisting it', async () => {
        await setup(AdminSupportPageComponent, {
            ticketId
        });
        await admitThread();
        fill('text', 'Private unsent answer');
        const dirty = new Event('beforeunload', {
            cancelable: true
        });
        window.dispatchEvent(dirty);
        expect(dirty.defaultPrevented).toBe(true);
        authStatus.set('anonymous');
        await settle();
        const ended = new Event('beforeunload', {
            cancelable: true
        });
        window.dispatchEvent(ended);
        expect(ended.defaultPrevented).toBe(false);
        expect(fixture.componentInstance.canLeave()).toBe(true);
    });
    it('does not resurrect a queue from a late response after the conversation denies access', async () => {
        await setup(AdminSupportPageComponent, {
            ticketId
        });
        const queue = http.expectOne(request => request.url === '/api/admin/support/tickets');
        http.expectOne(request => request.url === `/api/admin/support/tickets/${ticketId}`).flush({}, {
            status: 403,
            statusText: 'Forbidden'
        });
        await settle();
        queue.flush(wire.support.tickets);
        await settle();
        expect(root.querySelector('.ticket-choice')).toBeNull();
        expect(root.querySelector('textarea')).toBeNull();
        expect(fixture.componentInstance.canLeave()).toBe(true);
    });
    it('distinguishes private notes and account association while rendering user HTML as text', async () => {
        await setup(AdminSupportPageComponent, {
            ticketId
        });
        const conversation = clone(wire.support.conversation);
        conversation.messages[0].text = '<script>alert(1)</script>';
        flushTickets();
        flushThread(conversation);
        await settle();
        expect(root.querySelector('script')).toBeNull();
        expect(root.textContent).toContain('<script>');
        expect(root.textContent).toContain('Внутренняя заметка');
        expect(root.textContent).toContain('Аккаунт Mnema не подтверждён');
        expect(root.textContent).toContain('fixture.txt');
    });
    it('keeps draft on independent refresh outage and requires discard before selecting another ticket', async () => {
        await setup(AdminSupportPageComponent, {
            ticketId
        });
        http.expectOne(request => request.url === '/api/admin/support/tickets').flush({
            entries: [...wire.support.tickets.entries, {
                    ...wire.support.tickets.entries[0],
                    id: '9007199254740994'
                }],
            nextCursor: null
        });
        flushThread();
        await settle();
        fill('text', 'Ответ в процессе');
        click('Обновить переписку');
        http.expectOne(request => request.url === `/api/admin/support/tickets/${ticketId}`).flush({}, {
            status: 503,
            statusText: 'Unavailable'
        });
        await settle();
        expect((root.querySelector('textarea') as HTMLTextAreaElement).value).toBe('Ответ в процессе');
        root.querySelectorAll<HTMLButtonElement>('.ticket-choice')[1].click();
        fixture.detectChanges();
        expect(root.textContent).toContain('неотправленный текст');
        click('Продолжить писать');
        expect((root.querySelector('textarea') as HTMLTextAreaElement).value).toBe('Ответ в процессе');
        vi.spyOn(window, 'confirm').mockReturnValue(false);
        expect(fixture.componentInstance.canLeave()).toBe(false);
    });
    it('sends notes without a browser actor, resolves unknown outcomes by identical retry and acknowledges queued only', async () => {
        await setup(AdminSupportPageComponent, {
            ticketId
        });
        await admitThread();
        fill('type', 'note');
        fill('text', 'Внутренняя запись');
        click('Сохранить заметку');
        const first = http.expectOne(`/api/admin/support/tickets/${ticketId}/commands`);
        const command = first.request.body;
        expect(command.type).toBe('note');
        expect(command.expectedVersion).toBe(2);
        expect(command.actor).toBeUndefined();
        first.error(new ProgressEvent('error'));
        await settle();
        expect(fixture.componentInstance.canLeave()).toBe(false);
        click('Повторить ту же команду');
        const retry = http.expectOne(`/api/admin/support/tickets/${ticketId}/commands`);
        expect(retry.request.body).toEqual(command);
        retry.flush({
            ...wire.support.acknowledgement,
            commandId: command.commandId,
            delivery: null,
            outboxId: null
        });
        await settle();
        flushThread();
        await settle();
        flushTickets();
        await settle();
        expect(root.textContent).toContain('Внутренняя заметка сохранена');
        expect((root.querySelector('textarea') as HTMLTextAreaElement).value).toBe('');
    });
    it('retains reply on version conflict and never blindly repeats uncertain Telegram delivery', async () => {
        await setup(AdminSupportPageComponent, {
            ticketId
        });
        const conversation = clone(wire.support.conversation);
        conversation.messages[0].direction = 'out';
        conversation.messages[0].delivery = 'uncertain' as never;
        flushTickets();
        flushThread(conversation);
        await settle();
        expect(root.textContent).toContain('Автоматический повтор отключён');
        expect(root.textContent).not.toContain('Повторить ту же команду');
        fill('text', 'Ответ');
        click('Отправить ответ');
        http.expectOne(`/api/admin/support/tickets/${ticketId}/commands`).flush({}, {
            status: 409,
            statusText: 'Conflict'
        });
        await settle();
        expect((root.querySelector('textarea') as HTMLTextAreaElement).value).toBe('Ответ');
        expect(root.textContent).toContain('обращение уже изменилось');
    });
    it('changes status separately without erasing a reply draft', async () => {
        await setup(AdminSupportPageComponent, {
            ticketId
        });
        await admitThread();
        fill('text', 'Неотправленный ответ');
        click('Ждём пользователя');
        const request = http.expectOne(`/api/admin/support/tickets/${ticketId}/commands`);
        expect(request.request.body.type).toBe('status');
        expect(request.request.body.text).toBeUndefined();
        request.flush({
            ...wire.support.acknowledgement,
            commandId: request.request.body.commandId,
            delivery: null,
            outboxId: null,
            messageId: null
        });
        await settle();
        flushThread();
        await settle();
        flushTickets();
        await settle();
        expect((root.querySelector('textarea') as HTMLTextAreaElement).value).toBe('Неотправленный ответ');
    });
    it('clears private conversations after access revocation', async () => {
        await setup(AdminSupportPageComponent, {
            ticketId
        });
        await admitThread();
        fill('text', 'private draft');
        click('Обновить очередь');
        http.expectOne(request => request.url === '/api/admin/support/tickets').flush({}, {
            status: 403,
            statusText: 'Forbidden'
        });
        await settle();
        expect(root.textContent).not.toContain('Private fixture note');
        expect(root.querySelector('textarea')).toBeNull();
    });
});
describe('separate action journals', () => {
    it('keeps the other source useful after a source outage and supports retry', async () => {
        await setup(AdminAuditPageComponent);
        http.expectOne('/api/admin/console/audit').flush(wire.audit);
        http.expectOne(`${identity}/audit`).flush({}, {
            status: 503,
            statusText: 'Unavailable'
        });
        await settle();
        expect(root.textContent).toContain('Создан промокод');
        expect(root.textContent).toContain('Этот источник журнала недоступен');
        [...root.querySelectorAll<HTMLButtonElement>('button')].filter(button => button.textContent?.includes('Обновить'))[1].click();
        http.expectOne(`${identity}/audit`).flush({
            entries: [],
            next: null
        });
        await settle();
        expect(root.textContent).not.toContain('Этот источник');
    });
});
