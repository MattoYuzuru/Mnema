import { DestroyRef, signal } from '@angular/core';
import { Observable, Subscription } from 'rxjs';

import { PublicDeckFailure, publicFailureOf } from '../public-deck/public-deck.models';
import { CommunityCard, CommunityDeckPage } from './community.models';

export type CommunityPagerPhase = 'idle' | 'loading' | 'ready' | 'error';

/**
 * The cursor list of the catalogue behind `app-auto-load`, with the rules of `PublicPager` (rows accumulate by `code`, one request at a time,
 * an answer that belongs to an older list is dropped) and the two things the catalogue adds: a request that is replaced or {@link cancel}led is
 * unsubscribed, so only the latest answer ever renders, and the count says whether the server counted only up to `total`.
 *
 * A 412 on a next page (the rank generation of the cursor expired) starts the list again from its first page, silently; a 412 on a first page is asked again once. A failed next page
 * keeps the rows and waits for the explicit retry of the auto-load element. A failed first page is exposed as {@link failure}.
 */
export class CommunityPager {
    readonly rows = signal<readonly CommunityCard[]>([]);
    readonly total = signal(0);
    readonly capped = signal(false);
    readonly cursor = signal<string | null>(null);
    readonly phase = signal<CommunityPagerPhase>('idle');
    readonly failure = signal<PublicDeckFailure | null>(null);
    readonly loadingMore = signal(false);
    readonly moreFailed = signal(false);
    /** Changes with every start, so the auto-load element may continue even when the cursor repeats. */
    readonly context = signal(0);

    private subscription: Subscription | null = null;
    private sequence = 0;
    /** A first page is asked again once, silently, if the server says its generation is gone (412); a second 412 is a failure. */
    private restartsLeft = 1;

    constructor(private readonly fetch: (cursor: string | null) => Observable<CommunityDeckPage>, destroyRef: DestroyRef) {
        destroyRef.onDestroy(() => this.subscription?.unsubscribe());
    }

    /** Reads the first page, forgetting what was read and abandoning any request on its way. */
    start(): void {
        this.restartsLeft = 1;
        this.read();
    }

    private read(): void {
        const sequence = ++this.sequence;
        this.subscription?.unsubscribe();
        this.phase.set('loading');
        this.failure.set(null);
        this.moreFailed.set(false);
        this.loadingMore.set(false);
        this.cursor.set(null);
        this.rows.set([]);
        this.total.set(0);
        this.capped.set(false);
        this.context.update(value => value + 1);
        this.subscription = this.fetch(null).subscribe({
            next: page => {
                if (sequence !== this.sequence) return;
                this.rows.set(unique(page.decks));
                this.total.set(page.total);
                this.capped.set(page.totalCapped);
                this.cursor.set(page.nextCursor);
                this.phase.set('ready');
            },
            error: (error: unknown) => {
                if (sequence !== this.sequence) return;
                const failure = publicFailureOf(error);
                if (failure.kind === 'stale' && this.restartsLeft > 0) {
                    this.restartsLeft--;
                    this.read();
                    return;
                }
                this.failure.set(failure);
                this.phase.set('error');
            }
        });
    }

    /** Forgets the list and abandons any request: the screen shows something else. */
    cancel(): void {
        this.sequence++;
        this.subscription?.unsubscribe();
        this.subscription = null;
        this.phase.set('idle');
        this.loadingMore.set(false);
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
                const known = new Set(this.rows().map(row => row.code));
                this.rows.update(current => [...current, ...unique(page.decks).filter(row => !known.has(row.code))]);
                this.total.set(page.total);
                this.capped.set(page.totalCapped);
                this.cursor.set(page.nextCursor);
            },
            error: (error: unknown) => {
                if (sequence !== this.sequence) return;
                this.loadingMore.set(false);
                if (publicFailureOf(error).kind === 'stale') this.start();
                else this.moreFailed.set(true);
            }
        });
    }
}

function unique(rows: readonly CommunityCard[]): readonly CommunityCard[] {
    const seen = new Set<string>();
    return rows.filter(row => {
        if (seen.has(row.code)) return false;
        seen.add(row.code);
        return true;
    });
}
