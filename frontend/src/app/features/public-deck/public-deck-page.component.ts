import { DOCUMENT } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Injector, afterNextRender, computed, effect, inject, signal, untracked, viewChild } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink, Scroll } from '@angular/router';
import { Subscription, filter, take } from 'rxjs';

import { learnerOrigin } from '../../app.config';
import { AuthService } from '../../auth.service';
import { AuthorCardsService } from '../../author-cards.service';
import { safeReturnUrl } from '../../auth-protocol';
import { ActionMenuComponent } from '../../shared/action-menu.component';
import { AccessScreenComponent } from '../../shared/access-screen.component';
import { AuthorChipComponent } from '../../shared/author-chip.component';
import { GlyphComponent, GlyphName } from '../../shared/glyph.component';
import { sizeLine } from '../../shared/public-deck-card';
import { SegmentedChoiceComponent, SegmentedOption } from '../../shared/segmented-choice.component';
import { ShareButtonComponent } from '../../shared/share-button.component';
import { ShareLinkFieldComponent } from '../../shared/share-link-field.component';
import { SHARE_MENU_ITEM, ShareLinkService } from '../../shared/share-link.service';
import { DeckDescriptionComponent } from '../own-decks/deck-description.component';
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
import { COMMUNITY_ROUTE, RETRY_NOW_TEXT, waitText } from './public-deck.text';
import { PublicExercisesListComponent } from './public-exercises-list.component';
import { PublicMaterialComponent } from './public-material.component';
import { PublicMaterialsListComponent } from './public-materials-list.component';
import { PublicPageMeta } from './public-page-meta';

type Phase = 'loading' | 'ready' | 'not-found' | 'invite-only' | 'rate-limited' | 'busy' | 'error';
type Tab = 'materials' | 'exercises';

const LEVEL_GLYPHS: Readonly<Record<PublicVisibility, GlyphName>> = { PUBLIC: 'book-open', LINK: 'link', INVITE: 'lock' };
const MENU_ITEMS = [SHARE_MENU_ITEM];

/**
 * `/d/:code/:slug` and `/d/:code`: the read-only view of someone else's deck behind its public code, for guests and any
 * account. It is a hub (title, author, size, materials and exercises as lists), and with `?material=` the same page shows one
 * material drawn by the native renderer. There is no «Учить», no «Изменить» and no Workshop here. The published revision is all
 * the server gives. PUBLIC decks live at `/d/{code}/{slug}` (a missing or wrong slug is replaced, with history replaced too);
 * a LINK or INVITE deck never shows a slug. The head carries `referrer: no-referrer` and, unless the deck is PUBLIC, `noindex`.
 *
 * Failures are screens, not toasts: «Колода не найдена» (an unknown, hidden, closed or never published deck: one answer),
 * «Колода доступна по приглашению», and plain retry notices for too many requests, a busy service and a lost connection.
 */
@Component({
    selector: 'app-public-deck-page',
    imports: [RouterLink, AccessScreenComponent, ActionMenuComponent, AuthorChipComponent, DeckDescriptionComponent, GlyphComponent,
        PublicExercisesListComponent, PublicMaterialComponent, PublicMaterialsListComponent, SegmentedChoiceComponent, ShareButtonComponent,
        ShareLinkFieldComponent],
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
    protected readonly menuItems = MENU_ITEMS;
    protected readonly communityRoute = COMMUNITY_ROUTE;

    private readonly route = inject(ActivatedRoute);
    private readonly router = inject(Router);
    private readonly api = inject(PublicDeckApiService);
    private readonly auth = inject(AuthService);
    private readonly authors = inject(AuthorCardsService);
    private readonly links = inject(ShareLinkService);
    private readonly meta = inject(PublicPageMeta);
    private readonly location = inject(DOCUMENT).location;
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
    /** Where the login sends the reader back to: this very view. */
    protected readonly returnUrl = computed(() => {
        const slug = this.routeSlug();
        const material = this.materialKey();
        return safeReturnUrl(`/d/${encodeURIComponent(this.code())}${slug === null ? '' : `/${encodeURIComponent(slug)}`}`
            + (material === null ? '' : `?material=${encodeURIComponent(material)}`));
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

    private subscription: Subscription | null = null;
    private sequence = 0;
    private openedKey: string | null = null;
    private countdown: ReturnType<typeof setInterval> | null = null;

    constructor() {
        this.destroyRef.onDestroy(() => { this.subscription?.unsubscribe(); this.stopCountdown(); });
        effect(() => {
            const code = this.code();
            untracked(() => void this.load(code));
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
        if (id !== SHARE_MENU_ITEM.id) return;
        this.menuFallback.set(null);
        const outcome = await this.links.share(this.shareUrl(), this.deck()?.title ?? '');
        if (outcome === 'failed') this.menuFallback.set(this.shareUrl());
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

    private async load(code: string): Promise<void> {
        const sequence = ++this.sequence;
        this.subscription?.unsubscribe();
        this.phase.set('loading');
        this.deck.set(null);
        this.author.set(null);
        this.tab.set('materials');
        this.menuFallback.set(null);
        this.stopCountdown();
        this.meta.indexable(false);
        this.meta.title(null);
        // A signed-in viewer's bearer must be on the first request, so the server can say OWNER or GRANTEE.
        await this.auth.restore();
        if (sequence !== this.sequence) return;
        this.subscription = this.api.summary(code).subscribe({
            next: deck => { if (sequence === this.sequence) this.accept(deck); },
            error: (error: unknown) => { if (sequence === this.sequence) this.fail(publicFailureOf(error)); }
        });
    }

    private accept(deck: PublicDeck): void {
        this.deck.set(deck);
        this.phase.set('ready');
        this.meta.indexable(deck.visibility === 'PUBLIC');
        this.meta.title(deck.title);
        // The slug belongs to PUBLIC decks only; a wrong or missing one is replaced (the 301 for crawlers is Share/14).
        if ((this.routeSlug() ?? null) !== deck.slug) {
            void this.router.navigate(canonicalPath(deck), { replaceUrl: true, queryParamsHandling: 'preserve' });
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
