import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';

import { NativeDocument } from '../native-document';
import { buildNativeRenderState } from './native-render-state';
import { NativeMermaidComponent } from './native-mermaid.component';
import { NativeMediaPlayerComponent } from './native-media-player.component';
import { NativeMediaImageComponent } from './native-media-image.component';
import { NativeYoutubeComponent } from './native-youtube.component';

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
        NativeMediaImageComponent, NativeYoutubeComponent],
    templateUrl: './native-document-renderer.component.html',
    styleUrl: './native-document-renderer.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class NativeDocumentRendererComponent {
    readonly document = input.required<NativeDocument>();
    /** Authorized, short-lived URLs supplied by the owning Browse/editor/Study surface. */
    readonly assetSources = input<Readonly<Record<string, string | AssetPlaybackSource>>>({});
    readonly assetStatuses = input<Readonly<Record<string, string | undefined>>>({});
    readonly assetFailed = output<string>();
    protected readonly sources = computed(() => new Map(Object.entries(this.assetSources())
        .map(([id, source]) => [id.toLowerCase(), typeof source === 'string' ? { url: source } : source])));
    protected readonly renderState = computed(() => buildNativeRenderState(this.document()));
}
