import { ChangeDetectionStrategy, Component, DestroyRef, Injector, OnInit, computed, effect, inject, signal, untracked } from '@angular/core';
import { DatePipe } from '@angular/common';
import { AccessLevelComponent } from '../../shared/access-level.component';
import { CopyMarkComponent } from '../../shared/copy-mark.component';
import { UpdateBadgeComponent } from '../../shared/update-badge.component';
import { AutoLoadComponent } from '../../shared/auto-load.component';
import { COMMUNITY_LAUNCH } from '../../shared/community-launch';
import { CommunityApiService } from '../community/community-api.service';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { copyFor } from '../goal/goal-copy';
import { LearningGoalStore } from '../goal/learning-goal.store';
import { PromoPopupService } from '../promo/promo-popup.service';
import { DeckDescriptionComponent } from './deck-description.component';

import { deckFailureMessage } from './own-decks.store';
import { OwnDecksStore } from './own-decks.store';

const VISIBLE_RECHECK_MS = 10_000;

@Component({
    selector: 'app-own-decks-list-page',
    imports: [DatePipe, RouterLink, DeckDescriptionComponent, AutoLoadComponent, AccessLevelComponent, CopyMarkComponent, UpdateBadgeComponent],
    providers: [OwnDecksStore],
    templateUrl: './own-decks-list-page.component.html',
    styleUrl: './own-decks-page.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class OwnDecksListPageComponent implements OnInit {
    readonly store = inject(OwnDecksStore);
    private readonly goals = inject(LearningGoalStore);
    /** The empty-list sentence in the learner's own terms when they named a goal. */
    protected readonly emptyText = computed(() => copyFor(this.goals.goal()).emptyDecks);
    readonly failureMessage = deckFailureMessage;
    private readonly destroyRef = inject(DestroyRef);
    private timer: ReturnType<typeof setTimeout> | null = null;
    private readonly promoPopup = inject(PromoPopupService);
    private readonly communityLaunched = inject(COMMUNITY_LAUNCH);
    private readonly injector = inject(Injector);
    /** The catalogue is open and has decks: the empty list may point to it (never to an empty catalogue). */
    protected readonly communityHasDecks = signal(false);
    private communityAsked = false;

    constructor() {
        // The deck list once loaded is a natural pause: the one place besides the end of a Study session where the promo popup may ask to appear.
        effect(() => {
            if (this.store.listState().phase === 'ready') untracked(() => void this.promoPopup.request());
        });
        // One look at the catalogue, and only when the list is empty and the catalogue is open: a failed look just leaves the link out.
        effect(() => {
            const state = this.store.listState();
            if (state.phase === 'ready' && state.items.length === 0) untracked(() => this.lookAtCommunity());
        });
    }

    private lookAtCommunity(): void {
        if (!this.communityLaunched || this.communityAsked) return;
        this.communityAsked = true;
        // Looked up only now: while the catalogue is closed to readers nothing here depends on it, not even its HTTP client.
        this.injector.get(CommunityApiService).facets().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
            next: facets => this.communityHasDecks.set(facets.listedCount > 0),
            error: () => undefined
        });
    }

    ngOnInit(): void {
        this.store.loadList();
        const recheck = () => {
            this.clearTimer();
            if (document.visibilityState === 'visible' && navigator.onLine) this.store.refreshVisibleList();
            this.schedule();
        };
        document.addEventListener('visibilitychange', recheck);
        window.addEventListener('focus', recheck);
        window.addEventListener('online', recheck);
        this.destroyRef.onDestroy(() => {
            document.removeEventListener('visibilitychange', recheck);
            window.removeEventListener('focus', recheck);
            window.removeEventListener('online', recheck);
            this.clearTimer();
        });
        this.schedule();
    }

    private schedule(): void {
        if (this.timer || document.visibilityState !== 'visible' || !navigator.onLine) return;
        this.timer = setTimeout(() => {
            this.timer = null;
            this.store.refreshVisibleList();
            this.schedule();
        }, VISIBLE_RECHECK_MS);
    }

    private clearTimer(): void {
        if (this.timer) clearTimeout(this.timer);
        this.timer = null;
    }
}
