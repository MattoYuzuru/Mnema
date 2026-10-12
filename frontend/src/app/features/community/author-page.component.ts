import { DOCUMENT } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Injector, afterNextRender, computed, effect, inject, signal, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { Title } from '@angular/platform-browser';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Observable, map, tap } from 'rxjs';

import { learnerOrigin } from '../../app.config';
import { AuthService } from '../../auth.service';
import { safeReturnUrl } from '../../auth-protocol';
import { AuthorCardsService } from '../../author-cards.service';
import { AuthorCard } from '../../public-profile';
import { ActionMenuItem } from '../../shared/action-menu.component';
import { AccessScreenComponent } from '../../shared/access-screen.component';
import { AuthorHeaderComponent, AuthorProfile } from '../../shared/author-header.component';
import { ComplaintDialogComponent } from '../../shared/complaint-dialog.component';
import { DeckGridComponent } from '../../shared/deck-grid.component';
import { PublicDeckAuthor, PublicDeckCard } from '../../shared/public-deck-card';
import { SHARE_MENU_ITEM } from '../../shared/share-link.service';
import { PublicDeckFailure, publicFailureOf } from '../public-deck/public-deck.models';
import { SHELL_TITLE } from '../public-deck/public-page-meta';
import { waitText } from '../public-deck/public-deck.text';
import { CommunityApiService } from './community-api.service';
import { CommunityPager, PagerPage } from './community-pager';
import { AuthorDeckEntry } from './community.models';
import { displayCard } from './community-view';

/** The mark on an invitation-only deck that the viewer has a grant for. */
export const GRANTEE_NOTE = 'Доступна вам';

const COMPLAINT_MENU_ITEM: ActionMenuItem = { id: 'complain', label: 'Пожаловаться' };
/** Share for everybody; «Пожаловаться» for everybody but the author of the deck (the server refuses a complaint about one's own deck). */
const OWN_MENU: readonly ActionMenuItem[] = [SHARE_MENU_ITEM];
const READER_MENU: readonly ActionMenuItem[] = [SHARE_MENU_ITEM, COMPLAINT_MENU_ITEM];
/** Every card on this page is by the page's author, so the card draws no chip and needs no author. */
const NO_AUTHOR: PublicDeckAuthor = { username: null, avatarSrc: null };
const PAGE_TITLE = `Профиль — ${SHELL_TITLE}`;

type View = 'loading' | 'ready' | 'not-found' | 'unavailable';
type DecksView = 'loading' | 'ready' | 'closed' | 'rate-limited' | 'busy' | 'error';
/** What the complaint dialog is about: a profile (by login) or a deck (by code), with the name the dialog shows. */
interface Complaint { readonly target: 'profile' | 'deck'; readonly subject: string; readonly name: string }

/**
 * `/u/:username`: the public page of an author, for guests and any account. Identity answers the card (login, and name, photo and «О себе» only as far as the
 * author agreed); an unknown login, a profile without consent and a withdrawn one are one and the same «Профиль не найден». The decks come from Learning
 * (`CommunityApiService.authorDecks`, which does not know the consent: the card is asked for first and a card that is not there ends the page before any deck is
 * requested): the author's public decks, and for a signed-in viewer with a grant the author's invitation-only decks with the mark «Доступна вам» and the address
 * `/d/{code}` (no slug). The list continues through the shared auto-load; there is no count and no author chip, since every card is by this author.
 *
 * The route is public and stays reachable while the launch flag is off (nothing leads to it from the product until `COMMUNITY_LAUNCHED`). A catalogue that is
 * switched off answers the decks route 404: the header stays and the list says so calmly. The signed-in author sees the same page with a note and no complaint.
 */
@Component({
    selector: 'app-author-page',
    imports: [RouterLink, AccessScreenComponent, AuthorHeaderComponent, ComplaintDialogComponent, DeckGridComponent],
    templateUrl: './author-page.component.html',
    styleUrl: './author-page.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class AuthorPageComponent {
    private readonly route = inject(ActivatedRoute);
    private readonly router = inject(Router);
    private readonly api = inject(CommunityApiService);
    private readonly auth = inject(AuthService);
    private readonly authors = inject(AuthorCardsService);
    private readonly titles = inject(Title);
    private readonly document = inject(DOCUMENT);
    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
    private readonly injector = inject(Injector);
    private readonly destroyRef = inject(DestroyRef);

    private readonly params = toSignal(this.route.paramMap, { initialValue: this.route.snapshot.paramMap });
    private readonly username = computed(() => this.params().get('username') ?? '');
    protected readonly signedIn = computed(() => this.auth.status() === 'authenticated');
    private readonly me = computed(() => this.auth.user()?.accountId.toLowerCase() ?? null);
    private readonly origin = learnerOrigin(this.document.location.hostname, this.document.location.origin);

    protected readonly view = signal<View>('loading');
    private readonly card = signal<AuthorCard | null>(null);
    protected readonly author = computed<AuthorProfile | null>(() => {
        const card = this.card();
        return card === null ? null : {
            accountId: card.accountId, profileUsername: card.profileUsername, displayName: card.displayName, bio: card.bio, avatarSrc: this.authors.avatarUrl(card)
        };
    });
    protected readonly isSelf = computed(() => this.card() !== null && this.card()?.accountId === this.me());
    protected readonly profileUrl = computed(() => `${this.origin}/u/${encodeURIComponent(this.card()?.profileUsername ?? this.username())}`);
    /** «Учат сейчас» over the author's public decks; `null` below the server's minimum, and until the first page is in. */
    protected readonly learningNow = signal<number | null>(null);

    protected readonly pager = new CommunityPager<AuthorDeckEntry>(cursor => this.fetchDecks(cursor), this.destroyRef);
    protected readonly complaint = signal<Complaint | null>(null);

    protected readonly decksView = computed<DecksView>(() => {
        const phase = this.pager.phase();
        if (phase === 'error') {
            const kind = this.pager.failure()?.kind;
            return kind === 'not-found' ? 'closed' : kind === 'rate-limited' ? 'rate-limited' : kind === 'busy' ? 'busy' : 'error';
        }
        return phase === 'ready' ? 'ready' : 'loading';
    });
    protected readonly waiting = computed(() => `Слишком много запросов. ${waitText(this.pager.failure()?.retryAfter ?? null)}`);
    protected readonly moreError = computed(() => this.pager.moreFailed() ? 'Не удалось загрузить следующие колоды. Проверьте соединение и повторите.' : null);
    protected readonly noDecks = computed(() => this.decksView() === 'ready' && this.pager.rows().length === 0);

    private readonly entries = computed(() => new Map(this.pager.rows().map(entry => [entry.code, entry] as const)));
    protected readonly cards = computed<readonly PublicDeckCard[]>(() => this.pager.rows().map(entry => displayCard(entry, NO_AUTHOR, this.origin)));

    protected readonly deckLink = (deck: PublicDeckCard): readonly string[] | null => {
        const entry = this.entries().get(deck.id);
        if (entry === undefined) return null;
        // An invitation-only deck has no slug: its address is `/d/{code}`.
        return entry.access === 'GRANTEE' || entry.slug === null ? ['/d', entry.code] : ['/d', entry.code, entry.slug];
    };
    protected readonly noteFor = (deck: PublicDeckCard): string | null => this.entries().get(deck.id)?.access === 'GRANTEE' ? GRANTEE_NOTE : null;
    protected readonly menuFor = (deck: PublicDeckCard): readonly ActionMenuItem[] => {
        const owner = this.entries().get(deck.id)?.ownerId;
        return owner !== undefined && owner === this.me() ? OWN_MENU : READER_MENU;
    };

    private accountId = '';
    private sequence = 0;

    constructor() {
        this.titles.setTitle(PAGE_TITLE);
        this.destroyRef.onDestroy(() => this.titles.setTitle(SHELL_TITLE));
        effect(() => {
            const name = this.username();
            untracked(() => void this.load(name));
        });
    }

    protected retry(): void {
        void this.load(this.username());
    }

    /** The list failed on its own (the card is fine): ask for the decks again. */
    protected retryDecks(): void {
        this.startDecks();
    }

    protected actionChosen(event: { readonly id: string; readonly deck: PublicDeckCard }): void {
        if (event.id !== COMPLAINT_MENU_ITEM.id) return;
        this.complain({ target: 'deck', subject: event.deck.id, name: event.deck.title });
    }

    protected complainAboutProfile(): void {
        const card = this.card();
        if (card !== null) this.complain({ target: 'profile', subject: card.profileUsername, name: `@${card.profileUsername}` });
    }

    /** A complaint always has a signed-in reporter: a guest signs in first and comes back to this very page. */
    private complain(target: Complaint): void {
        if (this.signedIn()) this.complaint.set(target);
        else void this.router.navigate(['/login'], { queryParams: { returnUrl: safeReturnUrl(this.router.url) } });
    }

    /** The complaint dialog found the profile or the deck gone for this reader (404): the page asks again. */
    protected complaintGone(): void {
        const closed = this.complaint();
        this.complaint.set(null);
        if (closed?.target === 'deck') this.startDecks();
        else void this.load(this.username());
    }

    private fetchDecks(cursor: string | null): Observable<PagerPage<AuthorDeckEntry>> {
        return this.api.authorDecks(this.accountId, cursor).pipe(
            tap(page => this.learningNow.set(page.learningNowTotal)),
            map(page => ({ decks: page.decks, total: page.decks.length, totalCapped: false, nextCursor: page.nextCursor }))
        );
    }

    private startDecks(): void {
        this.pager.start();
    }

    /** Asks Identity for the card, then (only for a card that is there) Learning for the decks. Only the latest address renders. */
    private async load(name: string): Promise<void> {
        const sequence = ++this.sequence;
        this.pager.cancel();
        this.card.set(null);
        this.learningNow.set(null);
        this.complaint.set(null);
        this.view.set('loading');
        this.titles.setTitle(PAGE_TITLE);
        let card: AuthorCard | null;
        try {
            // A signed-in viewer's bearer must be on the first request to Learning (the grant, and the limits keyed by the account).
            [card] = await Promise.all([this.authors.byUsername(name), this.auth.restore()]);
        } catch {
            if (sequence !== this.sequence || this.destroyRef.destroyed) return;
            this.settle('unavailable', PAGE_TITLE);
            return;
        }
        if (sequence !== this.sequence || this.destroyRef.destroyed) return;
        if (card === null) {
            this.settle('not-found', `Профиль не найден — ${SHELL_TITLE}`);
            return;
        }
        this.card.set(card);
        this.accountId = card.accountId;
        this.settle('ready', card.displayName === null ? `@${card.profileUsername} — ${SHELL_TITLE}` : `${card.displayName} (@${card.profileUsername}) — ${SHELL_TITLE}`);
        this.startDecks();
    }

    private settle(view: View, title: string): void {
        this.view.set(view);
        this.titles.setTitle(title);
        afterNextRender(() => this.focusHeading(), { injector: this.injector });
    }

    /** The reader has not moved the focus elsewhere on the page (it is on the page, or lost because the element it was on is gone). */
    private focusHeading(): void {
        const document = this.host.nativeElement.ownerDocument;
        const active = document.activeElement;
        if (active === null || active === document.body || this.host.nativeElement.contains(active)) this.host.nativeElement.querySelector<HTMLElement>('h1')?.focus();
    }
}
