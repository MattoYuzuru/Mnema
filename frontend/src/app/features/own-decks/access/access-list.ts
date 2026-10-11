import { DestroyRef, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Observable, Subscription } from 'rxjs';

import { KeysetPage } from './access.models';

export type AccessListPhase = 'loading' | 'error' | 'ready';

/**
 * One keyset list of the panel (invitations, requests, people) for the shared {@link AutoLoadComponent}: the first page replaces the
 * rows (a reload keeps the old rows until the answer, so nothing jumps), a continuation appends and deduplicates by id, and an answer
 * of an older load is discarded by an epoch. A failed first page offers a retry (`phase = 'error'`); a failed continuation keeps every
 * row and is retried by the auto-load control through `moreError`.
 */
export class AccessList<T> {
    readonly items = signal<readonly T[]>([]);
    readonly next = signal<string | null>(null);
    readonly phase = signal<AccessListPhase>('loading');
    readonly loadingMore = signal(false);
    readonly moreError = signal<string | null>(null);
    /** Changes whenever the snapshot is replaced, so the auto-load control re-arms. */
    readonly context = signal(0);
    private epoch = 0;
    private running: Subscription | null = null;

    constructor(
        private readonly fetch: (cursor: string | null) => Observable<KeysetPage<T>>,
        private readonly key: (item: T) => string,
        private readonly destroy: DestroyRef,
        private readonly moreFailed: string
    ) {
        destroy.onDestroy(() => this.running?.unsubscribe());
    }

    reload(): void {
        const epoch = ++this.epoch;
        this.running?.unsubscribe();
        this.context.update(value => value + 1);
        this.loadingMore.set(false);
        this.moreError.set(null);
        if (this.items().length === 0) this.phase.set('loading');
        this.running = this.fetch(null).pipe(takeUntilDestroyed(this.destroy)).subscribe({
            next: page => {
                if (epoch !== this.epoch) return;
                this.items.set(this.merge([], page.items));
                this.next.set(page.nextCursor);
                this.phase.set('ready');
            },
            error: () => { if (epoch === this.epoch && this.phase() !== 'ready') this.phase.set('error'); }
        });
    }

    more(): void {
        const cursor = this.next();
        if (cursor === null || this.phase() !== 'ready' || this.loadingMore()) return;
        const epoch = this.epoch;
        this.loadingMore.set(true);
        this.moreError.set(null);
        this.fetch(cursor).pipe(takeUntilDestroyed(this.destroy)).subscribe({
            next: page => {
                if (epoch !== this.epoch) return;
                this.items.update(rows => this.merge(rows, page.items));
                this.next.set(page.nextCursor === cursor ? null : page.nextCursor);
                this.loadingMore.set(false);
            },
            error: () => {
                if (epoch !== this.epoch) return;
                this.loadingMore.set(false);
                this.moreError.set(this.moreFailed);
            }
        });
    }

    /** Takes a row out (decided, revoked) without touching the cursor. */
    remove(id: string): void {
        this.items.update(rows => rows.filter(row => this.key(row) !== id));
    }

    /** Puts a changed row in place (an invitation that was revoked). */
    replace(item: T): void {
        const id = this.key(item);
        this.items.update(rows => rows.map(row => this.key(row) === id ? item : row));
    }

    private merge(existing: readonly T[], added: readonly T[]): readonly T[] {
        const known = new Set(existing.map(this.key));
        const result = [...existing];
        for (const item of added) {
            const id = this.key(item);
            if (!known.has(id)) { known.add(id); result.push(item); }
        }
        return result;
    }
}
