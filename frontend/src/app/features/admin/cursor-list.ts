import { DestroyRef, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Observable, firstValueFrom } from 'rxjs';
import { errorText, isForbidden } from './admin-presenters';

export interface CursorPage<T> {
    readonly items: readonly T[];
    readonly next: string | null;
}

export interface CursorListOptions<T> {
    readonly fetch: (cursor: string | null) => Observable<CursorPage<T>>;
    /** Stable identity: a row that moved between pages is shown once. */
    readonly key: (item: T) => string;
    readonly destroy: DestroyRef;
    readonly firstError: string;
    readonly moreError: string;
    /** Called after a 401/403 so the owner of the list can drop every dependent private view. */
    readonly onForbidden?: () => void;
}

/**
 * Keyset list for the shared {@link AutoLoadComponent}: the first page replaces the list and bumps `context`, a continuation
 * appends and deduplicates by id. A stale response (reload, filter change, leaving the page) is discarded by an epoch.
 * A failed first page offers a retry through `error`; a failed continuation keeps every row and is retried explicitly by the
 * auto-load control through `moreError`.
 */
export class CursorList<T> {
    readonly items = signal<readonly T[]>([]);
    readonly next = signal<string | null>(null);
    readonly loading = signal(false);
    readonly loadingMore = signal(false);
    readonly error = signal('');
    readonly moreError = signal<string | null>(null);
    /** Changes whenever the list snapshot is replaced, so the auto-load control re-arms. */
    readonly context = signal(0);
    private epoch = 0;

    constructor(private readonly options: CursorListOptions<T>) { }

    async reload(): Promise<boolean> {
        const epoch = ++this.epoch;
        this.context.update(value => value + 1);
        this.loading.set(true);
        this.loadingMore.set(false);
        this.error.set('');
        this.moreError.set(null);
        try {
            const page = await firstValueFrom(this.options.fetch(null).pipe(takeUntilDestroyed(this.options.destroy)));
            if (epoch !== this.epoch)
                return false;
            this.items.set(this.unique([], page.items));
            this.next.set(page.next);
            return true;
        } catch (failure) {
            if (epoch === this.epoch) {
                if (isForbidden(failure))
                    this.forbidden();
                this.error.set(errorText(failure, this.options.firstError));
            }
            return false;
        } finally {
            if (epoch === this.epoch)
                this.loading.set(false);
        }
    }

    async more(): Promise<void> {
        const cursor = this.next();
        if (cursor === null || this.loading() || this.loadingMore())
            return;
        const epoch = this.epoch;
        this.loadingMore.set(true);
        this.moreError.set(null);
        try {
            const page = await firstValueFrom(this.options.fetch(cursor).pipe(takeUntilDestroyed(this.options.destroy)));
            if (epoch !== this.epoch)
                return;
            this.items.update(list => this.unique(list, page.items));
            this.next.set(page.next === cursor ? null : page.next);
        } catch (failure) {
            if (epoch === this.epoch) {
                if (isForbidden(failure))
                    this.forbidden();
                this.moreError.set(errorText(failure, this.options.moreError));
            }
        } finally {
            if (epoch === this.epoch)
                this.loadingMore.set(false);
        }
    }

    /** Replaces one loaded row (for example after a switch) without disturbing the cursor. */
    replace(item: T): void {
        const key = this.options.key(item);
        this.items.update(list => list.map(row => this.options.key(row) === key ? item : row));
    }

    /** Forgets everything and invalidates requests in flight. */
    clear(): void {
        ++this.epoch;
        this.discard();
        this.loading.set(false);
        this.loadingMore.set(false);
        this.error.set('');
        this.moreError.set(null);
    }

    private discard(): void {
        this.items.set([]);
        this.next.set(null);
    }

    private forbidden(): void {
        this.discard();
        this.options.onForbidden?.();
    }

    private unique(existing: readonly T[], added: readonly T[]): readonly T[] {
        const known = new Set(existing.map(this.options.key));
        const result = [...existing];
        for (const item of added) {
            const key = this.options.key(item);
            if (!known.has(key)) {
                known.add(key);
                result.push(item);
            }
        }
        return result;
    }
}
