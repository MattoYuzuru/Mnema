import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';

import { NativeDocumentRendererComponent } from '../../content/rendering/native-document-renderer.component';
import { OwnDeck } from '../own-decks/own-deck.models';
import { OwnDecksApiService } from '../own-decks/own-decks-api.service';
import { ItemApiService } from './item-api.service';
import { ItemDetail, ItemPage } from './authoring.models';

@Component({
    selector: 'app-browse-page',
    imports: [DatePipe, RouterLink, NativeDocumentRendererComponent],
    templateUrl: './browse-page.component.html',
    styleUrl: './authoring-page.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class BrowsePageComponent {
    readonly deck = signal<OwnDeck | null>(null);
    readonly page = signal<ItemPage | null>(null);
    readonly item = signal<ItemDetail | null>(null);
    readonly selectedOrdinal = signal<number | null>(null);
    readonly loading = signal(true);
    readonly failure = signal(false);

    private readonly route = inject(ActivatedRoute);
    private readonly decks = inject(OwnDecksApiService);
    private readonly items = inject(ItemApiService);
    private readonly destroyRef = inject(DestroyRef);

    constructor() { this.load(); }

    load(): void {
        const deckId = this.route.snapshot.paramMap.get('deckId');
        const memberKey = this.route.snapshot.paramMap.get('memberKey');
        this.selectedOrdinal.set(parseOrdinal(this.route.snapshot.queryParamMap.get('ordinal')));
        if (deckId === null) { this.failure.set(true); this.loading.set(false); return; }
        this.loading.set(true);
        this.failure.set(false);
        const content = memberKey === null ? this.items.list(deckId) : this.items.read(deckId, memberKey);
        forkJoin({ deck: this.decks.detail(deckId), content }).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
            next: result => {
                this.deck.set(result.deck);
                if ('document' in result.content) this.item.set(result.content);
                else this.page.set(result.content);
                this.loading.set(false);
            },
            error: () => { this.failure.set(true); this.loading.set(false); }
        });
    }

    loadMore(): void {
        const deck = this.deck();
        const current = this.page();
        if (deck === null || current === null || current.nextCursor === null || this.loading()) return;
        this.loading.set(true);
        this.items.list(deck.deckId, current.nextCursor).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
            next: next => {
                if (next.deckRevisionId !== current.deckRevisionId) { this.load(); return; }
                this.page.set({ ...next, items: [...current.items, ...next.items] });
                this.loading.set(false);
            },
            error: () => { this.failure.set(true); this.loading.set(false); }
        });
    }
}

function parseOrdinal(value: string | null): number | null {
    if (value === null || !/^(0|[1-9][0-9]{0,4})$/.test(value)) return null;
    const ordinal = Number(value);
    return ordinal < 100_000 ? ordinal : null;
}
