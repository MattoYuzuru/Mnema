import { ChangeDetectionStrategy, Component, DestroyRef, NgZone, effect, inject, input, signal } from '@angular/core';
import { Subscription } from 'rxjs';

import { MEDIA_PLAYBACK_RESOLVER } from '../../features/study/media-playback-resolver';
import { SignedMediaSource } from '../rendering/media-playback.api';
import { NativeMediaImageComponent } from '../rendering/native-media-image.component';
import { NativeMediaPlayerComponent } from '../rendering/native-media-player.component';

const RETRY_MS = 4_000;
const RENEW_MARGIN_MS = 60_000;
const MIN_RENEW_MS = 1_000;
const MAX_RENEW_MS = 15 * 60_000;

/**
 * One image, audio or video asset of an exercise. It resolves its own short-lived playback URL, renews it
 * before expiry and keeps polling while the asset is still being processed. The accessible `name` is
 * supplied by the caller; players are siblings of any selection control, never children of one.
 */
@Component({
    selector: 'app-learner-media',
    imports: [NativeMediaPlayerComponent, NativeMediaImageComponent],
    template: `
      @if (source(); as resolved) {
        @if (kind() === 'image') {
          <app-native-media-image [source]="resolved.url" [download]="resolved.url" [alt]="name()"
            [animated]="resolved.mimeType === 'image/gif'" (sourceFailed)="refresh()" />
        } @else {
          <app-native-media-player [kind]="kind() === 'video' ? 'video' : 'audio'" [title]="name()"
            [source]="resolved.url" (sourceFailed)="refresh()" />
        }
      } @else {
        <p class="media-status" role="status">{{ name() }}: файл пока недоступен или ещё обрабатывается.
          <button type="button" class="media-retry" (click)="refresh()">Проверить снова</button></p>
      }
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; max-inline-size: 100%; }
      .media-status { margin: 0; border: 1px dashed var(--mn-rule); padding: .75rem; color: var(--mn-muted); overflow-wrap: anywhere; }
      .media-retry { min-block-size: var(--mn-touch-min, 2.75rem); margin-inline-start: .5rem; border: 1px solid var(--mn-ink);
        padding: .4rem .8rem; color: var(--mn-ink); background: transparent; font: inherit; cursor: pointer; }
      .media-retry:focus-visible { outline: 3px solid var(--mn-focus, var(--mn-ink)); outline-offset: 3px; }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class LearnerMediaComponent {
    readonly kind = input.required<'image' | 'audio' | 'video'>();
    readonly assetId = input.required<string>();
    readonly name = input.required<string>();
    readonly source = signal<SignedMediaSource | null>(null);

    private readonly resolver = inject(MEDIA_PLAYBACK_RESOLVER);
    private readonly destroyRef = inject(DestroyRef);
    private readonly zone = inject(NgZone);
    private subscription: Subscription | null = null;
    private timer: ReturnType<typeof setTimeout> | null = null;
    private epoch = 0;

    constructor() {
        effect(() => {
            const assetId = this.assetId();
            this.source.set(null);
            this.resolve(assetId);
        });
        const wake = (): void => { if (document.visibilityState === 'visible' && navigator.onLine) this.refresh(); };
        document.addEventListener('visibilitychange', wake);
        window.addEventListener('online', wake);
        this.destroyRef.onDestroy(() => {
            document.removeEventListener('visibilitychange', wake);
            window.removeEventListener('online', wake);
            this.cancel();
        });
    }

    /** Re-resolves the playback URL now (failed playback, a manual retry or the window regaining focus). */
    refresh(): void { this.resolve(this.assetId()); }

    private resolve(assetId: string): void {
        this.cancel();
        const epoch = this.epoch;
        this.subscription = this.resolver.resolve(assetId).subscribe({
            next: value => {
                if (epoch !== this.epoch) return;
                this.source.set(value);
                this.schedule(value);
            },
            error: () => { if (epoch === this.epoch) this.schedule(null); }
        });
    }

    private schedule(value: SignedMediaSource | null): void {
        if (this.destroyRef.destroyed) return;
        const expiry = value === null ? Number.NaN : Date.parse(value.expiresAt) - Date.now() - RENEW_MARGIN_MS;
        const delay = value === null || !Number.isFinite(expiry) ? RETRY_MS
            : Math.max(MIN_RENEW_MS, Math.min(MAX_RENEW_MS, expiry));
        const epoch = this.epoch;
        // A renewal timer must not keep the Angular zone busy: it is not application work in flight.
        this.timer = this.zone.runOutsideAngular(() => setTimeout(() => {
            if (epoch === this.epoch) this.zone.run(() => this.refresh());
        }, delay));
    }

    private cancel(): void {
        this.epoch += 1;
        this.subscription?.unsubscribe();
        this.subscription = null;
        if (this.timer !== null) clearTimeout(this.timer);
        this.timer = null;
    }
}
