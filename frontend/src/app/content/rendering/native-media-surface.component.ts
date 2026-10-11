import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';

import { NativeDocument } from '../native-document';
import { NativeRenderNode, buildNativeRenderState } from './native-render-state';
import { MediaPlaybackApi, MediaPlaybackView } from './media-playback.api';
import { AssetPlaybackSource, NativeDocumentRendererComponent } from './native-document-renderer.component';
import { BlockOverlay } from './native-top-block.directive';

const FIRST_POLL_MS = 2_000;
const MAX_POLL_MS = 15_000;
const MIN_REFRESH_MS = 1_000;
const URL_RENEW_MARGIN_MS = 60_000;
const MAX_RENEW_CHECK_MS = 15 * 60_000;

/** Owner-scoped media hydration. Pending assets poll only while visible; no persistent socket. */
@Component({
    selector: 'app-native-media-surface',
    imports: [NativeDocumentRendererComponent],
    template: `<app-native-document-renderer [document]="document()" [assetSources]="sources()"
        [assetStatuses]="statuses()" [exposeNodeIds]="exposeNodeIds()" [overlay]="overlay()" [headingOffset]="headingOffset()" (assetFailed)="refreshFailed($event)" />`,
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class NativeMediaSurfaceComponent {
    readonly document = input.required<NativeDocument>();
    /** Workshop only: node ids on the top-level blocks, and what is drawn around them (see the renderer). */
    readonly exposeNodeIds = input(false);
    readonly overlay = input<BlockOverlay | null>(null);
    /**
     * Whose files these are. `owner` (the default) speaks to the account that owns them; `public` is a reader of a shared deck,
     * who is told only that the file is unavailable, never that it «does not belong to this account».
     */
    /** Levels the document's headings sit lower than usual (see the renderer); a public material under its own `h2` passes 1. */
    readonly headingOffset = input(0);
    readonly audience = input<'owner' | 'public'>('owner');
    readonly sources = signal<Readonly<Record<string, AssetPlaybackSource>>>({});
    readonly statuses = signal<Readonly<Record<string, string>>>({});

    private readonly api = inject(MediaPlaybackApi);
    private readonly destroyRef = inject(DestroyRef);
    private readonly ids = computed(() => mediaIds(this.document()));
    private readonly views = new Map<string, MediaPlaybackView>();
    private readonly failures = new Map<string, number>();
    private readonly readErrors = new Map<string, string>();
    private readonly terminal = new Set<string>();
    private timer: ReturnType<typeof setTimeout> | null = null;
    private delay = FIRST_POLL_MS;
    private polling = false;

    constructor() {
        effect(() => {
            const ids = this.ids();
            for (const id of [...this.views.keys()]) if (!ids.includes(id)) this.views.delete(id);
            for (const id of [...this.terminal]) if (!ids.includes(id)) this.terminal.delete(id);
            this.publishViews();
            void this.refresh(ids);
        });
        const wake = () => {
            this.clearTimer();
            if (document.visibilityState === 'visible' && navigator.onLine) {
                this.delay = FIRST_POLL_MS;
                this.terminal.clear();
                void this.refresh(this.ids(), true);
            }
        };
        document.addEventListener('visibilitychange', wake);
        window.addEventListener('focus', wake);
        window.addEventListener('online', wake);
        this.destroyRef.onDestroy(() => {
            document.removeEventListener('visibilitychange', wake);
            window.removeEventListener('focus', wake);
            window.removeEventListener('online', wake);
            this.clearTimer();
        });
    }

    refreshFailed(assetId: string): void {
        const id = assetId.toLowerCase();
        const last = this.failures.get(id) ?? 0;
        if (Date.now() - last < 30_000) return;
        this.failures.set(id, Date.now());
        void this.refresh([id], true);
    }

    private async refresh(ids: readonly string[], force = false): Promise<void> {
        if (this.destroyRef.destroyed || document.visibilityState !== 'visible' || !navigator.onLine
            || this.polling || ids.length === 0) return;
        this.clearTimer();
        this.polling = true;
        const now = Date.now();
        const due = ids.filter(id => !this.terminal.has(id) && (force || !this.views.has(id) || pending(this.views.get(id)!)
            || (this.views.get(id)?.playback?.expiresAt !== undefined
                && Date.parse(this.views.get(id)!.playback!.expiresAt) - now < URL_RENEW_MARGIN_MS)));
        const result = await Promise.allSettled(due.map(id => this.api.read(id)));
        this.polling = false;
        if (this.destroyRef.destroyed) return;
        let changed = false;
        result.forEach((entry, index) => {
            const id = due[index]!;
            if (entry.status === 'fulfilled' && this.ids().includes(id)) {
                const previous = this.views.get(id);
                this.views.set(id, entry.value);
                this.readErrors.delete(id);
                changed ||= previous?.state !== entry.value.state;
            } else if (entry.status === 'rejected') {
                const forbidden = entry.reason instanceof HttpErrorResponse
                    && (entry.reason.status === 403 || entry.reason.status === 404);
                if (forbidden) { this.terminal.add(id); this.views.delete(id); }
                this.readErrors.set(id, forbidden
                    ? (this.audience() === 'public' ? 'Файл недоступен.' : 'Файл недоступен или не принадлежит этому аккаунту.')
                    : 'Не удалось проверить файл. Повторим позже.');
            }
        });
        this.publishViews();
        this.delay = changed ? FIRST_POLL_MS : Math.min(this.delay * 2, MAX_POLL_MS);
        this.schedule();
    }

    private publishViews(): void {
        const sources: Record<string, AssetPlaybackSource> = {};
        const statuses: Record<string, string> = {};
        for (const [id, view] of this.views) {
            if (view.state === 'READY' && view.playback) {
                sources[id] = { url: view.playback.url, mimeType: view.playback.mimeType,
                    posterUrl: view.poster?.url, downloadUrl: view.download?.url };
            } else statuses[id] = switchStatus(view.state);
        }
        for (const [id, message] of this.readErrors) statuses[id] = message;
        this.sources.set(sources);
        this.statuses.set(statuses);
    }

    private schedule(): void {
        if (this.destroyRef.destroyed || this.timer || document.visibilityState !== 'visible' || !navigator.onLine) return;
        const active = this.ids().filter(id => !this.terminal.has(id)).map(id => this.views.get(id));
        if (active.some(view => view === undefined || pending(view))) {
            this.timer = setTimeout(() => { this.timer = null; void this.refresh(this.ids()); }, this.delay);
            return;
        }
        const expiry = active.map(view => view?.playback?.expiresAt).filter((value): value is string => !!value)
            .map(value => Date.parse(value) - Date.now() - URL_RENEW_MARGIN_MS)
            .filter(Number.isFinite);
        if (expiry.length) this.timer = setTimeout(() => { this.timer = null; void this.refresh(this.ids()); },
            Math.max(MIN_REFRESH_MS, Math.min(MAX_RENEW_CHECK_MS, ...expiry)));
    }

    private clearTimer(): void {
        if (this.timer) clearTimeout(this.timer);
        this.timer = null;
    }
}

function pending(view: MediaPlaybackView): boolean {
    return ['PENDING_UPLOAD', 'VERIFYING', 'PROCESSING'].includes(view.state);
}

function switchStatus(state: MediaPlaybackView['state']): string {
    switch (state) {
        case 'PENDING_UPLOAD': return 'Загружаем файл…';
        case 'VERIFYING': return 'Проверяем файл…';
        case 'PROCESSING': return 'Готовим файл к просмотру…';
        case 'FAILED_RETRYABLE': return 'Обработка прервалась. Автор может повторить её.';
        case 'REJECTED': return 'Файл не прошёл проверку. Автор может заменить его.';
        case 'DELETED': return 'Файл удалён.';
        case 'READY': return 'Файл готов к просмотру.';
    }
}

function mediaIds(documentValue: NativeDocument): readonly string[] {
    const state = buildNativeRenderState(documentValue);
    if (state.status !== 'ready') return [];
    const ids = new Set<string>();
    const walk = (node: NativeRenderNode): void => {
        if (node.kind === 'image' || node.kind === 'audio' || node.kind === 'video') ids.add(node.assetId.toLowerCase());
        if ('content' in node) node.content.forEach(walk);
    };
    walk(state.root);
    return [...ids];
}
