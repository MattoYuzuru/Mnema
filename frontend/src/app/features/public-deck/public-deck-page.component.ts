import { TourAnchorDirective } from '../../core/onboarding/tour-anchor.directive';
import { TourEvents } from '../../core/onboarding/tour-events';
import { DOCUMENT } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Injector, afterNextRender, computed, effect, inject, signal, untracked, viewChild } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink, Scroll } from '@angular/router';
import { Subscription, filter, take } from 'rxjs';

import { learnerOrigin } from '../../app.config';
import { CatalogTelemetry } from '../community/catalog-telemetry.service';
import { InterestStore } from '../community/interest-store.service';
import { AuthService } from '../../auth.service';
import { AuthorCardsService } from '../../author-cards.service';
import { safeReturnUrl } from '../../auth-protocol';
import { ActionMenuComponent, ActionMenuItem } from '../../shared/action-menu.component';
import { AccessScreenComponent } from '../../shared/access-screen.component';
import { AuthorChipComponent } from '../../shared/author-chip.component';
import { ComplaintDialogComponent } from '../../shared/complaint-dialog.component';
import { GlyphComponent, GlyphName } from '../../shared/glyph.component';
import { sizeLine } from '../../shared/public-deck-card';
import { SegmentedChoiceComponent, SegmentedOption } from '../../shared/segmented-choice.component';
import { ShareButtonComponent } from '../../shared/share-button.component';
import { ShareLinkFieldComponent } from '../../shared/share-link-field.component';
import { SHARE_MENU_ITEM, ShareLinkService } from '../../shared/share-link.service';
import { DeckDescriptionComponent } from '../own-decks/deck-description.component';
import { AccessApiService } from '../own-decks/access/access-api.service';
import { accessFailureOf } from '../own-decks/access/access.models';
import { ForkApiService } from '../own-decks/fork/fork-api.service';
import { forkFailureOf, newForkCommandId } from '../own-decks/fork/fork.models';
import { AccessRequestFlow } from './access-request-flow';
import { INVITE_TOKEN, clearInvite, peekInvite, stashInvite, takeInvite } from './invite-token-store';
import { PublicDeckApiService } from './public-deck-api.service';
import {
    LEVEL_LABELS,
    PublicDeck,
    PublicDeckFailure,
    PublicMaterial,
    PublicVisibility,
    canonicalPath,
    publicFailureOf,
    publishedLabel
} from './public-deck.models';
import { COMMUNITY_ROUTE, PUBLIC_ACTION_TEXT, RETRY_NOW_TEXT, waitText } from './public-deck.text';
import { PublicExercisesListComponent } from './public-exercises-list.component';
import { PublicMaterialComponent } from './public-material.component';
import { PublicMaterialsListComponent } from './public-materials-list.component';
import { PublicPageMeta } from './public-page-meta';

type Phase = 'loading' | 'ready' | 'not-found' | 'invite-only' | 'full' | 'rate-limited' | 'busy' | 'error';
type Tab = 'materials' | 'exercises';

const LEVEL_GLYPHS: Readonly<Record<PublicVisibility, GlyphName>> = { PUBLIC: 'book-open', LINK: 'link', INVITE: 'lock' };
const COMPLAINT_MENU_ITEM: ActionMenuItem = { id: 'complain', label: 'Пожаловаться' };
const OWNER_MENU_ITEMS: readonly ActionMenuItem[] = [SHARE_MENU_ITEM];
const READER_MENU_ITEMS: readonly ActionMenuItem[] = [SHARE_MENU_ITEM, COMPLAINT_MENU_ITEM];


/**
 * `/d/:code/:slug` and `/d/:code`: the read-only view of someone else's deck behind its public code, for guests and any
 * account. It is a hub (title, author, size, materials and exercises as lists), and with `?material=` the same page shows one
 * material drawn by the native renderer. There is no «Учить», no «Изменить» and no Workshop here. The published revision is all
 * the server gives. PUBLIC decks live at `/d/{code}/{slug}` (a missing or wrong slug is replaced, with history replaced too);
 * a LINK or INVITE deck never shows a slug. The head carries `referrer: no-referrer` and, unless the deck is PUBLIC, `noindex`.
 *
 * Failures are screens, not toasts: «Колода не найдена» (an unknown, hidden, closed or never published deck: one answer),
 * «Колода доступна по приглашению» (a signed-in viewer can «Запросить доступ» and «Отменить запрос»), and plain retry notices for too
 * many requests, a busy service and a lost connection.
 *
 * A signed-in viewer who does not own the deck gets «Добавить к себе» (a copy in O(1), then the copy's hub) or, with a live copy,
 * «Открыть мою копию». `/d/{code}?invite={token}` redeems the invitation once the viewer is signed in; the token leaves the address
 * at once (a guest's too); a guest's token waits in a ten-minute `sessionStorage` entry and is spent once after the sign-in, a signed-in
 * viewer's stays only in this component until the answer. It is never logged, put in a title, the login `returnUrl` or the history.
 */
@Component({
    selector: 'app-public-deck-page',
    imports: [RouterLink, AccessScreenComponent, ActionMenuComponent, AuthorChipComponent, DeckDescriptionComponent, GlyphComponent,
        PublicExercisesListComponent, PublicMaterialComponent, PublicMaterialsListComponent, SegmentedChoiceComponent, ShareButtonComponent,
        ShareLinkFieldComponent, ComplaintDialogComponent, TourAnchorDirective],
    providers: [PublicPageMeta],
    templateUrl: './public-deck-page.component.html',
    styleUrl: './public-deck-page.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class PublicDeckPageComponent {
    protected readonly phase = signal<Phase>('loading');
    protected readonly deck = signal<PublicDeck | null>(null);
    protected readonly retryAfter = signal<number | null>(null);
    protected readonly author = signal<{ readonly username: string; readonly avatarSrc: string | null } | null>(null);
    protected readonly tab = signal<Tab>('materials');
    protected readonly menuFallback = signal<string | null>(null);
    /** The complaint dialog is open (a signed-in reader chose «Пожаловаться»). */
    protected readonly complaintOpen = signal(false);
    protected readonly communityRoute = COMMUNITY_ROUTE;
    protected readonly text = PUBLIC_ACTION_TEXT;
    /** «Добавить к себе» is on its way. */
    protected readonly copying = signal(false);
    /** The plain sentence for the last copy attempt that did not succeed. */
    protected readonly copyNotice = signal<string | null>(null);
    /** A guest arrived by an invitation link (or one still waits for the sign-in): the screen says what signing in is for. */
    protected readonly invitePending = signal(false);
    /** The invitation is being redeemed (the loading line says so instead of «Загружаем колоду…»). */
    protected readonly redeeming = signal(false);

    private readonly route = inject(ActivatedRoute);
    private readonly router = inject(Router);
    private readonly api = inject(PublicDeckApiService);
    private readonly auth = inject(AuthService);
    private readonly telemetry = inject(CatalogTelemetry);
    private readonly interests = inject(InterestStore);
    private readonly forks = inject(ForkApiService);
    private readonly tour = inject(TourEvents);
    private readonly access = inject(AccessApiService);
    private readonly authors = inject(AuthorCardsService);
    private readonly links = inject(ShareLinkService);
    private readonly meta = inject(PublicPageMeta);
    private readonly document = inject(DOCUMENT);
    private readonly location = this.document.location;
    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
    private readonly injector = inject(Injector);
    private readonly destroyRef = inject(DestroyRef);

    private readonly params = toSignal(this.route.paramMap, { initialValue: this.route.snapshot.paramMap });
    private readonly queries = toSignal(this.route.queryParamMap, { initialValue: this.route.snapshot.queryParamMap });
    protected readonly code = computed(() => this.params().get('code') ?? '');
    private readonly routeSlug = computed(() => this.params().get('slug'));
    /** The material that is open (`?material=`), or `null` for the hub. */
    protected readonly materialKey = computed(() => this.queries().get('material'));

    protected readonly signedIn = computed(() => this.auth.status() === 'authenticated');
    protected readonly isOwner = computed(() => this.deck()?.access === 'OWNER');
    /** Share for everybody; «Пожаловаться» for everybody but the owner of the deck (the server refuses a complaint about one's own deck). */
    protected readonly menuItems = computed(() => this.isOwner() ? OWNER_MENU_ITEMS : READER_MENU_ITEMS);
    /** Where the login sends the reader back to: this very view. */
    protected readonly returnUrl = computed(() => {
        const slug = this.routeSlug();
        const material = this.materialKey();
        // The login returns to this very view, and never carries an invitation token (see `invite-token-store.ts`).
        const query = material === null ? '' : `?material=${encodeURIComponent(material)}`;
        return safeReturnUrl(`/d/${encodeURIComponent(this.code())}${slug === null ? '' : `/${encodeURIComponent(slug)}`}${query}`);
    });
    protected readonly shareUrl = computed(() => {
        const deck = this.deck();
        return deck === null ? '' : `${learnerOrigin(this.location.hostname, this.location.origin)}${canonicalPath(deck).map((part, index) => index === 0 ? part : `/${encodeURIComponent(part)}`).join('')}`;
    });
    protected readonly levelLabel = computed(() => LEVEL_LABELS[this.deck()?.visibility ?? 'PUBLIC']);
    protected readonly levelGlyph = computed(() => LEVEL_GLYPHS[this.deck()?.visibility ?? 'PUBLIC']);
    protected readonly size = computed(() => sizeLine(this.deck()?.memberCount ?? 0, this.deck()?.exerciseCount ?? 0));
    protected readonly published = computed(() => publishedLabel(this.deck()?.publishedAt ?? ''));
    protected readonly menuLabel = computed(() => `Действия с колодой «${this.deck()?.title ?? ''}»`);
    protected readonly tabs = computed<readonly SegmentedOption<Tab>[]>(() => [
        { value: 'materials', label: `Материалы · ${this.deck()?.memberCount ?? 0}`, hint: `Показаны материалы: ${this.deck()?.memberCount ?? 0}` },
        { value: 'exercises', label: `Упражнения · ${this.deck()?.exerciseCount ?? 0}`, hint: `Показаны упражнения: ${this.deck()?.exerciseCount ?? 0}` }
    ]);
    /** Seconds left of the wait the server asked for after a 429 (`Retry-After`); the retry button is `aria-disabled` until it is 0. */
    protected readonly remaining = signal(0);
    protected readonly waiting = computed(() => this.retryAfter() === null || this.remaining() > 0 ? waitText(this.remaining() > 0 ? this.remaining() : null) : RETRY_NOW_TEXT);
    protected readonly retryReady = computed(() => this.retryAfter() !== null && this.remaining() === 0 ? RETRY_NOW_TEXT : '');
    private readonly materialsList = viewChild(PublicMaterialsListComponent);
    /** The material before and after the open one, as far as the list has read. */
    protected readonly neighbors = computed(() => this.materialsList()?.neighbors() ?? { previous: null, next: null });

    /** «Запросить доступ» / «Отменить запрос» of the invitation-only screen. */
    protected readonly request = new AccessRequestFlow(this.access, this.destroyRef, {
        sent: () => afterNextRender(() => this.host.nativeElement.querySelector<HTMLElement>('.request-sent')?.focus(), { injector: this.injector }),
        gone: () => this.fail(new PublicDeckFailure('not-found')),
        withdrawn: () => afterNextRender(() => this.host.nativeElement.querySelector<HTMLElement>('app-access-screen .actions button')?.focus(), { injector: this.injector }),
        readable: () => void this.load(this.code())
    });

    private subscription: Subscription | null = null;
    /** The invitation to redeem, between its arrival and the answer (a retry after a lost connection needs it again). */
    private inviteToken: { readonly token: string; readonly code: string } | null = null;
    /** One command id per attempt to copy this deck, kept while the outcome is unknown so that the retry is the same command. */
    private copyCommand: { readonly code: string; readonly commandId: string } | null = null;
    private sequence = 0;
    private openedKey: string | null = null;
    private countdown: ReturnType<typeof setInterval> | null = null;

    constructor() {
        this.destroyRef.onDestroy(() => { this.subscription?.unsubscribe(); this.stopCountdown(); });
        effect(() => {
            const code = this.code();
            untracked(() => void this.load(code));
        });
        // The community tour waits for a deck to be opened; whoever opens one, the tour is told once the deck is on the screen.
        effect(() => {
            const deck = this.deck();
            if (deck !== null && this.phase() === 'ready') untracked(() => this.tour.emit('community.deck-opened'));
        });
        // After a screen is replaced by another the focus lands on the new page heading. A material focuses its own heading
        // («Материал 3 из 12») once it is there; going back to the hub puts the focus on the row that was opened.
        effect(() => {
            const phase = this.phase();
            const key = this.materialKey();
            if (phase === 'loading') return;
            untracked(() => {
                const opened = this.openedKey;
                this.openedKey = key;
                afterNextRender(() => {
                    if (key !== null && phase === 'ready') return;
                    if (key === null && opened !== null && phase === 'ready' && this.mayMoveFocus()) {
                        // The router scrolls the page (to the top, or back to a stored place) after the navigation; the row is brought
                        // into view only after that, or the router would take the page away from it again.
                        this.afterRouterScroll(() => { if (this.materialsList()?.focusRow(opened) !== true) this.focusHeading(); });
                        return;
                    }
                    this.focusHeading();
                }, { injector: this.injector });
            });
        });
    }

    protected retry(): void {
        // A 429 says how long to wait; the button only looks active again once that time has passed.
        if (this.phase() === 'rate-limited' && this.remaining() > 0) return;
        void this.load(this.code());
    }

    protected titleOf(item: PublicMaterial): string { return item.title.trim() || `Материал ${item.ordinal + 1}`; }

    protected setTab(value: Tab | null): void { this.tab.set(value ?? 'materials'); }

    protected async menuChosen(id: string): Promise<void> {
        if (id === COMPLAINT_MENU_ITEM.id) {
            // A guest signs in first and comes back to this very view; a complaint always has a signed-in reporter.
            if (this.signedIn()) this.complaintOpen.set(true);
            else void this.router.navigate(['/login'], { queryParams: { returnUrl: this.returnUrl() } });
            return;
        }
        if (id !== SHARE_MENU_ITEM.id) return;
        this.menuFallback.set(null);
        const outcome = await this.links.share(this.shareUrl(), this.deck()?.title ?? '');
        if (outcome === 'failed') this.menuFallback.set(this.shareUrl());
    }

    /** The complaint dialog found the deck gone for this reader (404): the dialog is closed and the page asks for the summary again. */
    protected complaintGone(): void {
        this.complaintOpen.set(false);
        this.recheck();
    }

    /**
     * A list or a material found the deck gone or closed while it was being read. Ask for the summary again without leaving the
     * page; only an answer that says «not found» or «by invitation» changes the screen, anything else keeps what is shown.
     */
    protected recheck(): void {
        const code = this.code();
        this.api.summary(code).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
            next: deck => { if (code === this.code()) this.deck.set(deck); },
            error: (error: unknown) => {
                const failure = publicFailureOf(error);
                if (code === this.code() && (failure.kind === 'not-found' || failure.kind === 'invite-only')) this.fail(failure);
            }
        });
    }

    /** «Добавить к себе»: copies the deck in O(1) and opens the copy, which the hub shows while it is being prepared. */
    protected addCopy(): void {
        const deck = this.deck();
        if (deck === null || this.copying()) return;
        const code = deck.code;
        // The same attempt keeps its command id (a lost answer is retried as the same command); a new deck or a refusal starts a new one.
        if (this.copyCommand?.code !== code) this.copyCommand = { code, commandId: newForkCommandId() };
        const { commandId } = this.copyCommand;
        this.copying.set(true);
        this.copyNotice.set(null);
        this.forks.copy(code, commandId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
            next: created => {
                this.copyCommand = null;
                // The tour's «Добавить к себе» step is done by the copy that worked, not by the click.
                // A tour that took the event opens the copy's hub itself (the same place), so the page does not navigate a second time.
                if (this.tour.emit('deck.copied', { deckId: created.deckId })) this.copying.set(false);
                else void this.router.navigate(['/decks', created.deckId]).finally(() => this.copying.set(false));
            },
            error: (error: unknown) => {
                const failure = forkFailureOf(error);
                if (failure.kind !== 'unknown') this.copyCommand = null;
                this.copying.set(false);
                this.copyNotice.set(failure.kind === 'not-found' ? PUBLIC_ACTION_TEXT.copyNotFound
                    : failure.kind === 'own-deck' ? PUBLIC_ACTION_TEXT.copyOwn
                    : failure.kind === 'sample' ? PUBLIC_ACTION_TEXT.copySample
                    : failure.kind === 'limit' ? PUBLIC_ACTION_TEXT.copyLimit(failure.limit)
                    : failure.kind === 'rate-limited' ? `Слишком много запросов. ${waitText(failure.retryAfter)}`
                    : failure.kind === 'busy' ? PUBLIC_ACTION_TEXT.copyBusy
                    : failure.kind === 'queue-busy' ? PUBLIC_ACTION_TEXT.copyQueueBusy : PUBLIC_ACTION_TEXT.copyFailed);
                // The deck may have just become private, rotated or ours: the summary says which screen is true now.
                if (failure.kind === 'not-found' || failure.kind === 'invite-only' || failure.kind === 'own-deck') this.recheck();
            }
        });
    }

    private async load(code: string): Promise<void> {
        const sequence = ++this.sequence;
        this.subscription?.unsubscribe();
        this.phase.set('loading');
        this.deck.set(null);
        this.author.set(null);
        this.tab.set('materials');
        this.menuFallback.set(null);
        this.copying.set(false);
        this.copyNotice.set(null);
        this.invitePending.set(false);
        this.redeeming.set(false);
        this.request.reset();
        this.complaintOpen.set(false);
        this.stopCountdown();
        this.meta.indexable(false);
        this.meta.title(null);
        // A signed-in viewer's bearer must be on the first request, so the server can say OWNER or GRANTEE.
        await this.auth.restore();
        this.interests.ensure();
        if (sequence !== this.sequence) return;
        const token = this.takeInviteToken(code);
        if (token !== null) this.redeem(token, code, sequence);
        else this.readSummary(code, sequence);
    }

    private readSummary(code: string, sequence: number): void {
        this.subscription = this.api.summary(code).subscribe({
            next: deck => { if (sequence === this.sequence) this.accept(deck); },
            error: (error: unknown) => { if (sequence === this.sequence) this.fail(publicFailureOf(error)); }
        });
    }

    /**
     * The invitation to redeem. `?invite=` is taken out of the address at once, for a guest too (the history entry is replaced). A
     * signed-in viewer keeps the token in this component until the answer (a retry after a 429 needs it again); a guest's token
     * waits in a short-lived `sessionStorage` entry (`invite-token-store.ts`) and is spent once, after the sign-in returns here.
     */
    private takeInviteToken(code: string): string | null {
        const typed = this.queries().get('invite');
        if (typed !== null) {
            void this.router.navigate([], { relativeTo: this.route, queryParams: { invite: null }, queryParamsHandling: 'merge', replaceUrl: true });
            if (INVITE_TOKEN.test(typed)) {
                if (this.signedIn()) {
                    this.inviteToken = { token: typed, code };
                    clearInvite(this.sessionStore());
                } else {
                    stashInvite(this.sessionStore(), typed, code);
                    this.invitePending.set(true);
                }
            }
        }
        if (!this.signedIn()) {
            if (peekInvite(this.sessionStore(), code)) this.invitePending.set(true);
            return null;
        }
        // A token that was kept for another deck is not used for this one.
        if (this.inviteToken?.code !== code) this.inviteToken = null;
        if (this.inviteToken === null) {
            const stored = takeInvite(this.sessionStore(), code);
            if (stored !== null) this.inviteToken = { token: stored, code };
        }
        return this.inviteToken?.token ?? null;
    }

    private sessionStore(): Storage | null {
        try { return this.document.defaultView?.sessionStorage ?? null; } catch { return null; }
    }

    /** Exchanges the invitation for the deck's current code, then reads the deck under that code. */
    private redeem(token: string, code: string, sequence: number): void {
        this.redeeming.set(true);
        this.subscription = this.access.redeem(token).subscribe({
            next: current => {
                if (sequence !== this.sequence) return;
                this.redeeming.set(false);
                this.inviteToken = null;
                if (current === code) { this.readSummary(code, sequence); return; }
                // Another code: the route change loads the deck under it.
                void this.router.navigate(['/d', current], { replaceUrl: true });
            },
            error: (error: unknown) => {
                if (sequence !== this.sequence) return;
                this.redeeming.set(false);
                const failure = accessFailureOf(error);
                if (failure?.status === 404 || failure?.status === 422) this.inviteToken = null;
                if (failure?.status === 422 && failure.code === 'RESOURCE_LIMIT_EXCEEDED') {
                    this.deck.set(null);
                    this.author.set(null);
                    this.phase.set('full');
                } else {
                    this.fail(publicFailureOf(error));
                }
            }
        });
    }

    private accept(deck: PublicDeck): void {
        this.deck.set(deck);
        if (deck.visibility === 'PUBLIC') this.telemetry.detail(deck.code);
        this.phase.set('ready');
        this.meta.indexable(deck.visibility === 'PUBLIC');
        this.meta.title(deck.title);
        // The slug belongs to PUBLIC decks only; a wrong or missing one is replaced (crawlers get a 301 from the web server, Share/14).
        if ((this.routeSlug() ?? null) !== deck.slug) {
            // The address never keeps an invitation token.
            void this.router.navigate(canonicalPath(deck), { replaceUrl: true, queryParamsHandling: 'merge', queryParams: { invite: null } });
        }
        const code = deck.code;
        void this.authors.card(deck.ownerId).then(card => {
            if (this.destroyRef.destroyed || this.code() !== code) return;
            this.author.set(card === null ? null : { username: card.profileUsername, avatarSrc: this.authors.avatarUrl(card) });
        }, () => undefined);
    }

    private fail(failure: PublicDeckFailure): void {
        this.deck.set(null);
        this.author.set(null);
        this.retryAfter.set(failure.retryAfter);
        this.stopCountdown();
        if (failure.kind === 'rate-limited' && failure.retryAfter !== null) this.startCountdown(failure.retryAfter);
        this.meta.indexable(false);
        this.meta.title(null);
        this.phase.set(failure.kind === 'not-found' ? 'not-found' : failure.kind === 'invite-only' ? 'invite-only'
            : failure.kind === 'rate-limited' ? 'rate-limited' : failure.kind === 'busy' ? 'busy' : 'error');
    }

    /** Runs `action` once the router has scrolled after the current navigation (its `Scroll` event), or shortly after if none comes. */
    private afterRouterScroll(action: () => void): void {
        let done = false;
        const run = (): void => { if (!done && !this.destroyRef.destroyed) { done = true; action(); } };
        const subscription = this.router.events.pipe(filter(event => event instanceof Scroll), take(1)).subscribe(() => setTimeout(run, 0));
        const timer = setTimeout(() => { subscription.unsubscribe(); run(); }, 250);
        this.destroyRef.onDestroy(() => { subscription.unsubscribe(); clearTimeout(timer); });
    }

    private startCountdown(seconds: number): void {
        this.remaining.set(seconds);
        this.countdown = setInterval(() => {
            this.remaining.update(value => Math.max(0, value - 1));
            if (this.remaining() === 0) this.stopCountdown();
        }, 1000);
    }

    private stopCountdown(): void {
        if (this.countdown !== null) clearInterval(this.countdown);
        this.countdown = null;
        this.remaining.set(0);
    }

    /** The reader has not moved the focus elsewhere on the page (it is on the page, or lost because the element it was on is gone). */
    private mayMoveFocus(): boolean {
        const active = this.host.nativeElement.ownerDocument.activeElement;
        return active === null || active === this.host.nativeElement.ownerDocument.body || this.host.nativeElement.contains(active);
    }

    private focusHeading(): void {
        if (this.mayMoveFocus()) this.host.nativeElement.querySelector<HTMLElement>('h1')?.focus();
    }
}
