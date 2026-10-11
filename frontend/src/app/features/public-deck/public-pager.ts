import { DestroyRef, signal } from '@angular/core';
import { Observable, Subscription } from 'rxjs';

import { PublicDeckFailure, publicFailureOf } from './public-deck.models';

/** One page of a public cursor list. */
export interface PublicListPage<T> {
    readonly rows: readonly T[];
    readonly total: number;
    readonly nextCursor: string | null;
}

export type PublicPagerPhase = 'loading' | 'ready' | 'error';

/**
 * The state of one public cursor list behind `app-auto-load`: rows accumulate by stable key, a page is requested at most
 * once at a time, and an answer that belongs to an older list is dropped. A 412 (the cursor belongs to an older publication
 * of the deck) restarts from the first page and raises {@link restarted} so the screen can say so politely; a failed
 * next page keeps the rows and waits for the explicit retry of the auto-load element. Any other first-page failure is
 * exposed as {@link failure}; one that says the deck is gone is also reported to {@link onGone}.
 */
export class PublicPager<T> {
    readonly rows = signal<readonly T[]>([]);
    readonly total = signal(0);
    readonly cursor = signal<string | null>(null);
    readonly phase = signal<PublicPagerPhase>('loading');
    readonly failure = signal<PublicDeckFailure | null>(null);
    readonly loadingMore = signal(false);
    readonly moreFailed = signal(false);
    /** The list was started again because the deck was republished while it was being read. */
    readonly restarted = signal(false);
    /** Changes with every restart, so the auto-load element may continue even when the cursor repeats. */
    readonly context = signal(0);

    private subscription: Subscription | null = null;
    private sequence = 0;

    constructor(
        private readonly fetch: (cursor: string | null) => Observable<PublicListPage<T>>,
        private readonly keyOf: (row: T) => string,
        destroyRef: DestroyRef,
        private readonly onGone: (failure: PublicDeckFailure) => void = () => undefined
    ) {
        destroyRef.onDestroy(() => this.subscription?.unsubscribe());
    }

    /** Reads the first page, forgetting what was read. */
    start(announceRestart = false): void {
        const sequence = ++this.sequence;
        this.subscription?.unsubscribe();
        this.phase.set('loading');
        this.failure.set(null);
        this.moreFailed.set(false);
        this.loadingMore.set(false);
        this.cursor.set(null);
        this.rows.set([]);
        this.total.set(0);
        this.context.update(value => value + 1);
        this.restarted.set(announceRestart);
        this.subscription = this.fetch(null).subscribe({
            next: page => {
                if (sequence !== this.sequence) return;
                this.rows.set(unique(page.rows, this.keyOf));
                this.total.set(page.total);
                this.cursor.set(page.nextCursor);
                this.phase.set('ready');
            },
            error: (error: unknown) => {
                if (sequence !== this.sequence) return;
                const failure = publicFailureOf(error);
                this.failure.set(failure);
                this.phase.set('error');
                if (failure.kind === 'not-found' || failure.kind === 'invite-only') this.onGone(failure);
            }
        });
    }

    /** Reads the next page; called by the auto-load element and by its retry. */
    next(): void {
        const cursor = this.cursor();
        if (cursor === null || this.loadingMore() || this.phase() !== 'ready') return;
        const sequence = this.sequence;
        this.loadingMore.set(true);
        this.moreFailed.set(false);
        this.subscription = this.fetch(cursor).subscribe({
            next: page => {
                if (sequence !== this.sequence) return;
                this.loadingMore.set(false);
                const known = new Set(this.rows().map(this.keyOf));
                this.rows.update(current => [...current, ...unique(page.rows, this.keyOf).filter(row => !known.has(this.keyOf(row)))]);
                this.total.set(page.total);
                this.cursor.set(page.nextCursor);
            },
            error: (error: unknown) => {
                if (sequence !== this.sequence) return;
                this.loadingMore.set(false);
                const failure = publicFailureOf(error);
                if (failure.kind === 'stale') this.start(true);
                else if (failure.kind === 'not-found' || failure.kind === 'invite-only') this.onGone(failure);
                else this.moreFailed.set(true);
            }
        });
    }
}

function unique<T>(rows: readonly T[], keyOf: (row: T) => string): readonly T[] {
    const seen = new Set<string>();
    return rows.filter(row => {
        const key = keyOf(row);
        if (seen.has(key)) return false;
        seen.add(key);
        return true;
    });
}
