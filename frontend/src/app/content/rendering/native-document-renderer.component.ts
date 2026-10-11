import { ChangeDetectionStrategy, Component, computed, forwardRef, input, output } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';

import { NativeDocument } from '../native-document';
import { buildNativeRenderState } from './native-render-state';
import { NativeMermaidComponent } from './native-mermaid.component';
import { NativeMediaPlayerComponent } from './native-media-player.component';
import { NativeMediaImageComponent } from './native-media-image.component';
import { NativeYoutubeComponent } from './native-youtube.component';
import { BlockOverlay, NATIVE_BLOCK_HOST, NativeBlockHost, TopBlockDirective } from './native-top-block.directive';

export interface AssetPlaybackSource {
    readonly url: string;
    readonly posterUrl?: string | null;
    readonly downloadUrl?: string | null;
    readonly mimeType?: string;
}

/**
 * Embeds a native document inside a reader screen. The host owns `main` and its
 * single `h1`; document-local heading levels are rendered beneath that outline.
 */
@Component({
    selector: 'app-native-document-renderer',
    imports: [NgTemplateOutlet, NativeMermaidComponent, NativeMediaPlayerComponent,
        NativeMediaImageComponent, NativeYoutubeComponent, TopBlockDirective],
    providers: [{ provide: NATIVE_BLOCK_HOST, useExisting: forwardRef(() => NativeDocumentRendererComponent) }],
    templateUrl: './native-document-renderer.component.html',
    styleUrl: './native-document-renderer.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class NativeDocumentRendererComponent implements NativeBlockHost {
    readonly document = input.required<NativeDocument>();
    /** Authorized, short-lived URLs supplied by the owning Browse/editor/Study surface. */
    readonly assetSources = input<Readonly<Record<string, string | AssetPlaybackSource>>>({});
    readonly assetStatuses = input<Readonly<Record<string, string | undefined>>>({});
    readonly assetFailed = output<string>();
    /**
     * Puts `data-node-id` on every top-level block. Only the Workshop asks for it (a selection is mapped to blocks by it); Browse,
     * Study and the editor preview never do, so node ids stay out of the reader's DOM.
     */
    readonly exposeNodeIds = input(false);
    /** Marks and slots around top-level blocks (the Workshop's edits); `null` draws the document as it is. */
    readonly overlay = input<BlockOverlay | null>(null);
    /**
     * How many levels the host's own headings take below the page's `h1` and section heading. 0 (the default) draws a document's
     * level 1 as `h2`; a host that puts its own `h2` above the document (a public material under «Материал 3 из 12») passes 1, and the
     * document's level 1 becomes level 3 (`role="heading"` with `aria-level`, so the levels past `h6` stay valid).
     */
    readonly headingOffset = input(0);
    protected readonly sources = computed(() => new Map(Object.entries(this.assetSources())
        .map(([id, source]) => [id.toLowerCase(), typeof source === 'string' ? { url: source } : source])));
    protected readonly renderState = computed(() => buildNativeRenderState(this.document()));

    /** «Статус: подпись». A status that already ends in a full stop or an ellipsis loses it, so no «Файл недоступен.: …» is read. */
    protected pendingText(assetId: string, fallback: string, alt: string): string {
        const status = (this.assetStatuses()[assetId.toLowerCase()] ?? fallback).replace(/[.…]+$/u, '');
        return `${status}: ${alt}`;
    }
}
