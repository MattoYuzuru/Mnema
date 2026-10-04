import { ChangeDetectionStrategy, Component, DestroyRef, effect, inject, input, signal } from '@angular/core';

import { MediaPlaybackApi, MediaPlaybackView } from '../../content/rendering/media-playback.api';

/**
 * The picture of one found image (AI-10, #296). A candidate is the owner's own media asset, so it is read like every other asset the
 * Workshop shows: through the owner-scoped playback API, never a link to the stock site. A card is small and the zoom, download and GIF
 * controls of the document's figure would be buttons inside a label, so the card draws a plain `<img>`; the full-size image is the one
 * in the document once the candidate is chosen. A file that is not ready or cannot be read says so in words, and a failed picture is
 * asked for again once (the signed address may have expired).
 */
@Component({
    selector: 'app-image-candidate-thumb',
    template: `
      @if (source(); as url) {
        <img [src]="url" [alt]="alt()" loading="lazy" decoding="async" (error)="failed()" />
      } @else {
        <span class="thumb-note">{{ note() }}</span>
      }
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; }
      img { display: block; inline-size: 100%; block-size: auto; aspect-ratio: 3 / 2; object-fit: cover; background: var(--mn-soft); }
      .thumb-note { display: grid; place-items: center; aspect-ratio: 3 / 2; padding: .5rem; background: var(--mn-soft); color: var(--mn-muted); font: .85rem/1.35 var(--mn-font-body, system-ui, sans-serif); text-align: center; }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class ImageCandidateThumbComponent {
    readonly assetId = input.required<string>();
    readonly alt = input('');

    private readonly api = inject(MediaPlaybackApi);
    protected readonly source = signal<string | null>(null);
    protected readonly note = signal('Загружаем изображение…');
    private retried = false;
    private token = 0;

    constructor() {
        const destroyed = inject(DestroyRef);
        let gone = false;
        destroyed.onDestroy(() => { gone = true; });
        effect(() => {
            const assetId = this.assetId();
            this.retried = false;
            const token = ++this.token;
            void this.read(assetId, token, () => gone);
        });
    }

    /** The picture could not be drawn: read the asset again once, then say so. */
    protected failed(): void {
        if (this.retried) {
            this.source.set(null);
            this.note.set('Не удалось показать изображение.');
            return;
        }
        this.retried = true;
        void this.read(this.assetId(), ++this.token, () => false);
    }

    private async read(assetId: string, token: number, gone: () => boolean): Promise<void> {
        let view: MediaPlaybackView;
        try {
            view = await this.api.read(assetId);
        } catch {
            if (token === this.token && !gone()) { this.source.set(null); this.note.set('Не удалось загрузить изображение.'); }
            return;
        }
        if (token !== this.token || gone()) return;
        if (view.state === 'READY' && view.playback !== null) {
            this.source.set(view.playback.url);
        } else {
            this.source.set(null);
            this.note.set(view.state === 'REJECTED' || view.state === 'DELETED' ? 'Изображение недоступно.' : 'Проверяем файл…');
        }
    }
}
