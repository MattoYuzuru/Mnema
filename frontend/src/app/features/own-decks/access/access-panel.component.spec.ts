import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';

import access from '../../../../../../contracts/decks/access.json';
import { AuthorCardsService } from '../../../author-cards.service';
import type { AuthorCard } from '../../../public-profile';
import { ToastService } from '../../../core/notifications/toast.service';
import { lastCall, spyObj, type SpyObj } from '../../../../testing/mocks';
import { AccessApiService } from './access-api.service';
import { AccessPanelComponent } from './access-panel.component';
import { AccessDecision, AccessGrant, AccessRequest, CreatedInvite, DeckInvite, KeysetPage } from './access.models';

const DECK = '11111111-1111-4111-8111-111111111111';
const ANNA = '0192f3a4-5b6c-7d8e-9f01-23456789abcd';
const STRANGER = '0192f3a4-5b6c-7d8e-9f01-23456789abce';
const OLGA = '0192f3a4-5b6c-7d8e-9f01-23456789abcf';
const page = <T>(items: readonly T[], nextCursor: string | null = null): KeysetPage<T> => ({ items, nextCursor });
const card = (accountId: string, profileUsername: string): AuthorCard => ({ accountId, profileUsername, displayName: null, bio: null, avatarPresent: false });
const request = (requesterId: string, createdAt = '2026-10-11T09:45:00.123456Z'): AccessRequest => ({ requesterId, createdAt });
const grant = (granteeId: string): AccessGrant => ({ granteeId, role: 'VIEWER', grantedAt: '2026-10-10T12:00:00Z' });
const invite = (overrides: Partial<DeckInvite> = {}): DeckInvite => ({ ...(access.invites.list.answer.items[0] as DeckInvite), ...overrides });
const created: CreatedInvite = access.invites.create.answer.body as CreatedInvite;

describe('AccessPanelComponent', () => {
    let fixture: ComponentFixture<AccessPanelComponent>;
    let root: HTMLElement;
    let api: SpyObj<AccessApiService>;
    let cards: SpyObj<AuthorCardsService>;
    let toasts: ToastService;

    const settle = async (): Promise<void> => { await fixture.whenStable(); fixture.detectChanges(); await fixture.whenStable(); fixture.detectChanges(); };
    const section = (name: string): HTMLElement => root.querySelector<HTMLElement>(name)!;
    const button = (scope: string, label: string): HTMLButtonElement =>
        [...section(scope).querySelectorAll<HTMLButtonElement>('button')].find(item => item.textContent!.trim() === label)!;
    const rows = (scope: string): HTMLElement[] => [...section(scope).querySelectorAll<HTMLElement>('.roster-row')];
    const flat = (element: Element): string => element.textContent!.replace(/\s+/g, ' ').trim();
    const status = (scope: string): string => section(scope).querySelector('[role=status][aria-live=polite]')!.textContent!;

    async function create(inputs: { requestsEnabled?: boolean } = {}): Promise<void> {
        fixture = TestBed.createComponent(AccessPanelComponent);
        fixture.componentRef.setInput('deckId', DECK);
        fixture.componentRef.setInput('deckTitle', 'Японский');
        fixture.componentRef.setInput('requestsEnabled', inputs.requestsEnabled ?? true);
        root = fixture.nativeElement as HTMLElement;
        document.body.append(root);
        fixture.detectChanges();
        await settle();
    }

    beforeEach(async () => {
        api = spyObj<AccessApiService>({
            createInvite: vi.fn().mockName('createInvite'), invites: vi.fn().mockName('invites'), revokeInvite: vi.fn().mockName('revokeInvite'),
            requests: vi.fn().mockName('requests'), decide: vi.fn().mockName('decide'),
            grants: vi.fn().mockName('grants'), grantByLogin: vi.fn().mockName('grantByLogin'), revokeGrant: vi.fn().mockName('revokeGrant')
        });
        api.invites.mockReturnValue(of(page([])));
        api.requests.mockReturnValue(of(page([])));
        api.grants.mockReturnValue(of(page([])));
        cards = spyObj<AuthorCardsService>({
            cards: vi.fn().mockImplementation(async (ids: readonly string[]) =>
                new Map([[ANNA, card(ANNA, 'anna.k')], [OLGA, card(OLGA, 'olga-v')]].filter(([id]) => ids.includes(id as string)) as [string, AuthorCard][])),
            avatarUrl: vi.fn().mockReturnValue(null)
        });
        await TestBed.configureTestingModule({
            providers: [{ provide: AccessApiService, useValue: api }, { provide: AuthorCardsService, useValue: cards }]
        }).compileComponents();
        toasts = TestBed.inject(ToastService);
        vi.spyOn(toasts, 'echo');
    });

    afterEach(() => root?.remove());

    it('has three sections, each with its own heading, and says what is empty', async () => {
        await create();
        expect([...root.querySelectorAll('h3')].map(heading => heading.textContent)).toEqual(['Запросы доступа', 'Пригласительные ссылки', 'Кто имеет доступ']);
        for (const name of ['app-access-requests', 'app-invite-links', 'app-access-people']) {
            const element = section(name).querySelector('section')!;
            expect(document.getElementById(element.getAttribute('aria-labelledby')!)!.tagName).toBe('H3');
        }
        expect(flat(section('app-access-requests'))).toContain('Новых запросов нет');
        expect(flat(section('app-invite-links'))).toContain('Ссылок пока нет');
        expect(flat(section('app-access-people'))).toContain('Пока никого');
        // The sentence stands outside any list.
        for (const name of ['app-access-requests', 'app-invite-links', 'app-access-people']) expect(section(name).querySelector('ul')).toBeNull();
    });

    it('lets one failing list retry on its own without hiding the others', async () => {
        api.grants.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 503 })));
        api.requests.mockReturnValue(of(page([request(ANNA)])));
        await create();
        expect(flat(section('app-access-people'))).toContain('Не удалось загрузить список.');
        expect(rows('app-access-requests')).toHaveLength(1);
        api.grants.mockReturnValue(of(page([grant(OLGA)])));
        button('app-access-people', 'Повторить').click();
        await settle();
        expect(rows('app-access-people')).toHaveLength(1);
    });

    describe('access requests', () => {
        beforeEach(() => {
            api.requests.mockReturnValue(of(page([request(ANNA), request(STRANGER, '2026-10-11T08:10:00Z')])));
        });

        it('shows each requester as an author chip or as a person without a public profile, asking for the names in one batch', async () => {
            await create();
            expect(cards.cards).toHaveBeenCalledTimes(1);
            expect([...cards.cards.mock.calls[0][0]]).toEqual([ANNA, STRANGER]);
            const [first, second] = rows('app-access-requests');
            expect(first.querySelector('app-author-chip')!.textContent).toContain('anna.k');
            expect(flat(second)).toContain('Пользователь без публичного профиля');
            expect(second.querySelector('app-author-chip')).toBeNull();
            expect(first.querySelector('time, .hint')!.textContent).toMatch(/11\.10\.2026/);
        });

        it('names the lookup that failed, and the one still running', async () => {
            const pending = new Promise<ReadonlyMap<string, AuthorCard>>(() => undefined);
            cards.cards.mockReturnValueOnce(pending).mockRejectedValueOnce(new Error('offline'));
            await create();
            expect(rows('app-access-requests').map(flat).join(' ')).toContain('Загружаем имя…');
            fixture.destroy();
            cards.cards.mockRejectedValue(new Error('offline'));
            await create();
            expect(flat(rows('app-access-requests')[0])).toContain('Имя не загрузилось');
        });

        it('names every row action with its visible word first and whom it is about, once the name is known', async () => {
            await create();
            const [first, second] = rows('app-access-requests');
            expect([...first.querySelectorAll('button')].map(action => action.getAttribute('aria-label')))
                .toEqual(['Принять запрос от @anna.k', 'Отклонить запрос от @anna.k']);
            expect([...second.querySelectorAll('button')].map(action => action.getAttribute('aria-label')))
                .toEqual(['Принять запрос от пользователя без публичного профиля', 'Отклонить запрос от пользователя без публичного профиля']);
            // WCAG 2.5.3: the accessible name starts with the visible text.
            for (const action of section('app-access-requests').querySelectorAll('button')) expect(action.getAttribute('aria-label')!.startsWith(action.textContent!.trim())).toBe(true);
            expect(first.querySelector('time')!.getAttribute('datetime')).toBe('2026-10-11T09:45:00.123456Z');
            expect(section('app-access-requests').querySelector('ul')!.getAttribute('role')).toBe('list');
        });

        it('has no name while the person is unknown, and offers «Повторить» for a failed lookup', async () => {
            cards.cards.mockReturnValueOnce(new Promise(() => undefined));
            await create();
            expect(rows('app-access-requests')[0].querySelector('button')!.getAttribute('aria-label')).toBeNull();
            fixture.destroy();
            root.remove();
            cards.cards.mockRejectedValueOnce(new Error('offline'));
            await create();
            expect(flat(rows('app-access-requests')[0])).toContain('Имя не загрузилось');
            const retry = rows('app-access-requests')[0].querySelector<HTMLButtonElement>('app-access-person button')!;
            expect(retry.getAttribute('aria-label')).toBe('Повторить загрузку имени');
            cards.cards.mockResolvedValueOnce(new Map([[ANNA, card(ANNA, 'anna.k')]]));
            retry.click();
            await settle();
            expect(rows('app-access-requests')[0].querySelector('app-author-chip')!.textContent).toContain('anna.k');
        });

        it('approves: sends the decision, removes the row, speaks the result, re-reads the people and moves the focus to the heading', async () => {
            const decision: AccessDecision = { requesterId: ANNA, status: 'APPROVED', decidedAt: '2026-10-11T10:05:00Z' };
            api.decide.mockReturnValue(of(decision));
            await create();
            const grantsBefore = api.grants.mock.calls.length;
            button('app-access-requests', 'Принять').click();
            await settle();
            expect(api.decide).toHaveBeenCalledWith(DECK, ANNA, 'approve');
            expect(rows('app-access-requests')).toHaveLength(1);
            expect(status('app-access-requests')).toBe('Доступ открыт: @anna.k');
            expect(api.grants.mock.calls.length).toBe(grantsBefore + 1);
            expect(document.activeElement).toBe(section('app-access-requests').querySelector('h3'));
        });

        it('keeps the request in the list and says the deck is full when approving at the cap', async () => {
            api.decide.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 422, error: { code: 'RESOURCE_LIMIT_EXCEEDED', limit: 1000 } })));
            await create();
            button('app-access-requests', 'Принять').click();
            await settle();
            expect(rows('app-access-requests')).toHaveLength(2);
            expect(section('app-access-requests').querySelector('[role=alert]')!.textContent).toContain('1000 человек с доступом');
        });

        it('rejects silently and says so, and falls back to the heading when the list empties', async () => {
            api.requests.mockReturnValue(of(page([request(ANNA)])));
            api.decide.mockReturnValue(of({ requesterId: ANNA, status: 'REJECTED', decidedAt: '2026-10-11T10:05:00Z' }));
            await create();
            button('app-access-requests', 'Отклонить').click();
            await settle();
            expect(api.decide).toHaveBeenCalledWith(DECK, ANNA, 'reject');
            expect(status('app-access-requests')).toBe('Запрос отклонён. Человек об этом не узнает.');
            expect(document.activeElement).toBe(section('app-access-requests').querySelector('h3'));
            expect(flat(section('app-access-requests'))).toContain('Новых запросов нет');
        });

        it('allows one decision at a time and shows which one is running', async () => {
            const pending = new Subject<AccessDecision>();
            api.decide.mockReturnValue(pending);
            await create();
            button('app-access-requests', 'Принять').click();
            await settle();
            const approving = button('app-access-requests', 'Принимаем…');
            expect(approving.getAttribute('aria-busy')).toBe('true');
            for (const action of section('app-access-requests').querySelectorAll('button')) expect(action.getAttribute('aria-disabled')).toBe('true');
            button('app-access-requests', 'Отклонить').click();
            expect(api.decide).toHaveBeenCalledTimes(1);
            pending.next({ requesterId: ANNA, status: 'APPROVED', decidedAt: '2026-10-11T10:05:00Z' });
            pending.complete();
            await settle();
            expect(rows('app-access-requests')).toHaveLength(1);
        });

        it('reloads the list when the request is already gone, and says so', async () => {
            api.decide.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 404 })));
            await create();
            const reads = api.requests.mock.calls.length;
            button('app-access-requests', 'Принять').click();
            await settle();
            expect(section('app-access-requests').querySelector('[role=alert]')!.textContent).toBe('Этого больше нет. Мы обновили список.');
            expect(api.requests.mock.calls.length).toBe(reads + 1);
            api.decide.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 0 })));
            button('app-access-requests', 'Принять').click();
            await settle();
            expect(section('app-access-requests').querySelector('[role=alert]')!.textContent).toContain('Не удалось связаться с сервером');
        });

        it('says that new requests are off, and keeps the open ones decidable', async () => {
            await create({ requestsEnabled: false });
            expect(flat(section('app-access-requests'))).toContain('Запросы выключены');
            expect(rows('app-access-requests')).toHaveLength(2);
        });

        it('continues the list through the shared auto-load with a cursor', async () => {
            api.requests.mockReturnValueOnce(of(page([request(ANNA)], 'next')));
            await create();
            const loader = fixture.debugElement.query(el => el.name === 'app-auto-load').componentInstance;
            api.requests.mockReturnValueOnce(of(page([request(ANNA), request(STRANGER)])));
            loader.loadNext.emit();
            await settle();
            expect(lastCall(api.requests)[1]).toBe('next');
            expect(rows('app-access-requests')).toHaveLength(2);
        });
    });

    describe('invitation links', () => {
        const pickChoice = async (controlSuffix: string, value: string): Promise<void> => {
            const selects = fixture.debugElement.queryAll(el => el.name === 'app-mnema-select').map(el => el.componentInstance);
            selects.find(select => select.controlId().endsWith(controlSuffix)).valueChange.emit(value);
            await settle();
        };

        it('lists the links with a status word, the uses and the term, and shows no link or token in the list', async () => {
            api.invites.mockReturnValue(of(page([
                invite({ maxUses: 5, uses: 2 }),
                invite({ inviteId: '0192f3a4-5b6c-7d8e-9f01-000000000002', status: 'EXHAUSTED', maxUses: 1, uses: 1, expiresAt: null }),
                invite({ inviteId: '0192f3a4-5b6c-7d8e-9f01-000000000003', status: 'REVOKED', revokedAt: '2026-10-11T10:00:00Z' }),
                invite({ inviteId: '0192f3a4-5b6c-7d8e-9f01-000000000004', status: 'STALE' })
            ])));
            await create();
            const [active, exhausted, revoked, stale] = rows('app-invite-links').map(flat);
            expect(active).toContain('Действует');
            expect(active).toContain('Открыли: 2 из 5');
            expect(exhausted).toContain('Использована');
            expect(exhausted).toContain('Срок: без ограничения');
            expect(revoked).toContain('Отозвана');
            expect(stale).toContain('Устарела');
            expect(stale).toContain('Ссылка устарела');
            expect(rows('app-invite-links').map(row => row.querySelectorAll('button').length)).toEqual([1, 0, 0, 0]);
            expect(section('app-invite-links').querySelector('a')).toBeNull();
            expect(section('app-invite-links').innerHTML).not.toContain('invite=');
        });

        it('creates a one-time link for a week by default and shows the new link once, in place of the form', async () => {
            api.createInvite.mockReturnValue(of(created));
            await create();
            button('app-invite-links', 'Создать ссылку').click();
            await settle();
            expect(api.createInvite).toHaveBeenCalledWith(DECK, { maxUses: 1, expiresInDays: 7 });
            const fresh = section('app-invite-links').querySelector('.new-link')!;
            expect(fresh.getAttribute('role')).toBe('group');
            expect(flat(fresh)).toContain(`${window.location.host}${created.link}`);
            expect(flat(fresh)).toContain('Ссылка показана один раз');
            expect(fresh.querySelector('app-share-button button')!.textContent).toContain('Поделиться');
            expect(section('app-invite-links').querySelector('form')).toBeNull();
            expect(toasts.echo).toHaveBeenCalledWith('Ссылка создана');
            // The focused group announces its name and the one-time sentence, which is in bold.
            expect(document.activeElement).toBe(fresh);
            expect(document.getElementById(fresh.getAttribute('aria-labelledby')!)!.textContent).toBe('Новая ссылка');
            const once = document.getElementById(fresh.getAttribute('aria-describedby')!)!;
            expect(once.textContent).toContain('Ссылка показана один раз.');
            expect(once.querySelector('strong')!.textContent).toBe('Ссылка показана один раз.');
            expect(fresh.classList.contains('warning')).toBe(true);
            // The list is re-read; it holds no token.
            expect(api.invites).toHaveBeenCalledTimes(2);
        });

        it('drops the link on «Готово», brings the form back with the focus on its button', async () => {
            api.createInvite.mockReturnValue(of(created));
            await create();
            button('app-invite-links', 'Создать ссылку').click();
            await settle();
            expect(flat(section('app-invite-links').querySelector('.new-link')!)).toContain('открыть её снова будет нельзя');
            button('app-invite-links', 'Готово, я сохранил ссылку').click();
            await settle();
            expect(section('app-invite-links').querySelector('.new-link')).toBeNull();
            expect(section('app-invite-links').textContent).not.toContain(created.token);
            expect(document.activeElement).toBe(button('app-invite-links', 'Создать ссылку'));
        });

        it('sends the chosen limit and term, and null for unlimited and no term', async () => {
            api.createInvite.mockReturnValue(of(created));
            await create();
            await pickChoice('-uses', '20');
            await pickChoice('-expiry', 'none');
            button('app-invite-links', 'Создать ссылку').click();
            await settle();
            expect(api.createInvite).toHaveBeenLastCalledWith(DECK, { maxUses: 20, expiresInDays: null });
            button('app-invite-links', 'Готово, я сохранил ссылку').click();
            await settle();
            await pickChoice('-uses', 'none');
            await pickChoice('-expiry', '30');
            button('app-invite-links', 'Создать ссылку').click();
            await settle();
            expect(api.createInvite).toHaveBeenLastCalledWith(DECK, { maxUses: null, expiresInDays: 30 });
        });

        it('guides the owner when the deck is private, when the limit is reached and when the answer was lost', async () => {
            await create();
            api.createInvite.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 400, error: { code: 'PUBLICATION_REQUIRED' } })));
            button('app-invite-links', 'Создать ссылку').click();
            await settle();
            expect(section('app-invite-links').querySelector('[role=alert]')!.textContent).toContain('Сначала выберите уровень доступа');
            api.createInvite.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 422, error: { code: 'RESOURCE_LIMIT_EXCEEDED', limit: 20 } })));
            button('app-invite-links', 'Создать ссылку').click();
            await settle();
            expect(section('app-invite-links').querySelector('[role=alert]')!.textContent).toContain('Действующих ссылок уже 20');
            api.createInvite.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 422, error: { code: 'RESOURCE_LIMIT_EXCEEDED', limit: 200 } })));
            button('app-invite-links', 'Создать ссылку').click();
            await settle();
            expect(section('app-invite-links').querySelector('[role=alert]')!.textContent).toContain('Слишком много сохранённых ссылок');
            api.createInvite.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 0 })));
            const reads = api.invites.mock.calls.length;
            button('app-invite-links', 'Создать ссылку').click();
            await settle();
            expect(section('app-invite-links').querySelector('[role=alert]')!.textContent).toContain('лишняя ссылка');
            expect(api.invites.mock.calls.length).toBe(reads + 1);
            expect(section('app-invite-links').querySelector('.new-link')).toBeNull();
        });

        it('ignores a second press while the link is being created', async () => {
            const pending = new Subject<CreatedInvite>();
            api.createInvite.mockReturnValue(pending);
            await create();
            button('app-invite-links', 'Создать ссылку').click();
            await settle();
            const busy = button('app-invite-links', 'Создаём…');
            expect(busy.getAttribute('aria-busy')).toBe('true');
            busy.click();
            expect(api.createInvite).toHaveBeenCalledTimes(1);
        });

        it('revokes an active link, keeps the row as «Отозвана», says the people inside stay and moves the focus to the heading', async () => {
            api.invites.mockReturnValue(of(page([invite(), invite({ inviteId: '0192f3a4-5b6c-7d8e-9f01-000000000002' })])));
            api.revokeInvite.mockImplementation((_deck, inviteId) => of(invite({ inviteId, status: 'REVOKED', revokedAt: '2026-10-11T10:00:00Z' })));
            await create();
            expect(button('app-invite-links', 'Отозвать').getAttribute('aria-label')).toMatch(/^Отозвать ссылку, созданную 11\.10\.2026, \d\d:\d\d$/);
            button('app-invite-links', 'Отозвать').click();
            await settle();
            expect(api.revokeInvite).toHaveBeenCalledWith(DECK, invite().inviteId);
            expect(flat(rows('app-invite-links')[0])).toContain('Отозвана');
            expect(status('app-invite-links')).toContain('сохраняют доступ');
            expect(document.activeElement).toBe(section('app-invite-links').querySelector('h3'));
            api.revokeInvite.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 404 })));
            button('app-invite-links', 'Отозвать').click();
            await settle();
            expect(section('app-invite-links').querySelector('[role=alert]')!.textContent).toContain('Этого больше нет');
        });
    });

    describe('people with access', () => {
        const typeLogin = async (value: string): Promise<void> => {
            const field = section('app-access-people').querySelector<HTMLInputElement>('input')!;
            field.value = value;
            field.dispatchEvent(new Event('input'));
            await settle();
        };
        const submit = async (): Promise<void> => {
            section('app-access-people').querySelector('form')!.dispatchEvent(new Event('submit'));
            await settle();
        };
        const alert = (): string | undefined => section('app-access-people').querySelector('form [role=alert]')?.textContent ?? undefined;

        it('lists the people with their chips and the date, and closes access with a row-named button', async () => {
            api.grants.mockReturnValue(of(page([grant(ANNA), grant(OLGA)])));
            api.revokeGrant.mockReturnValue(of(undefined));
            await create();
            expect(rows('app-access-people').map(row => row.querySelector('app-author-chip')!.textContent!.trim().slice(-6))).toEqual(['anna.k', 'olga-v']);
            expect(flat(rows('app-access-people')[0])).toContain('с 10.10.2026');
            const close = rows('app-access-people')[0].querySelector('button')!;
            expect(close.textContent!.trim()).toBe('Закрыть доступ');
            expect(close.getAttribute('aria-label')).toBe('Закрыть доступ: @anna.k');
            close.click();
            await settle();
            expect(api.revokeGrant).toHaveBeenCalledWith(DECK, ANNA);
            expect(rows('app-access-people')).toHaveLength(1);
            expect(status('app-access-people')).toContain('Копии, которые человек уже сделал, остаются у него');
            expect(status('app-access-people')).toContain('по пригласительной ссылке он не вернётся 30 дней'.replace('по', 'По'));
            expect(document.activeElement).toBe(section('app-access-people').querySelector('h3'));
            api.revokeGrant.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 0 })));
            button('app-access-people', 'Закрыть доступ').click();
            await settle();
            expect(rows('app-access-people')).toHaveLength(1);
            expect(section('app-access-people').querySelector('[role=alert]')!.textContent).toContain('Не удалось связаться');
        });

        it('invites by login: strips «@», sends it, clears the field, speaks the result and re-reads the list', async () => {
            api.grantByLogin.mockReturnValue(of(grant(ANNA)));
            await create();
            const field = section('app-access-people').querySelector<HTMLInputElement>('input')!;
            expect(field.labels![0].textContent).toBe('Пригласить по логину');
            expect(document.getElementById(field.getAttribute('aria-describedby')!)!.textContent).toContain('публичным профилем');
            const reads = api.grants.mock.calls.length;
            await typeLogin('  @Anna.K ');
            await submit();
            expect(api.grantByLogin).toHaveBeenCalledWith(DECK, 'Anna.K');
            expect(field.value).toBe('');
            expect(status('app-access-people')).toBe('Доступ открыт: @Anna.K');
            expect(api.grants.mock.calls.length).toBe(reads + 1);
        });

        it('refuses a malformed login on the page, with the field flagged and focused', async () => {
            await create();
            await typeLogin('a b');
            await submit();
            expect(api.grantByLogin).not.toHaveBeenCalled();
            const field = section('app-access-people').querySelector<HTMLInputElement>('input')!;
            expect(field.getAttribute('aria-invalid')).toBe('true');
            expect(alert()).toContain('от 3 до 50 символов');
            expect(field.getAttribute('aria-describedby')).toContain(section('app-access-people').querySelector('form [role=alert]')!.id);
            expect(document.activeElement).toBe(field);
            await typeLogin('anna.k');
            expect(field.getAttribute('aria-invalid')).toBeNull();
        });

        it('gives one answer for an unknown and a hidden profile, and a plain one for the owner, the limit and the outage', async () => {
            await create();
            await typeLogin('nobody');
            const outcomes: [number, string][] = [
                [404, 'Такого пользователя нет, или у него не открыт публичный профиль.'],
                [400, 'Это ваш логин: у вас и так есть доступ.'],
                [429, 'Слишком много проверок. Подождите минуту и повторите.'],
                [503, 'Не удалось проверить логин. Повторите чуть позже.'],
                [422, 'У колоды уже 1000 человек с доступом. Закройте доступ тем, кому он не нужен, и повторите.'],
                [0, 'Не удалось связаться с сервером. Проверьте соединение и повторите.']
            ];
            for (const [code, text] of outcomes) {
                api.grantByLogin.mockReturnValue(throwError(() => new HttpErrorResponse({ status: code, error: code === 422 ? { code: 'RESOURCE_LIMIT_EXCEEDED', limit: 1000 } : null })));
                await submit();
                expect(alert()).toBe(text);
            }
            api.grantByLogin.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 400, error: { code: 'PUBLICATION_REQUIRED' } })));
            await submit();
            expect(alert()).toContain('когда колода доступна по приглашению');
            expect(api.grants).toHaveBeenCalledTimes(1);
        });

        it('ignores a second submit while the login is checked', async () => {
            const pending = new Subject<AccessGrant>();
            api.grantByLogin.mockReturnValue(pending);
            await create();
            await typeLogin('anna.k');
            await submit();
            await submit();
            expect(api.grantByLogin).toHaveBeenCalledTimes(1);
            expect(button('app-access-people', 'Проверяем…').getAttribute('aria-busy')).toBe('true');
        });
    });
});
