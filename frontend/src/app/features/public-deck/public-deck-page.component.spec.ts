import { signal } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { Subject, of, throwError } from 'rxjs';

import contract from '../../../../../contracts/decks/public-read.json';
import { AuthService, AuthStatus } from '../../auth.service';
import { AuthorCardsService } from '../../author-cards.service';
import { AutoLoadComponent } from '../../shared/auto-load.component';
import { SHARE_MENU_ITEM, ShareLinkService } from '../../shared/share-link.service';
import { PublicDeckApiService } from './public-deck-api.service';
import { PublicDeckPageComponent } from './public-deck-page.component';
import { publicDeckMatcher } from './public-deck-routing';
import {
    PublicDeck,
    PublicDeckFailure,
    parsePublicDeck,
    parsePublicExercisePage,
    parsePublicMaterialDocument,
    parsePublicMaterialPage
} from './public-deck.models';
import { By } from '@angular/platform-browser';

const PUBLIC = parsePublicDeck(contract.summary.public, contract.summary.public.code);
const LINK = parsePublicDeck(contract.summary.link, contract.summary.link.code);
const INVITE = parsePublicDeck(contract.summary.invitedGrantee, contract.summary.invitedGrantee.code);
const SLUG = contract.summary.public.slug;
const MEMBER = contract.items.document.response.memberKey;
const AUTHOR = { accountId: contract.summary.public.ownerId, profileUsername: 'anna.k', displayName: null, bio: null, avatarPresent: true };

describe('PublicDeckPageComponent', () => {
    let api: { summary: ReturnType<typeof vi.fn>; materials: ReturnType<typeof vi.fn>; exercises: ReturnType<typeof vi.fn>; material: ReturnType<typeof vi.fn> };
    let status: ReturnType<typeof signal<AuthStatus>>;
    let restore: ReturnType<typeof vi.fn>;
    let authors: { card: ReturnType<typeof vi.fn>; avatarUrl: ReturnType<typeof vi.fn> };
    let sharing: { share: ReturnType<typeof vi.fn> };
    let harness: RouterTestingHarness;

    const meta = (name: string) => document.head.querySelector<HTMLMetaElement>(`meta[name=${name}]`);
    const root = () => harness.routeNativeElement as HTMLElement;
    const text = () => root().textContent ?? '';
    const router = () => TestBed.inject(Router);

    beforeEach(() => {
        document.head.querySelectorAll('meta[name=robots], meta[name=referrer]').forEach(element => element.remove());
        status = signal<AuthStatus>('anonymous');
        restore = vi.fn().mockResolvedValue(undefined);
        api = { summary: vi.fn(), materials: vi.fn(), exercises: vi.fn(), material: vi.fn() };
        api.materials.mockReturnValue(of(parsePublicMaterialPage(contract.items.page.response, PUBLIC.code, 50)));
        api.exercises.mockReturnValue(of(parsePublicExercisePage(contract.exercises.page.response, PUBLIC.code, 50)));
        authors = { card: vi.fn().mockResolvedValue(AUTHOR), avatarUrl: vi.fn().mockReturnValue('https://identity.test/profiles/x/avatar') };
        sharing = { share: vi.fn().mockResolvedValue('copied') };
        TestBed.configureTestingModule({ providers: [
            provideRouter([{ matcher: publicDeckMatcher, component: PublicDeckPageComponent }, { path: 'login', children: [] }, { path: 'decks', children: [] }, { path: '', children: [] }]),
            provideHttpClient(), provideHttpClientTesting(),
            { provide: PublicDeckApiService, useValue: api },
            { provide: AuthService, useValue: { status, restore } },
            { provide: AuthorCardsService, useValue: authors },
            { provide: ShareLinkService, useValue: sharing }
        ] });
    });

    async function open(url: string): Promise<void> {
        harness = await RouterTestingHarness.create();
        await harness.navigateByUrl(url, PublicDeckPageComponent);
        await settle();
    }
    async function settle(): Promise<void> {
        for (let round = 0; round < 3; round++) {
            await new Promise(resolve => setTimeout(resolve, 0));
            harness.detectChanges();
        }
    }
    const publicUrl = `/d/${PUBLIC.code}/${SLUG}`;

    describe('a PUBLIC deck for a guest', () => {
        beforeEach(async () => {
            api.summary.mockReturnValue(of(PUBLIC));
            await open(publicUrl);
        });

        it('shows the read-only hub: title, description, author, counts, publication date and the level mark', () => {
            expect(root().querySelector('h1')?.textContent).toBe(PUBLIC.title);
            expect(text()).toContain(PUBLIC.description);
            expect(root().querySelector('app-author-chip .login')?.textContent).toBe('@anna.k');
            expect(authors.card).toHaveBeenCalledWith(PUBLIC.ownerId);
            expect(root().querySelector('.facts')?.textContent).toContain('2 материала · 1 упражнение');
            expect(root().querySelector('.facts time')?.textContent).toMatch(/^Опубликовано \d+ октября/u);
            expect(root().querySelector('.facts .stamp')?.textContent).toContain('Доступ: Публичная');
            expect(api.summary).toHaveBeenCalledWith(PUBLIC.code);
        });

        it('has no Study, no editing and no Workshop, and no node ids', () => {
            const labels = [...root().querySelectorAll('a, button')].map(element => element.textContent?.trim());
            for (const forbidden of ['Учить', 'Изменить', 'Редактировать', 'Мастерская', 'Добавить материал', 'Пожаловаться']) {
                expect(labels.some(label => label?.includes(forbidden)), forbidden).toBe(false);
            }
            expect(root().querySelector('[data-node-id]')).toBeNull();
        });

        it('offers «Поделиться» as a button and in the «⋯» menu, and nothing else in the menu', () => {
            expect([...root().querySelectorAll('button')].map(button => button.textContent?.trim())).toContain('Поделиться');
            const menu = root().querySelector('app-action-menu')!;
            expect(menu.querySelector('button.trigger')?.getAttribute('aria-label')).toBe(`Действия с колодой «${PUBLIC.title}»`);
            expect([...menu.querySelectorAll('[role=menuitem]')].map(item => item.textContent?.trim())).toEqual(['Поделиться']);
        });

        it('shares the canonical address with the slug', async () => {
            sharing.share.mockResolvedValueOnce('failed');
            root().querySelector<HTMLButtonElement>('app-action-menu [role=menuitem]')!.click();
            await settle();
            expect(sharing.share).toHaveBeenCalledWith(`${window.location.origin}/d/${PUBLIC.code}/${SLUG}`, PUBLIC.title);
            expect(root().querySelector('app-share-link-field input')?.getAttribute('type')).toBe('url');
        });

        it('invites a guest to sign in with this very view as the return address, and draws no dead button', () => {
            const link = root().querySelector<HTMLAnchorElement>('.primary-slot a')!;
            expect(link.textContent?.trim()).toBe('Войдите, чтобы добавить колоду к себе');
            expect(link.getAttribute('href')).toBe(`/login?returnUrl=${encodeURIComponent(publicUrl)}`);
            expect(root().querySelectorAll('.primary-slot button')).toHaveLength(0);
        });

        it('lists the materials with links that open them in this page', () => {
            const links = [...root().querySelectorAll<HTMLAnchorElement>('app-public-materials-list .row-link')];
            expect(links.map(link => link.textContent)).toEqual([contract.items.page.response.items[0].title]);
            expect(links[0].getAttribute('href')).toBe(`${publicUrl}?material=${contract.items.page.response.items[0].memberKey}`);
        });

        it('switches to the exercise preview without a page change', async () => {
            const exercises = root().querySelectorAll<HTMLInputElement>('app-segmented-choice input[type=radio]');
            expect(exercises).toHaveLength(2);
            exercises[1].click();
            exercises[1].dispatchEvent(new Event('change'));
            await settle();
            expect(root().querySelector('app-public-exercises-list')).not.toBeNull();
            expect(root().querySelector('app-public-materials-list')).toBeNull();
            expect(root().querySelector('app-public-exercises-list .prompt')?.textContent).toBe(contract.exercises.page.response.exercises[0].prompt);
            expect(router().url).toBe(publicUrl);
            // The shared component announces the change through its live hint.
            expect(root().querySelector('app-segmented-choice .hint[aria-live=polite]')?.textContent).toBe('Показаны упражнения: 1');
        });

        it('keeps the head private while the route is active: no referrer, indexable only because the deck is PUBLIC', () => {
            expect(meta('referrer')?.content).toBe('no-referrer');
            expect(meta('robots')?.content ?? 'index').not.toContain('noindex');
            expect(document.title).toBe(`${PUBLIC.title} — Mnema`);
        });

        it('removes the referrer policy when the route is left', async () => {
            await router().navigateByUrl('/');
            await settle();
            expect(meta('referrer')).toBeNull();
            expect(meta('robots')).toBeNull();
        });
    });

    describe('the canonical address', () => {
        it('replaces a missing slug without adding a history entry or reloading the deck', async () => {
            api.summary.mockReturnValue(of(PUBLIC));
            await open(`/d/${PUBLIC.code}`);
            expect(router().url).toBe(publicUrl);
            expect(api.summary).toHaveBeenCalledTimes(1);
            expect(root().querySelector('h1')?.textContent).toBe(PUBLIC.title);
        });

        it('replaces a wrong slug and keeps the open material', async () => {
            api.summary.mockReturnValue(of(PUBLIC));
            api.material.mockReturnValue(of(parsePublicMaterialDocument(contract.items.document.response, PUBLIC.code, MEMBER)));
            await open(`/d/${PUBLIC.code}/old-title?material=${MEMBER}`);
            expect(router().url).toBe(`${publicUrl}?material=${MEMBER}`);
            expect(api.summary).toHaveBeenCalledTimes(1);
        });

        it('leaves a correct slug alone', async () => {
            api.summary.mockReturnValue(of(PUBLIC));
            const navigate = vi.spyOn(TestBed.inject(Router), 'navigate');
            await open(publicUrl);
            expect(navigate).not.toHaveBeenCalled();
        });

        it('never shows a slug for a LINK or an invited deck, and drops one that was typed', async () => {
            api.summary.mockReturnValue(of(LINK));
            await open(`/d/${LINK.code}/some-title`);
            expect(router().url).toBe(`/d/${LINK.code}`);
        });
    });

    describe('a LINK deck and an invitation', () => {
        it('is not indexed and shows the «По ссылке» mark', async () => {
            api.summary.mockReturnValue(of(LINK));
            await open(`/d/${LINK.code}`);
            expect(meta('robots')?.content).toBe('noindex');
            expect(meta('referrer')?.content).toBe('no-referrer');
            expect(root().querySelector('.facts .stamp')?.textContent).toContain('По ссылке');
            expect(root().querySelector('h1')?.textContent).toBe(LINK.title);
        });

        it('shows an invited grantee the deck, marked «По приглашению» and not indexed', async () => {
            status.set('authenticated');
            api.summary.mockReturnValue(of(INVITE));
            await open(`/d/${INVITE.code}`);
            expect(root().querySelector('.facts .stamp')?.textContent).toContain('По приглашению');
            expect(meta('robots')?.content).toBe('noindex');
        });
    });

    describe('signed in', () => {
        it('draws no guest invitation and no dead «Добавить к себе» (that button is Share/10)', async () => {
            status.set('authenticated');
            api.summary.mockReturnValue(of(PUBLIC));
            await open(publicUrl);
            expect(root().querySelector('.primary-slot')?.children).toHaveLength(0);
            expect(text()).not.toContain('Войдите');
            expect(text()).not.toContain('Добавить к себе');
        });

        it('asks for the deck only after the session is restored, so the bearer is on the first request', async () => {
            let release!: () => void;
            restore.mockReturnValue(new Promise<void>(resolve => { release = resolve; }));
            api.summary.mockReturnValue(of(PUBLIC));
            harness = await RouterTestingHarness.create();
            await harness.navigateByUrl(publicUrl, PublicDeckPageComponent);
            await settle();
            expect(api.summary).not.toHaveBeenCalled();
            expect(root().querySelector('h1')?.textContent).toBe('Колода');
            expect(root().querySelector('[role=status]')?.textContent).toBe('Загружаем колоду…');
            release();
            await settle();
            expect(api.summary).toHaveBeenCalledTimes(1);
            expect(root().querySelector('h1')?.textContent).toBe(PUBLIC.title);
        });

        it('shows the owner the same view plus a way to his own decks', async () => {
            status.set('authenticated');
            api.summary.mockReturnValue(of({ ...PUBLIC, access: 'OWNER' } satisfies PublicDeck));
            await open(publicUrl);
            const link = root().querySelector<HTMLAnchorElement>('.primary-slot a')!;
            expect(link.textContent?.trim()).toBe('Открыть в своих колодах');
            expect(link.getAttribute('href')).toBe('/decks');
            expect(root().querySelector('h1')?.textContent).toBe(PUBLIC.title);
            expect(text()).not.toContain('Войдите');
        });
    });

    describe('the author chip', () => {
        it('is hidden when the author has no public card, and when the card cannot be read', async () => {
            api.summary.mockReturnValue(of(PUBLIC));
            authors.card.mockResolvedValue(null);
            await open(publicUrl);
            expect(root().querySelector('app-author-chip')).toBeNull();
        });

        it('stays out of the way when Identity fails', async () => {
            api.summary.mockReturnValue(of(PUBLIC));
            authors.card.mockRejectedValue(new Error('offline'));
            await open(publicUrl);
            expect(root().querySelector('app-author-chip')).toBeNull();
            expect(root().querySelector('h1')?.textContent).toBe(PUBLIC.title);
        });
    });

    describe('one material', () => {
        beforeEach(async () => {
            api.summary.mockReturnValue(of(PUBLIC));
            api.material.mockReturnValue(of(parsePublicMaterialDocument(contract.items.document.response, PUBLIC.code, MEMBER)));
            await open(`${publicUrl}?material=${MEMBER}`);
        });

        it('draws the document with the native renderer, read-only, with the way back to the deck', () => {
            expect(api.material).toHaveBeenCalledWith(PUBLIC.code, MEMBER);
            expect(root().querySelector('article.paper-surface app-native-document-renderer [data-native-render-state=ready]')).not.toBeNull();
            expect(text()).toContain('ser — быть (постоянное свойство)');
            expect(root().querySelector('[data-node-id]')).toBeNull();
            expect(root().querySelector('h2#public-material-heading')?.textContent).toContain('Материал 1 из 2');
            expect(root().querySelector('a.back-link')?.textContent).toContain('К колоде');
            expect(root().querySelector('a.back-link')?.getAttribute('href')).toBe(publicUrl);
            expect(root().querySelector('app-public-materials-list')?.hasAttribute('hidden')).toBe(true);
            expect(root().querySelector('textarea, [contenteditable], .ProseMirror')).toBeNull();
        });

        it('keeps the description and counts for the hub only', () => {
            expect(text()).not.toContain(PUBLIC.description);
            expect(root().querySelector('.facts')?.textContent).not.toContain('материала');
        });
    });


    describe('material navigation', () => {
        const keys = (index: number) => `7f1c2d3e-4a5b-4c6d-8e7f-${String(index).padStart(12, '0')}`;
        const rows = (count: number, nextCursor: string | null) => parsePublicMaterialPage({
            code: PUBLIC.code, total: 3, nextCursor,
            items: Array.from({ length: count }, (_, index) => ({ memberKey: keys(index), itemRevisionId: keys(index + 500), ordinal: index, title: `Тема ${index + 1}` }))
        }, PUBLIC.code, 50);
        const open2 = (index: number) => parsePublicMaterialDocument({ ...contract.items.document.response, memberKey: keys(index), ordinal: index }, PUBLIC.code, keys(index));

        beforeEach(() => {
            api.summary.mockReturnValue(of({ ...PUBLIC, memberCount: 3 }));
            api.materials.mockReturnValue(of(rows(3, null)));
            api.material.mockImplementation((_code: string, key: string) => of(open2(Number(key.slice(-12)))));
        });

        it('keeps the list mounted and hidden while a material is open, so Back neither reloads nor loses the rows', async () => {
            await open(publicUrl);
            const list = root().querySelector('app-public-materials-list')!;
            expect(list.hasAttribute('hidden')).toBe(false);
            root().querySelector<HTMLAnchorElement>('.row-link')!.click();
            await settle();
            expect(router().url).toBe(`${publicUrl}?material=${keys(0)}`);
            expect(root().querySelector('app-public-materials-list')).toBe(list);
            expect(list.hasAttribute('hidden')).toBe(true);
            root().querySelector<HTMLAnchorElement>('a.back-link')!.click();
            await settle();
            expect(root().querySelector('app-public-materials-list')).toBe(list);
            expect(list.hasAttribute('hidden')).toBe(false);
            expect(api.materials).toHaveBeenCalledTimes(1);
            expect(list.querySelectorAll('.row-link')).toHaveLength(3);
        });

        it('focuses the heading of a material when it opens, and the row that was opened when the reader goes back', async () => {
            await open(publicUrl);
            root().querySelectorAll<HTMLAnchorElement>('.row-link')[1].click();
            await settle();
            const heading = root().querySelector('h2#public-material-heading')!;
            expect(heading.textContent).toContain('Материал 2 из 3');
            expect(heading.getAttribute('tabindex')).toBe('-1');
            expect(document.activeElement).toBe(heading);
            root().querySelector<HTMLAnchorElement>('a.back-link')!.click();
            await settle();
            // The row waits for the router's own scrolling (its Scroll event, or 250 ms at most).
            await new Promise(resolve => setTimeout(resolve, 300));
            expect(document.activeElement).toBe(root().querySelectorAll('.row-link')[1]);
        });

        it('offers the material before and the one after, and moves between them without leaving the page', async () => {
            await open(`${publicUrl}?material=${keys(1)}`);
            const links = [...root().querySelectorAll<HTMLAnchorElement>('nav.siblings a')];
            expect(links.map(link => link.getAttribute('rel'))).toEqual(['prev', 'next']);
            expect(links[0].textContent).toContain('Предыдущий материал: Тема 1');
            expect(links[1].textContent).toContain('Следующий материал: Тема 3');
            links[1].click();
            await settle();
            expect(router().url).toBe(`${publicUrl}?material=${keys(2)}`);
            expect(root().querySelector('h2#public-material-heading')?.textContent).toContain('Материал 3 из 3');
            expect([...root().querySelectorAll('nav.siblings a')].map(link => link.getAttribute('rel'))).toEqual(['prev']);
            expect(api.materials).toHaveBeenCalledTimes(1);
        });

        it('reads on until it knows the neighbours of a material that is past the first page', async () => {
            api.materials.mockReturnValueOnce(of(rows(2, 'p2'))).mockReturnValueOnce(of(parsePublicMaterialPage({
                code: PUBLIC.code, total: 3, nextCursor: null,
                items: [{ memberKey: keys(2), itemRevisionId: keys(502), ordinal: 2, title: 'Тема 3' }]
            }, PUBLIC.code, 50)));
            await open(`${publicUrl}?material=${keys(1)}`);
            expect(api.materials).toHaveBeenCalledTimes(2);
            expect(root().querySelector('nav.siblings a[rel=next]')?.textContent).toContain('Тема 3');
        });

        it('draws no navigation for a material the list does not know', async () => {
            api.materials.mockReturnValue(of(rows(3, null)));
            api.material.mockReturnValue(of(open2(1)));
            await open(`${publicUrl}?material=${keys(9)}`);
            expect(root().querySelector('nav.siblings')).toBeNull();
        });
    });

    describe('a material that is not there', () => {
        it('is a calm notice with the way back, not a page-wide error', async () => {
            api.summary.mockReturnValue(of(PUBLIC));
            api.material.mockReturnValue(throwError(() => new PublicDeckFailure('not-found')));
            await open(`${publicUrl}?material=${MEMBER}`);
            expect(root().querySelector('h1')?.textContent).toBe(PUBLIC.title);
            expect(root().querySelector('app-public-material [role=status]')?.textContent).toContain('Такого материала в этой колоде нет');
            expect(root().querySelector('a.back-link')).not.toBeNull();
        });
    });

    describe('access screens', () => {
        const screen = () => root().querySelector('app-access-screen section.access');

        it('says «Колода не найдена» for a deck that is not there, with no Community link yet', async () => {
            api.summary.mockReturnValue(throwError(() => new PublicDeckFailure('not-found')));
            await open(`/d/${PUBLIC.code}`);
            expect(screen()?.querySelector('h1')?.textContent).toBe('Колода не найдена');
            expect(screen()?.querySelector('.message')?.textContent?.replace(/\s+/gu, ' ').trim()).toBe('Такой колоды нет, или автор её скрыл. Проверьте ссылку.');
            expect(screen()?.querySelector('.actions a')?.getAttribute('href')).toBe('/');
            expect(meta('robots')?.content).toBe('noindex');
            expect(document.activeElement).toBe(screen()?.querySelector('h1'));
        });

        it('shows the not-found screen for a malformed code in the address', async () => {
            api.summary.mockReturnValue(throwError(() => new PublicDeckFailure('not-found')));
            await open('/d/short');
            expect(screen()?.querySelector('h1')?.textContent).toBe('Колода не найдена');
        });

        it('says «Колода доступна по приглашению» without a request button, and offers a guest to sign in', async () => {
            api.summary.mockReturnValue(throwError(() => new PublicDeckFailure('invite-only')));
            await open(`/d/${INVITE.code}`);
            expect(screen()?.querySelector('h1')?.textContent).toBe('Колода доступна по приглашению');
            expect(screen()?.querySelector('.message')?.textContent).toBe('Автор открыл эту колоду только приглашённым. Попросите доступ, и автор увидит ваш запрос.');
            const actions = [...screen()!.querySelectorAll('.actions a, .actions button')].map(element => element.textContent?.trim());
            expect(actions).toEqual(['Войти', 'На главную']);
            expect(text()).not.toContain('Запросить доступ');
            expect(screen()?.querySelector<HTMLAnchorElement>('.actions a')?.getAttribute('href')).toBe(`/login?returnUrl=${encodeURIComponent(`/d/${INVITE.code}`)}`);
            expect(meta('robots')?.content).toBe('noindex');
            expect(root().textContent).not.toContain(INVITE.title);
        });

        it('does not ask a signed-in viewer to sign in again', async () => {
            status.set('authenticated');
            api.summary.mockReturnValue(throwError(() => new PublicDeckFailure('invite-only')));
            await open(`/d/${INVITE.code}`);
            expect([...screen()!.querySelectorAll('.actions a, .actions button')].map(element => element.textContent?.trim())).toEqual(['На главную']);
        });

        it('says how long to wait after 429, keeps «Повторить» aria-disabled until then, and retries once it passes', async () => {
            vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
            api.summary.mockReturnValueOnce(throwError(() => new PublicDeckFailure('rate-limited', 3))).mockReturnValueOnce(of(PUBLIC));
            await open(publicUrl);
            expect(screen()?.querySelector('h1')?.textContent).toBe('Слишком много запросов');
            const button = screen()!.querySelector<HTMLButtonElement>('.actions button')!;
            expect(screen()?.querySelector('.message p')?.textContent).toBe('Подождите 3 с и повторите.');
            expect(button.getAttribute('aria-disabled')).toBe('true');
            button.click();
            expect(api.summary).toHaveBeenCalledTimes(1);
            vi.advanceTimersByTime(1000);
            harness.detectChanges();
            expect(screen()?.querySelector('.message p')?.textContent).toBe('Подождите 2 с и повторите.');
            vi.advanceTimersByTime(2000);
            harness.detectChanges();
            expect(screen()?.querySelector('.message p')?.textContent).toBe('Можно повторить.');
            expect(screen()?.querySelector('.message [role=status]')?.textContent).toBe('Можно повторить.');
            expect(button.getAttribute('aria-disabled')).toBeNull();
            button.click();
            await settle();
            expect(root().querySelector('h1')?.textContent).toBe(PUBLIC.title);
            expect(api.summary).toHaveBeenCalledTimes(2);
            vi.useRealTimers();
        });

        it('does not disable the retry when the server named no delay', async () => {
            api.summary.mockReturnValue(throwError(() => new PublicDeckFailure('rate-limited')));
            await open(publicUrl);
            expect(screen()?.querySelector('.message p')?.textContent).toBe('Подождите немного и повторите.');
            expect(screen()?.querySelector('.actions button')?.getAttribute('aria-disabled')).toBeNull();
        });

        it('says the service is busy after 503 and retries on request', async () => {
            api.summary.mockReturnValueOnce(throwError(() => new PublicDeckFailure('busy', 1))).mockReturnValueOnce(of(PUBLIC));
            await open(publicUrl);
            expect(screen()?.querySelector('h1')?.textContent).toBe('Сервис занят');
            expect(screen()?.querySelector('.message')?.textContent).toBe('Сейчас много читателей. Повторите через секунду.');
            screen()!.querySelector<HTMLButtonElement>('.actions button')!.click();
            await settle();
            expect(root().querySelector('h1')?.textContent).toBe(PUBLIC.title);
        });

        it('says the connection failed for anything else', async () => {
            api.summary.mockReturnValue(throwError(() => new PublicDeckFailure('unavailable')));
            await open(publicUrl);
            expect(screen()?.querySelector('h1')?.textContent).toBe('Не удалось открыть колоду');
            expect(screen()?.querySelector('.actions button')?.textContent).toBe('Повторить');
        });
    });

    describe('a deck that disappears while it is being read', () => {
        it('switches to «Колода не найдена» when a list reports it gone and the summary agrees', async () => {
            api.summary.mockReturnValueOnce(of(PUBLIC)).mockReturnValueOnce(throwError(() => new PublicDeckFailure('not-found')));
            api.materials.mockReturnValue(throwError(() => new PublicDeckFailure('not-found')));
            await open(publicUrl);
            expect(root().querySelector('app-access-screen h1')?.textContent).toBe('Колода не найдена');
            expect(api.summary).toHaveBeenCalledTimes(2);
        });

        it('keeps the page when only the list was refused and the summary still answers', async () => {
            api.summary.mockReturnValue(of(PUBLIC));
            api.materials.mockReturnValue(throwError(() => new PublicDeckFailure('not-found')));
            await open(publicUrl);
            expect(root().querySelector('h1')?.textContent).toBe(PUBLIC.title);
            expect(root().querySelector('app-public-materials-list [role=alert]')).not.toBeNull();
            expect(api.summary).toHaveBeenCalledTimes(2);
        });
    });

    describe('opening another deck in the same page', () => {
        it('loads the new one and forgets the old', async () => {
            api.summary.mockReturnValueOnce(of(PUBLIC)).mockReturnValueOnce(of(LINK));
            await open(publicUrl);
            await router().navigateByUrl(`/d/${LINK.code}`);
            await settle();
            expect(root().querySelector('h1')?.textContent).toBe(LINK.title);
            expect(meta('robots')?.content).toBe('noindex');
        });

        it('ignores a late answer for the deck that was left', async () => {
            const slow = new Subject<PublicDeck>();
            api.summary.mockReturnValueOnce(slow).mockReturnValueOnce(of(LINK));
            await open(publicUrl);
            await router().navigateByUrl(`/d/${LINK.code}`);
            await settle();
            slow.next(PUBLIC);
            await settle();
            expect(root().querySelector('h1')?.textContent).toBe(LINK.title);
        });
    });

    describe('the list of the page', () => {
        it('uses app-auto-load and no page buttons', async () => {
            api.summary.mockReturnValue(of(PUBLIC));
            await open(publicUrl);
            expect(harness.fixture.debugElement.query(By.directive(AutoLoadComponent))).not.toBeNull();
            expect([...root().querySelectorAll('button')].map(button => button.textContent?.trim()).join('|')).not.toMatch(/Показать ещё|Следующая|Предыдущая/u);
            expect(SHARE_MENU_ITEM.id).toBe('share');
        });
    });
});
