import { HttpErrorResponse, HttpHeaders, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { NEVER, Subject, of, throwError } from 'rxjs';

import access from '../../../../../contracts/decks/access.json';
import forks from '../../../../../contracts/decks/forks.json';
import contract from '../../../../../contracts/decks/public-read.json';
import { AuthService, AuthStatus } from '../../auth.service';
import { AuthorCardsService } from '../../author-cards.service';
import { TourEvents } from '../../core/onboarding/tour-events';
import { AccessApiService } from '../own-decks/access/access-api.service';
import { ForkApiService } from '../own-decks/fork/fork-api.service';
import { isCanonicalCommandId } from '../own-decks/own-deck.models';
import { PublicDeckApiService } from './public-deck-api.service';
import { PublicDeckPageComponent } from './public-deck-page.component';
import { INVITE_STORAGE_KEY, INVITE_TTL_MS, stashInvite } from './invite-token-store';
import { publicDeckMatcher } from './public-deck-routing';
import { PublicDeck, PublicDeckFailure, parsePublicDeck, parsePublicExercisePage, parsePublicMaterialPage } from './public-deck.models';

const PUBLIC = parsePublicDeck(contract.summary.public, contract.summary.public.code);
const INVITE = parsePublicDeck(contract.summary.invitedGrantee, contract.summary.invitedGrantee.code);
const COPY_ID = forks.create.created.body.deckId;
const TOKEN = access.invites.redeem.request.body.token;
const publicUrl = `/d/${PUBLIC.code}/${contract.summary.public.slug}`;

function problem(status: number, code: string | null, extra: Record<string, unknown> = {}, headers: Record<string, string> = {}): HttpErrorResponse {
    return new HttpErrorResponse({ status, error: code === null ? null : { code, ...extra }, headers: new HttpHeaders(headers) });
}

describe('the actions of the public deck view: copy, invitation, access request', () => {
    let api: { summary: ReturnType<typeof vi.fn>; materials: ReturnType<typeof vi.fn>; exercises: ReturnType<typeof vi.fn>; material: ReturnType<typeof vi.fn> };
    let copies: { copy: ReturnType<typeof vi.fn> };
    let accessApi: { redeem: ReturnType<typeof vi.fn>; requestAccess: ReturnType<typeof vi.fn>; withdrawRequest: ReturnType<typeof vi.fn> };
    let status: ReturnType<typeof signal<AuthStatus>>;
    let harness: RouterTestingHarness;

    const root = () => harness.routeNativeElement as HTMLElement;
    const text = () => root().textContent ?? '';
    const router = () => TestBed.inject(Router);
    const slot = () => [...root().querySelectorAll('.primary-slot button, .primary-slot a')].map(element => element.textContent?.trim());
    const screen = () => root().querySelector('app-access-screen section.access');
    const screenActions = () => [...screen()!.querySelectorAll('.actions a, .actions button')].map(element => element.textContent?.trim());
    const button = (label: string) => [...root().querySelectorAll<HTMLButtonElement>('button')].find(element => element.textContent?.trim() === label)!;

    beforeEach(() => {
        sessionStorage.clear();
        created = false;
        status = signal<AuthStatus>('authenticated');
        api = { summary: vi.fn(), materials: vi.fn(), exercises: vi.fn(), material: vi.fn() };
        api.materials.mockReturnValue(of(parsePublicMaterialPage(contract.items.page.response, PUBLIC.code, 50)));
        api.exercises.mockReturnValue(of(parsePublicExercisePage(contract.exercises.page.response, PUBLIC.code, 50)));
        copies = { copy: vi.fn() };
        accessApi = { redeem: vi.fn(), requestAccess: vi.fn(), withdrawRequest: vi.fn() };
        TestBed.configureTestingModule({ providers: [
            provideRouter([{ matcher: publicDeckMatcher, component: PublicDeckPageComponent }, { path: 'login', children: [] },
                { path: 'decks/:deckId', children: [] }, { path: 'decks', children: [] }, { path: '', children: [] }]),
            provideHttpClient(), provideHttpClientTesting(),
            { provide: PublicDeckApiService, useValue: api },
            { provide: ForkApiService, useValue: copies },
            { provide: AccessApiService, useValue: accessApi },
            { provide: AuthService, useValue: { status, user: signal(null), restore: vi.fn().mockResolvedValue(undefined) } },
            { provide: AuthorCardsService, useValue: { card: vi.fn().mockResolvedValue(null), avatarUrl: vi.fn() } }
        ] });
    });

    let created = false;
    async function open(url: string): Promise<void> {
        // One harness per test: a second `open` navigates the same one (a new deck, or the same deck again with other answers).
        if (!created) { harness = await RouterTestingHarness.create(); created = true; } else await harness.navigateByUrl('/');
        await harness.navigateByUrl(url, PublicDeckPageComponent);
        await settle();
    }
    async function settle(): Promise<void> {
        for (let round = 0; round < 4; round++) {
            await new Promise(resolve => setTimeout(resolve, 0));
            harness.detectChanges();
        }
    }
    const withCopy = (myCopy: PublicDeck['myCopy']): PublicDeck => ({ ...PUBLIC, myCopy });

    describe('what the onboarding tour is told', () => {
        const heard = (): [string, unknown][] => {
            const seen: [string, unknown][] = [];
            TestBed.inject(TourEvents).listen((event, payload) => { seen.push([event, payload]); });
            return seen;
        };

        it('says that a deck was opened once its page is on the screen, so the community tour can go on', async () => {
            api.summary.mockReturnValue(of(PUBLIC));
            const seen = heard();
            await open(publicUrl);
            expect(seen).toContainEqual(['community.deck-opened', undefined]);
        });

        it('marks the deck summary and «Добавить к себе» as the places the tour points at', async () => {
            api.summary.mockReturnValue(of(PUBLIC));
            await open(publicUrl);
            expect(root().querySelector('header.hub-heading')!.getAttribute('data-tour-anchor')).toBe('community.deck');
            expect(button('Добавить к себе').getAttribute('data-tour-anchor')).toBe('community.add');
        });

        it('says that the deck was copied only when the copy worked, with the id of the copy', async () => {
            api.summary.mockReturnValue(of(PUBLIC));
            copies.copy.mockReturnValueOnce(throwError(() => problem(404, 'DECK_NOT_FOUND')));
            const seen = heard();
            await open(publicUrl);
            button('Добавить к себе').click();
            await settle();
            expect(seen.filter(([event]) => event === 'deck.copied')).toEqual([]);
            copies.copy.mockReturnValue(of({ commandId: 'x', deckId: COPY_ID, preparing: true, existing: false }));
            button('Добавить к себе').click();
            await settle();
            expect(seen.filter(([event]) => event === 'deck.copied')).toEqual([['deck.copied', { deckId: COPY_ID }]]);
        });

        it('opens the copy itself, unless a tour gate took the event and goes to the copy\'s hub on its own', async () => {
            api.summary.mockReturnValue(of(PUBLIC));
            copies.copy.mockReturnValue(of({ commandId: 'x', deckId: COPY_ID, preparing: true, existing: false }));
            const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
            await open(publicUrl);
            button('Добавить к себе').click();
            await settle();
            expect(navigate).toHaveBeenCalledWith(['/decks', COPY_ID]);
            navigate.mockClear();
            TestBed.inject(TourEvents).listen(() => true);
            await open(publicUrl);
            button('Добавить к себе').click();
            await settle();
            expect(navigate).not.toHaveBeenCalled();
            expect(button('Добавить к себе').disabled).toBe(false);
        });
    });

    describe('«Добавить к себе»', () => {
        it('is the primary action of a signed-in viewer who has no copy, and adds nothing for a guest', async () => {
            api.summary.mockReturnValue(of(PUBLIC));
            await open(publicUrl);
            expect(slot()).toEqual(['Добавить к себе']);
            expect(root().querySelector<HTMLElement>('.primary-slot button')?.className).toContain('primary');
            status.set('anonymous');
            await open(publicUrl);
            expect(slot()).toEqual(['Войдите, чтобы добавить колоду к себе']);
        });

        it('sends the source code with a fresh UUIDv4 command and opens the copy, whose hub shows it being prepared', async () => {
            api.summary.mockReturnValue(of(PUBLIC));
            copies.copy.mockReturnValue(of({ commandId: 'x', deckId: COPY_ID, preparing: true, existing: false }));
            await open(publicUrl);
            button('Добавить к себе').click();
            await settle();
            expect(copies.copy).toHaveBeenCalledTimes(1);
            expect(copies.copy.mock.calls[0][0]).toBe(PUBLIC.code);
            expect(isCanonicalCommandId(copies.copy.mock.calls[0][1])).toBe(true);
            expect(router().url).toBe(`/decks/${COPY_ID}`);
        });

        it('opens the copy that already exists when the server says so (a 200 with existing)', async () => {
            api.summary.mockReturnValue(of(PUBLIC));
            copies.copy.mockReturnValue(of({ commandId: 'x', deckId: COPY_ID, preparing: false, existing: true }));
            await open(publicUrl);
            button('Добавить к себе').click();
            await settle();
            expect(router().url).toBe(`/decks/${COPY_ID}`);
        });

        it('shows «Добавляем…» as a busy, still focusable button and ignores a second press', async () => {
            api.summary.mockReturnValue(of(PUBLIC));
            copies.copy.mockReturnValue(NEVER);
            await open(publicUrl);
            const add = button('Добавить к себе');
            add.click();
            await settle();
            expect(add.textContent?.trim()).toBe('Добавляем…');
            expect(add.getAttribute('aria-busy')).toBe('true');
            expect(add.getAttribute('aria-disabled')).toBe('true');
            expect(add.disabled).toBe(false);
            add.click();
            await settle();
            expect(copies.copy).toHaveBeenCalledTimes(1);
        });

        it('says «Открыть мою копию» instead, with a link to the copy, and a quiet line while it is still being prepared', async () => {
            api.summary.mockReturnValue(of(withCopy({ deckId: COPY_ID, preparing: true })));
            await open(publicUrl);
            expect(slot()).toEqual(['Открыть мою копию']);
            expect(root().querySelector<HTMLAnchorElement>('.primary-slot a')?.getAttribute('href')).toBe(`/decks/${COPY_ID}`);
            expect(root().querySelector('.copy-hint')?.textContent).toBe('Копия ещё готовится.');
            api.summary.mockReturnValue(of(withCopy({ deckId: COPY_ID, preparing: false })));
            await open(publicUrl);
            expect(root().querySelector('.copy-hint')).toBeNull();
        });

        it('keeps «Открыть в своих колодах» for the owner', async () => {
            api.summary.mockReturnValue(of({ ...PUBLIC, access: 'OWNER' as const }));
            await open(publicUrl);
            expect(slot()).toEqual(['Открыть в своих колодах']);
        });

        it('retries a lost answer as the same command, and starts a new command after a refusal', async () => {
            api.summary.mockReturnValue(of(PUBLIC));
            copies.copy.mockReturnValueOnce(throwError(() => problem(0, null)))
                .mockReturnValueOnce(throwError(() => problem(422, 'RESOURCE_LIMIT_EXCEEDED', { limit: 10 })))
                .mockReturnValueOnce(of({ commandId: 'x', deckId: COPY_ID, preparing: true, existing: false }));
            await open(publicUrl);
            button('Добавить к себе').click();
            await settle();
            expect(root().querySelector('.copy-notice')?.textContent).toBe('Не удалось добавить колоду. Проверьте соединение и повторите.');
            expect(root().querySelector('.copy-notice')?.getAttribute('role')).toBe('alert');
            button('Добавить к себе').click();
            await settle();
            expect(copies.copy.mock.calls[1][1]).toBe(copies.copy.mock.calls[0][1]);
            expect(root().querySelector('.copy-notice')?.textContent).toContain('не больше 10 копий');
            button('Добавить к себе').click();
            await settle();
            expect(copies.copy.mock.calls[2][1]).not.toBe(copies.copy.mock.calls[1][1]);
        });

        it('words the refusals plainly and never leaves the page', async () => {
            api.summary.mockReturnValue(of(PUBLIC));
            const cases: readonly (readonly [HttpErrorResponse, string])[] = [
                [problem(409, 'FORK_OWN_DECK'), 'Это ваша колода: добавлять её к себе не нужно.'],
                [problem(404, 'RESOURCE_NOT_FOUND'), 'Сейчас добавить колоду к себе нельзя. Попробуйте позже.'],
                [problem(429, 'RATE_LIMITED', {}, { 'Retry-After': '37' }), 'Слишком много запросов. Подождите 37 с и повторите.'],
                [problem(503, 'PUBLIC_READ_BUSY', {}, { 'Retry-After': '1' }), 'Сейчас много читателей. Повторите через секунду.'],
                [problem(500, null), 'Не удалось добавить колоду. Проверьте соединение и повторите.']
            ];
            await open(publicUrl);
            for (const [error, sentence] of cases) {
                copies.copy.mockReturnValueOnce(throwError(() => error));
                button('Добавить к себе').click();
                await settle();
                expect(root().querySelector('.copy-notice')?.textContent, sentence).toBe(sentence);
                expect(router().url).toBe(publicUrl);
            }
        });

        it('reads the deck again after a 404 or a 403, so a deck that was closed becomes the screen that is true', async () => {
            api.summary.mockReturnValueOnce(of(PUBLIC)).mockReturnValueOnce(throwError(() => new PublicDeckFailure('not-found')));
            copies.copy.mockReturnValue(throwError(() => problem(404, 'RESOURCE_NOT_FOUND')));
            await open(publicUrl);
            button('Добавить к себе').click();
            await settle();
            expect(api.summary).toHaveBeenCalledTimes(2);
            expect(screen()?.querySelector('h1')?.textContent).toBe('Колода не найдена');
        });
    });

    describe('an invitation link', () => {
        const inviteUrl = (code = PUBLIC.code) => `/d/${code}?invite=${TOKEN}`;

        it('redeems the token once the viewer is signed in, strips it from the address at once, and reads the deck', async () => {
            const redeemed = new Subject<string>();
            accessApi.redeem.mockReturnValue(redeemed);
            api.summary.mockReturnValue(of(PUBLIC));
            await open(inviteUrl());
            // Before the answer: the address is already clean and the token is only in the call.
            expect(router().url).not.toContain('invite');
            expect(accessApi.redeem).toHaveBeenCalledWith(TOKEN);
            expect(api.summary).not.toHaveBeenCalled();
            redeemed.next(PUBLIC.code);
            await settle();
            expect(api.summary).toHaveBeenCalledWith(PUBLIC.code);
            expect(root().querySelector('h1')?.textContent).toBe(PUBLIC.title);
            expect(router().url).not.toContain(TOKEN);
        });

        it('opens the code the server answers with when the deck has a newer one', async () => {
            accessApi.redeem.mockReturnValue(of(INVITE.code));
            api.summary.mockImplementation((code: string) => of(code === INVITE.code ? INVITE : PUBLIC));
            await open(inviteUrl());
            expect(router().url).toBe(`/d/${INVITE.code}`);
            expect(api.summary).toHaveBeenCalledWith(INVITE.code);
            expect(root().querySelector('h1')?.textContent).toBe(INVITE.title);
            expect(accessApi.redeem).toHaveBeenCalledTimes(1);
        });

        it('shows «Колода не найдена» for a dead invitation (404), and nothing about the deck', async () => {
            accessApi.redeem.mockReturnValue(throwError(() => problem(404, 'RESOURCE_NOT_FOUND')));
            await open(inviteUrl());
            expect(screen()?.querySelector('h1')?.textContent).toBe('Колода не найдена');
            expect(api.summary).not.toHaveBeenCalled();
            expect(router().url).toBe(`/d/${PUBLIC.code}`);
        });

        it('says plainly that a full deck has no room (422)', async () => {
            accessApi.redeem.mockReturnValue(throwError(() => problem(422, 'RESOURCE_LIMIT_EXCEEDED', { limit: 1000 })));
            await open(inviteUrl());
            expect(screen()?.querySelector('h1')?.textContent).toBe('В колоде нет мест');
            expect(screen()?.querySelector('.message')?.textContent).toBe('У колоды уже максимальное число читателей. Попросите автора освободить место.');
            expect(screenActions()).toEqual(['На главную']);
        });

        it('keeps the token in memory for «Повторить» after a 429, and spends it once', async () => {
            accessApi.redeem.mockReturnValueOnce(throwError(() => problem(429, 'RATE_LIMITED')))
                .mockReturnValueOnce(of(PUBLIC.code));
            api.summary.mockReturnValue(of(PUBLIC));
            await open(inviteUrl());
            expect(screen()?.querySelector('h1')?.textContent).toBe('Слишком много запросов');
            expect(router().url).not.toContain('invite');
            const retry = [...screen()!.querySelectorAll<HTMLButtonElement>('.actions button')][0];
            retry.click();
            await settle();
            expect(accessApi.redeem).toHaveBeenCalledTimes(2);
            expect(accessApi.redeem).toHaveBeenLastCalledWith(TOKEN);
            expect(root().querySelector('h1')?.textContent).toBe(PUBLIC.title);
        });

        it('does not redeem for a guest, strips the token from the address at once, and keeps it out of the login return address', async () => {
            status.set('anonymous');
            api.summary.mockReturnValue(throwError(() => new PublicDeckFailure('invite-only')));
            await open(inviteUrl(INVITE.code));
            expect(accessApi.redeem).not.toHaveBeenCalled();
            expect(router().url).toBe(`/d/${INVITE.code}`);
            const login = screen()?.querySelector<HTMLAnchorElement>('.actions a');
            expect(login?.textContent?.trim()).toBe('Войти');
            expect(login?.getAttribute('href')).toBe(`/login?returnUrl=${encodeURIComponent(`/d/${INVITE.code}`)}`);
            expect(login?.getAttribute('href')).not.toContain(TOKEN);
            expect(document.documentElement.innerHTML).not.toContain(TOKEN);
            // The one place the token waits: a short-lived entry of this tab.
            expect(JSON.parse(sessionStorage.getItem(INVITE_STORAGE_KEY)!)).toEqual({ token: TOKEN, code: INVITE.code, createdAt: expect.any(Number) });
            expect(JSON.stringify({ ...localStorage })).not.toContain(TOKEN);
        });

        it('tells an invited guest what signing in is for, next to the unchanged approved sentence, and says nothing to anyone else', async () => {
            status.set('anonymous');
            api.summary.mockReturnValue(throwError(() => new PublicDeckFailure('invite-only')));
            const note = () => screen()?.querySelector('.invite-note')?.textContent;
            await open(`/d/${INVITE.code}`);
            expect(note()).toBeUndefined();
            await open(inviteUrl(INVITE.code));
            expect(note()).toBe('Войдите, чтобы принять приглашение.');
            expect(screen()?.querySelector('.message p')?.textContent).toBe('Автор открыл эту колоду только приглашённым. Попросите доступ, и автор увидит ваш запрос.');
            // Reloaded without the query: the stash alone is enough, and looking at it consumes nothing.
            await open(`/d/${INVITE.code}`);
            expect(note()).toBe('Войдите, чтобы принять приглашение.');
            expect(sessionStorage.getItem(INVITE_STORAGE_KEY)).not.toBeNull();
            status.set('authenticated');
            accessApi.redeem.mockReturnValue(NEVER);
            await open(`/d/${INVITE.code}`);
            expect(root().querySelector('.invite-note')).toBeNull();
        });

        it('says «Принимаем приглашение…» while the invitation is being redeemed', async () => {
            accessApi.redeem.mockReturnValue(NEVER);
            await open(inviteUrl());
            expect(root().querySelector('[role=status]')?.textContent).toBe('Принимаем приглашение…');
            expect(text()).not.toContain('Загружаем колоду…');
        });

        it('spends the stashed token once after the sign-in returns: deleted before the request, never redeemed twice', async () => {
            status.set('anonymous');
            api.summary.mockReturnValue(throwError(() => new PublicDeckFailure('invite-only')));
            await open(inviteUrl(INVITE.code));
            status.set('authenticated');
            const redeemed = new Subject<string>();
            accessApi.redeem.mockImplementation(() => {
                // Already gone while the request is on its way.
                expect(sessionStorage.getItem(INVITE_STORAGE_KEY)).toBeNull();
                return redeemed;
            });
            api.summary.mockReturnValue(of(INVITE));
            await open(`/d/${INVITE.code}`);
            expect(accessApi.redeem).toHaveBeenCalledTimes(1);
            expect(accessApi.redeem).toHaveBeenCalledWith(TOKEN);
            redeemed.next(INVITE.code);
            await settle();
            expect(root().querySelector('h1')?.textContent).toBe(INVITE.title);
            await open(`/d/${INVITE.code}`);
            expect(accessApi.redeem).toHaveBeenCalledTimes(1);
        });

        it('ignores a stashed token that is older than ten minutes, and deletes it', async () => {
            stashInvite(sessionStorage, TOKEN, INVITE.code, Date.now() - INVITE_TTL_MS - 1000);
            api.summary.mockReturnValue(of(INVITE));
            await open(`/d/${INVITE.code}`);
            expect(accessApi.redeem).not.toHaveBeenCalled();
            expect(sessionStorage.getItem(INVITE_STORAGE_KEY)).toBeNull();
        });

        it('leaves a token stashed for another deck alone', async () => {
            stashInvite(sessionStorage, TOKEN, PUBLIC.code);
            api.summary.mockReturnValue(of(INVITE));
            await open(`/d/${INVITE.code}`);
            expect(accessApi.redeem).not.toHaveBeenCalled();
            expect(sessionStorage.getItem(INVITE_STORAGE_KEY)).not.toBeNull();
        });

        it('works for a guest without storage: the address is clean and nothing is stored', async () => {
            status.set('anonymous');
            vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked'); });
            api.summary.mockReturnValue(throwError(() => new PublicDeckFailure('invite-only')));
            await open(inviteUrl(INVITE.code));
            expect(router().url).toBe(`/d/${INVITE.code}`);
            expect(screen()?.querySelector('h1')?.textContent).toBe('Колода доступна по приглашению');
        });

        it('ignores and strips a token that cannot be one', async () => {
            api.summary.mockReturnValue(of(PUBLIC));
            await open(`/d/${PUBLIC.code}?invite=not-a-token`);
            expect(accessApi.redeem).not.toHaveBeenCalled();
            expect(router().url).not.toContain('invite');
            expect(root().querySelector('h1')?.textContent).toBe(PUBLIC.title);
        });

        it('never puts the token in the title, the page, storage or the console', async () => {
            const logs = [vi.spyOn(console, 'log'), vi.spyOn(console, 'info'), vi.spyOn(console, 'warn'), vi.spyOn(console, 'error'), vi.spyOn(console, 'debug')];
            accessApi.redeem.mockReturnValue(of(PUBLIC.code));
            api.summary.mockReturnValue(of(PUBLIC));
            await open(inviteUrl());
            expect(document.title).not.toContain(TOKEN);
            expect(document.documentElement.innerHTML).not.toContain(TOKEN);
            for (const storage of [localStorage, sessionStorage]) expect(JSON.stringify({ ...storage })).not.toContain(TOKEN);
            for (const log of logs) expect(JSON.stringify(log.mock.calls)).not.toContain(TOKEN);
        });
    });

    describe('«Запросить доступ» on the invitation-only screen', () => {
        const asked = () => root().querySelector('.request-sent');

        beforeEach(() => {
            api.summary.mockReturnValue(throwError(() => new PublicDeckFailure('invite-only')));
        });

        it('offers the request to a signed-in viewer, with the way home, and «Войти» only to a guest', async () => {
            await open(`/d/${INVITE.code}`);
            expect(screenActions()).toEqual(['Запросить доступ', 'На главную']);
            status.set('anonymous');
            await open(`/d/${INVITE.code}`);
            expect(screenActions()).toEqual(['Войти', 'На главную']);
        });

        it('sends the request for the code and answers with the approved sentence, focused, plus «Отменить запрос»', async () => {
            accessApi.requestAccess.mockReturnValue(of('PENDING'));
            await open(`/d/${INVITE.code}`);
            button('Запросить доступ').click();
            await settle();
            expect(accessApi.requestAccess).toHaveBeenCalledWith(INVITE.code);
            expect(asked()?.textContent).toBe('Запрос отправлен. Если автор согласится, колода появится по этой же ссылке.');
            expect(document.activeElement).toBe(asked());
            expect(screenActions()).toEqual(['Отменить запрос', 'На главную']);
        });

        it('shows a busy, focusable button while the request is on its way', async () => {
            accessApi.requestAccess.mockReturnValue(NEVER);
            await open(`/d/${INVITE.code}`);
            button('Запросить доступ').click();
            await settle();
            const sending = button('Отправляем…');
            expect(sending.getAttribute('aria-busy')).toBe('true');
            expect(sending.getAttribute('aria-disabled')).toBe('true');
            sending.click();
            await settle();
            expect(accessApi.requestAccess).toHaveBeenCalledTimes(1);
        });

        it('takes the request back and says so, and the viewer can ask again', async () => {
            accessApi.requestAccess.mockReturnValue(of('PENDING'));
            accessApi.withdrawRequest.mockReturnValue(of(undefined));
            await open(`/d/${INVITE.code}`);
            button('Запросить доступ').click();
            await settle();
            button('Отменить запрос').click();
            await settle();
            expect(accessApi.withdrawRequest).toHaveBeenCalledWith(INVITE.code);
            expect(asked()).toBeNull();
            expect(root().querySelector('.request-notice')?.textContent).toBe('Запрос отменён.');
            expect(root().querySelector('.request-notice')?.getAttribute('role')).toBe('status');
            expect(screenActions()).toEqual(['Запросить доступ', 'На главную']);
            expect(document.activeElement).toBe(button('Запросить доступ'));
        });

        it('keeps the request when taking it back fails', async () => {
            accessApi.requestAccess.mockReturnValue(of('PENDING'));
            accessApi.withdrawRequest.mockReturnValue(throwError(() => problem(0, null)));
            await open(`/d/${INVITE.code}`);
            button('Запросить доступ').click();
            await settle();
            button('Отменить запрос').click();
            await settle();
            expect(asked()).not.toBeNull();
            expect(root().querySelector('.request-error')?.textContent).toBe('Не удалось отменить запрос. Повторите.');
            expect(root().querySelector('.request-error')?.getAttribute('role')).toBe('alert');
            expect(root().querySelector('.request-error')?.classList.contains('notice')).toBe(true);
        });

        it('answers an author who turned requests off (403 DECK_INVITE_ONLY, or an empty 403) exactly like a request that was sent', async () => {
            for (const error of [problem(403, null), problem(403, 'DECK_INVITE_ONLY')]) {
                accessApi.requestAccess.mockReturnValueOnce(throwError(() => error));
                await open(`/d/${INVITE.code}`);
                button('Запросить доступ').click();
                await settle();
                expect(asked()?.textContent).toBe('Запрос отправлен. Если автор согласится, колода появится по этой же ссылке.');
                expect(screenActions()).toEqual(['Отменить запрос', 'На главную']);
            }
        });

        it('treats any other 403 (a token without scope, for example) as a failure, never as a sent request', async () => {
            accessApi.requestAccess.mockReturnValue(throwError(() => problem(403, 'ACCESS_DENIED')));
            await open(`/d/${INVITE.code}`);
            button('Запросить доступ').click();
            await settle();
            expect(asked()).toBeNull();
            expect(root().querySelector('.request-error')?.textContent).toBe('Не удалось отправить запрос. Проверьте соединение и повторите.');
            expect(root().querySelector('.request-notice')?.textContent).toBe('');
            expect(screenActions()).toEqual(['Запросить доступ', 'На главную']);
        });

        it('says plainly that there are too many open requests (422), how long to wait (429) and that the connection failed', async () => {
            await open(`/d/${INVITE.code}`);
            const cases: readonly (readonly [HttpErrorResponse, string])[] = [
                [problem(422, 'RESOURCE_LIMIT_EXCEEDED', { limit: 20 }), 'У вас уже слишком много открытых запросов. Дождитесь ответов или отмените лишние.'],
                [problem(429, 'RATE_LIMITED', {}, { 'Retry-After': '3600' }), 'Слишком много запросов. Подождите 60 мин и повторите.'],
                [problem(429, 'RATE_LIMITED', {}, { 'Retry-After': '45' }), 'Слишком много запросов. Подождите 45 с и повторите.'],
                [problem(0, null), 'Не удалось отправить запрос. Проверьте соединение и повторите.']
            ];
            for (const [error, sentence] of cases) {
                accessApi.requestAccess.mockReturnValueOnce(throwError(() => error));
                button('Запросить доступ').click();
                await settle();
                expect(root().querySelector('.request-error')?.textContent, sentence).toBe(sentence);
                expect(root().querySelector('.request-error')?.getAttribute('role')).toBe('alert');
                expect(asked()).toBeNull();
            }
        });

        it('turns into «Колода не найдена» when the code is gone (404), and reads the deck again when there is nothing to ask for (400)', async () => {
            accessApi.requestAccess.mockReturnValueOnce(throwError(() => problem(400, 'INVALID_REQUEST')))
                .mockReturnValueOnce(throwError(() => problem(404, 'RESOURCE_NOT_FOUND')));
            await open(`/d/${INVITE.code}`);
            api.summary.mockReturnValue(of(INVITE));
            button('Запросить доступ').click();
            await settle();
            expect(root().querySelector('h1')?.textContent).toBe(INVITE.title);
            api.summary.mockReturnValue(throwError(() => new PublicDeckFailure('invite-only')));
            await open(`/d/${INVITE.code}`);
            button('Запросить доступ').click();
            await settle();
            expect(screen()?.querySelector('h1')?.textContent).toBe('Колода не найдена');
        });
    });
});
