import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { DeckDescriptionComponent } from './deck-description.component';

import { deckFailureMessage } from './own-decks.store';
import { OwnDecksStore } from './own-decks.store';

const VISIBLE_RECHECK_MS = 10_000;

@Component({
    selector: 'app-own-decks-list-page',
    imports: [DatePipe, RouterLink, DeckDescriptionComponent],
    providers: [OwnDecksStore],
    templateUrl: './own-decks-list-page.component.html',
    styleUrl: './own-decks-page.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class OwnDecksListPageComponent implements OnInit {
    readonly store = inject(OwnDecksStore);
    readonly failureMessage = deckFailureMessage;
    private readonly destroyRef = inject(DestroyRef);
    private timer: ReturnType<typeof setTimeout> | null = null;

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
