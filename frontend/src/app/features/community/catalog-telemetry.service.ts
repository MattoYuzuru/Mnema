import { HttpErrorResponse } from '@angular/common/http';
import { DestroyRef, Injectable, effect, inject, signal, untracked } from '@angular/core';
import { NavigationStart, Router } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subscription, timeout } from 'rxjs';
import { InterestStore } from './interest-store.service';
import { CardAttribution, RecommendationsApiService } from './recommendations-api.service';

export const EXPOSURE_FLUSH_MS = 300;
const CODE = /^[1-9A-HJ-NP-Za-km-z]{10}$/u;
/** Best effort, bounded to the current navigation/account; never stores query/content text or persistent tracking state. */
@Injectable({ providedIn: 'root' })
export class CatalogTelemetry {
    private readonly interests = inject(InterestStore);
    private readonly api = inject(RecommendationsApiService);
    private readonly router = inject(Router);
    private readonly destroy = inject(DestroyRef);
    readonly accepting = signal(true);
    private readonly exposureRequests = new Set<Subscription>();
    private readonly requests = new Set<Subscription>();
    private readonly seen = new Set<string>();
    private navigationEpoch = 0;
    private readonly buffers = new Map<string, { readonly surface: CardAttribution['surface']; readonly rankVersion: CardAttribution['rankVersion']; readonly items: { readonly code: string; readonly position: number | null }[] }>();
    private flushTimer: ReturnType<typeof setTimeout> | null = null;
    private attribution: CardAttribution | null = null;
    private pendingDetail: string | null = null;
    private owner: string | null = this.interests.account();
    private detailCode: string | null = null;
    constructor() {
        effect(() => {
            const account = this.interests.account();
            const enabled = this.interests.events();
            const ready = this.interests.ready();
            untracked(() => {
                if (account !== this.owner) { this.clear(true); this.owner = account; }
                if (ready && this.pendingDetail !== null) { const code = this.pendingDetail; this.pendingDetail = null; if (enabled) this.detail(code); }
                if (ready && !enabled) this.clear(true);
            });
        });
        this.router.events.pipe(takeUntilDestroyed(this.destroy)).subscribe(event => {
            if (!(event instanceof NavigationStart)) return;
            this.flush();
            this.navigationEpoch++;
            this.seen.clear();
            const code = /^\/d\/([1-9A-HJ-NP-Za-km-z]{10})(?:[/?#]|$)/u.exec(event.url)?.[1] ?? null;
            if (this.attribution?.code !== code) this.attribution = null;
            if (this.pendingDetail !== code) this.pendingDetail = null;
            if (this.detailCode !== code) this.detailCode = null;
        });
        this.destroy.onDestroy(() => {
            // The service survives ordinary screen navigation. At app teardown only a still-current account may flush.
            if (this.owner === this.interests.account() && this.interests.events()) this.flush(true);
            this.cancelFlush(); this.buffers.clear(); this.seen.clear(); this.attribution = null; this.pendingDetail = null;
        });
    }
    private clear(forgetAttribution: boolean): void {
        this.cancelFlush(); this.buffers.clear();
        this.seen.clear();
        this.requests.forEach(request => request.unsubscribe()); this.requests.clear(); this.exposureRequests.clear(); this.accepting.set(true);
        if (forgetAttribution) { this.attribution = null; this.pendingDetail = null; this.detailCode = null; }
    }
    private valid(context: CardAttribution): boolean {
        return this.interests.events() && CODE.test(context.code) && ['catalog','search','for-you','author','detail'].includes(context.surface)
            && ['catalog-v1','recs-v1'].includes(context.rankVersion) && (context.position === null || (Number.isInteger(context.position) && context.position >= 1 && context.position <= 1000));
    }
    visible(context: CardAttribution): void {
        if (!this.valid(context) || !this.accepting()) return;
        const key = `exposure:${context.surface}:${context.rankVersion}:${context.code}`;
        if (!this.once(key)) return;
        const baseKey = `${this.navigationEpoch}:${context.surface}:${context.rankVersion}`;
        let occurrence = 0;
        let keyOfBuffer = `${baseKey}:${occurrence}`;
        let buffer = this.buffers.get(keyOfBuffer);
        // A removed card may legitimately appear again after dedupe eviction; it still cannot repeat inside a wire batch.
        while (buffer?.items.some(item => item.code === context.code)) {
            keyOfBuffer = `${baseKey}:${++occurrence}`;
            buffer = this.buffers.get(keyOfBuffer);
        }
        if (buffer === undefined) { buffer = { surface: context.surface, rankVersion: context.rankVersion, items: [] }; this.buffers.set(keyOfBuffer, buffer); }
        buffer.items.push({ code: context.code, position: context.position });
        if ([...this.buffers.values()].reduce((count, row) => count + row.items.length, 0) >= 1000) this.accepting.set(false);
        if (buffer.items.length >= 48) this.flush();
        else if (this.flushTimer === null) this.flushTimer = setTimeout(() => this.flush(), EXPOSURE_FLUSH_MS);
    }
    private cancelFlush(): void {
        if (this.flushTimer !== null) clearTimeout(this.flushTimer);
        this.flushTimer = null;
    }
    private flush(draining = false): void {
        this.cancelFlush();
        if (this.owner !== this.interests.account() || !this.interests.events()) { this.buffers.clear(); return; }
        for (const [key, buffer] of this.buffers) {
            while (buffer.items.length > 0 && (draining || this.exposureRequests.size < 4)) {
                this.send(this.api.exposures(buffer.surface, buffer.rankVersion, buffer.items.splice(0, 48)), true);
            }
            if (buffer.items.length === 0) this.buffers.delete(key);
            if (!draining && this.exposureRequests.size >= 4) break;
        }
        if ([...this.buffers.values()].reduce((count, row) => count + row.items.length, 0) < 1000) this.accepting.set(true);
    }
    /** Retain exposure dedupe only for cards still rendered by the current screen; queued batches have their own epoch. */
    retainCards(codes: readonly string[]): void {
        const displayed = new Set(codes);
        for (const key of this.seen) if (key.startsWith('exposure:') && !displayed.has(key.slice(key.lastIndexOf(':') + 1))) this.seen.delete(key);
    }
    opened(context: CardAttribution, search: boolean): void {
        if (!this.valid(context)) return;
        this.attribution = { code: context.code, surface: context.surface, position: context.position, rankVersion: context.rankVersion };
        if (search && this.once(`search:${context.code}`)) this.send(this.api.event('SEARCH_CLICK', context));
    }
    detail(code: string): void {
        if (!this.interests.ready() && this.interests.account() !== null && CODE.test(code)) { this.pendingDetail = code; return; }
        const context = this.attribution?.code === code ? this.attribution : { code, surface: 'detail' as const, position: null, rankVersion: 'catalog-v1' as const };
        this.attribution = null;
        if (!this.valid(context) || this.detailCode === code || !this.once(`detail:${code}`)) return;
        this.detailCode = code;
        this.send(this.api.event('DETAIL_VIEW', context));
    }
    private once(key: string): boolean {
        if (this.seen.has(key)) return false;
        this.seen.add(key); return true;
    }
    private send(request: import('rxjs').Observable<void>, exposure = false): void {
        // Exposures use four network slots and retain the buffered items until a slot is free. Clicks/detail are at most two
        // attempts per navigation, have no pending queue and time out after five seconds; no event is sampled by a capacity cap.
        let subscription: Subscription | null = null;
        const done = (): void => {
            if (subscription === null) return;
            this.requests.delete(subscription); this.exposureRequests.delete(subscription);
            if (exposure) this.flush();
        };
        subscription = request.pipe(timeout(5000)).subscribe({ error: (error: unknown) => { if (error instanceof HttpErrorResponse && error.status === 404) this.interests.disableEvents(); done(); }, complete: done });
        if (!subscription.closed) {
            this.requests.add(subscription);
            if (exposure) this.exposureRequests.add(subscription);
        }
    }
}
