import { ChangeDetectionStrategy, Component, DestroyRef, Injector, afterNextRender, effect, inject, input, signal, untracked, viewChild } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { Subscription } from 'rxjs';

import { ModerationNoticeComponent } from '../../../shared/moderation-notice.component';
import { DeckModeration, ModerationApiService } from './moderation-api.service';

/**
 * The moderation notice on the Deck hub: reads `GET /api/decks/{deckId}/moderation` and draws {@link ModerationNoticeComponent}
 * when the deck is hidden from the Community or has units taken down. A clean deck, a loading read and a failed read draw nothing
 * (the notice is the owner's information, not a gate, and the hub must not be held up by it). The link of the notifications
 * `DECK_HIDDEN` and `DECK_CONTENT_TAKEN_DOWN` opens `/decks/{id}#moderation`: the heading takes the focus once, when the notice is there.
 */
@Component({
    selector: 'app-deck-moderation',
    imports: [ModerationNoticeComponent],
    template: `
      @if (notice(); as current) {
        <app-moderation-notice [hiddenFromCommunity]="current.hiddenByModeration" [materials]="current.takenDown.materials" [exercises]="current.takenDown.exercises" />
      }
    `,
    styles: [`:host { display: block; min-inline-size: 0; }`],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class DeckModerationComponent {
    readonly deckId = input.required<string>();
    protected readonly notice = signal<DeckModeration | null>(null);

    private readonly api = inject(ModerationApiService);
    private readonly injector = inject(Injector);
    private readonly shown = viewChild(ModerationNoticeComponent);
    private readonly fragment = toSignal(inject(ActivatedRoute).fragment, { initialValue: null });
    private load: Subscription | null = null;
    private followed: string | null = null;

    constructor() {
        inject(DestroyRef).onDestroy(() => this.load?.unsubscribe());
        effect(() => {
            const deckId = this.deckId();
            untracked(() => this.read(deckId));
        });
        effect(() => {
            const current = this.notice();
            if (current === null || this.fragment() !== 'moderation' || this.followed === current.deckId) return;
            this.followed = current.deckId;
            untracked(() => afterNextRender(() => this.shown()?.focusHeading(), { injector: this.injector }));
        });
    }

    private read(deckId: string): void {
        this.load?.unsubscribe();
        this.notice.set(null);
        this.load = this.api.notice(deckId).subscribe({
            next: notice => { if (notice.deckId === deckId.toLowerCase()) this.notice.set(notice); },
            error: () => this.notice.set(null)
        });
    }
}
