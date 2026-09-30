import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, effect, inject, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';

import { NativeMediaSurfaceComponent } from '../../content/rendering/native-media-surface.component';
import { HoldToDeleteButtonComponent } from '../../shared/hold-to-delete-button.component';
import { OwnDeck } from '../own-decks/own-deck.models';
import { OwnDecksApiService } from '../own-decks/own-decks-api.service';
import { ItemApiService } from './item-api.service';
import { AuthoringApiService } from './authoring-api.service';
import { ItemDetail, ItemPage, newCommandId } from './authoring.models';

@Component({
    selector: 'app-browse-page',
    imports: [DatePipe, RouterLink, NativeMediaSurfaceComponent, HoldToDeleteButtonComponent],
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
    readonly loadingMore = signal(false);
    readonly moreError = signal(false);
    readonly captureCount = signal<number | null>(null);
    readonly deleting = signal(false);
    readonly deleteMessage = signal<string | null>(null);
    readonly positionError = signal(false);
    readonly loadSentinel = viewChild<ElementRef<HTMLElement>>('loadSentinel');

    private readonly route = inject(ActivatedRoute);
    private readonly router = inject(Router);
    private readonly decks = inject(OwnDecksApiService);
    private readonly items = inject(ItemApiService);
    private readonly authoring = inject(AuthoringApiService);
    private readonly destroyRef = inject(DestroyRef);
    private deletionSnapshot: { version: string; revisionId: string; ordinal: number } | null = null;
    private pendingDeletion: { deckId: string; memberKey: string; itemRevisionId: string;
        version: string; revisionId: string; ordinal: number; commandId: string } | null = null;

    constructor() {
        this.load();
        effect(onCleanup => {
            const sentinel = this.loadSentinel()?.nativeElement;
            const nextCursor = this.page()?.nextCursor;
            if (!sentinel || !nextCursor || this.loadingMore() || this.moreError()) return;
            const observer = new IntersectionObserver(entries => {
                if (entries.some(entry => entry.isIntersecting)) this.loadMore();
            }, { rootMargin: '0px 0px 800px 0px' });
            observer.observe(sentinel);
            onCleanup(() => observer.disconnect());
        });
    }

    load(): void {
        const deckId = this.route.snapshot.paramMap.get('deckId');
        const memberKey = this.route.snapshot.paramMap.get('memberKey');
        if (this.deleting() || this.pendingDeletion) return;
        this.selectedOrdinal.set(null);
        this.deletionSnapshot = null;
        this.positionError.set(false);
        this.deleteMessage.set(null);
        if (deckId === null) { this.failure.set(true); this.loading.set(false); return; }
        this.loading.set(true);
        this.failure.set(false);
        this.moreError.set(false);
        this.captureCount.set(null);
        if (memberKey === null) {
            this.authoring.listDeckCaptures(deckId, null, 1).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
                next: page => this.captureCount.set(page.total),
                error: () => this.captureCount.set(null)
            });
        }
        const content = memberKey === null ? this.items.list(deckId) : this.items.read(deckId, memberKey);
        forkJoin({ deck: this.decks.detail(deckId), content }).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
            next: result => {
                this.deck.set(result.deck);
                if ('document' in result.content) {
                    this.item.set(result.content);
                    this.setPosition(result.content);
                }
                else this.page.set(result.content);
                this.loading.set(false);
            },
            error: () => { this.failure.set(true); this.loading.set(false); }
        });
    }

    deleteItem(): void {
        const deck = this.deck(); const item = this.item(); const snapshot = this.deletionSnapshot;
        if (this.deleting() || !deck || !item || !snapshot) return;
        const command = this.pendingDeletion ?? { deckId: deck.deckId, memberKey: item.memberKey,
            itemRevisionId: item.itemRevisionId, ...snapshot, commandId: newCommandId() };
        this.pendingDeletion = command;
        this.deleting.set(true);
        this.deleteMessage.set(null);
        this.items.delete(command.deckId, command.memberKey, command.version, command.revisionId,
            command.itemRevisionId, command.ordinal, command.commandId)
            .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
                next: () => {
                    this.pendingDeletion = null;
                    this.deletionSnapshot = null;
                    void this.router.navigate(['/decks', command.deckId, 'materials']);
                },
                error: (error: unknown) => {
                    this.deleting.set(false);
                    const uncertain = !(error instanceof HttpErrorResponse) || error.status === 0 || error.status >= 500;
                    if (!uncertain) this.pendingDeletion = null;
                    if (error instanceof HttpErrorResponse && (error.status === 412 || error.status === 404)) {
                        this.deletionSnapshot = null;
                        this.selectedOrdinal.set(null);
                        this.positionError.set(true);
                    }
                    this.deleteMessage.set(uncertain
                        ? 'Удаление не подтверждено. Повторите удержание: будет отправлена та же команда.'
                        : 'Материал не удалён. Обновите страницу и повторите попытку.');
                }
            });
    }

    private setPosition(item: ItemDetail): void {
        // The current read binds position, document and write preconditions to one server snapshot.
        if (item.ordinal === null) { this.positionError.set(true); return; }
        this.selectedOrdinal.set(item.ordinal);
        this.deletionSnapshot = { version: item.deckVersion, revisionId: item.deckRevisionId, ordinal: item.ordinal };
    }

    loadMore(): void {
        const deck = this.deck();
        const current = this.page();
        if (deck === null || current === null || current.nextCursor === null || this.loadingMore()) return;
        this.loadingMore.set(true);
        this.moreError.set(false);
        this.items.list(deck.deckId, current.nextCursor).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
            next: next => {
                if (next.deckRevisionId !== current.deckRevisionId) { this.loadingMore.set(false); this.load(); return; }
                this.page.set({ ...next, items: [...current.items, ...next.items] });
                this.loadingMore.set(false);
            },
            error: () => { this.moreError.set(true); this.loadingMore.set(false); }
        });
    }
}
