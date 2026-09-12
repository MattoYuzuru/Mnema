import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';

import { NativeDocument } from '../native-document';
import { buildNativeRenderState } from './native-render-state';

/**
 * Embeds a native document inside a reader screen. The host owns `main` and its
 * single `h1`; document-local heading levels are rendered beneath that outline.
 */
@Component({
    selector: 'app-native-document-renderer',
    imports: [NgTemplateOutlet],
    templateUrl: './native-document-renderer.component.html',
    styleUrl: './native-document-renderer.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class NativeDocumentRendererComponent {
    readonly document = input.required<NativeDocument>();
    protected readonly renderState = computed(() => buildNativeRenderState(this.document()));
}
