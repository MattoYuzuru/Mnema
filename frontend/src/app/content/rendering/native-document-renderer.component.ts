import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';

import { NativeDocument } from '../native-document';
import { buildNativeRenderState } from './native-render-state';
import { NativeMermaidComponent } from './native-mermaid.component';

/**
 * Embeds a native document inside a reader screen. The host owns `main` and its
 * single `h1`; document-local heading levels are rendered beneath that outline.
 */
@Component({
    selector: 'app-native-document-renderer',
    imports: [NgTemplateOutlet, NativeMermaidComponent],
    templateUrl: './native-document-renderer.component.html',
    styleUrl: './native-document-renderer.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class NativeDocumentRendererComponent {
    readonly document = input.required<NativeDocument>();
    /** Authorized, short-lived URLs supplied by the owning Browse/editor/Study surface. */
    readonly assetSources = input<Readonly<Record<string, string>>>({});
    protected readonly renderState = computed(() => buildNativeRenderState(this.document()));
}
