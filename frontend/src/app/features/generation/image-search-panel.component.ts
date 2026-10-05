import { ChangeDetectionStrategy, Component, ElementRef, afterNextRender, computed, input, output, signal, viewChild } from '@angular/core';

import { IMAGE_SEARCH_COST, IMAGE_SEARCH_RUNNING } from './generation-view';
import { MAX_SEARCH_QUERY_LENGTH } from './generation.models';
import { isSendKey } from './implicit-submit';

let nextPanel = 0;

/**
 * The inline panel under an image: «Что искать» (optional), «Искать», «Отмена» (AI-10, #296). It is not a dialog: it sits in the flow of
 * the document, so the text around it stays readable and reachable. There is no `<form>`, so no browser decides what Enter does: Enter
 * in the field sends the search unless an IME composes, Esc closes the panel, and the button is the only other way to send. While the
 * search runs the controls are `aria-disabled` and stay focusable (focus is never lost), and the panel itself says what is going on.
 */
@Component({
    selector: 'app-image-search-panel',
    templateUrl: './image-search-panel.component.html',
    styleUrl: './image-search-panel.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class ImageSearchPanelComponent {
    /** What the field holds when the panel opens (the previous query for «Ещё раз»). */
    readonly query = input('');
    /** The description of the image: the field's placeholder. */
    readonly placeholder = input('');
    /** A search is being sent or is running. */
    readonly pending = input(false);
    readonly error = input<string | null>(null);
    readonly submitted = output<string>();
    readonly cancelled = output<void>();

    private readonly field = viewChild.required<ElementRef<HTMLInputElement>>('field');
    private readonly uid = `mn-image-search-${nextPanel++}`;
    protected readonly fieldId = `${this.uid}-field`;
    protected readonly costId = `${this.uid}-cost`;
    protected readonly errorId = `${this.uid}-error`;
    protected readonly maxLength = MAX_SEARCH_QUERY_LENGTH;
    protected readonly cost = IMAGE_SEARCH_COST;
    protected readonly running = IMAGE_SEARCH_RUNNING;
    protected readonly value = signal<string | null>(null);
    protected readonly text = computed(() => this.value() ?? this.query());
    protected readonly describedBy = computed(() => this.error() !== null ? `${this.costId} ${this.errorId}` : this.costId);
    private composing = false;

    constructor() {
        afterNextRender(() => this.field().nativeElement.focus());
    }

    protected onInput(event: Event): void {
        this.value.set((event.target as HTMLInputElement).value);
    }

    protected onKeydown(event: KeyboardEvent): void {
        if (event.key === 'Enter') {
            // Never during an IME composition: that Enter confirms the candidate text. Elsewhere it sends, and nothing else may.
            if (event.isComposing || event.keyCode === 229 || this.composing) return;
            event.preventDefault();
            if (isSendKey(event)) this.send();
        }
    }

    /** Esc closes the panel, but during an IME composition it cancels the composition only. */
    protected onEscape(event: KeyboardEvent): void {
        if (event.isComposing || event.keyCode === 229 || this.composing) return;
        event.preventDefault();
        this.cancel();
    }

    protected onCompositionStart(): void { this.composing = true; }
    protected onCompositionEnd(): void { this.composing = false; }

    protected send(): void {
        if (this.pending()) return;
        this.submitted.emit(this.text().trim());
    }

    protected cancel(): void {
        if (this.pending()) return;
        this.cancelled.emit();
    }
}
