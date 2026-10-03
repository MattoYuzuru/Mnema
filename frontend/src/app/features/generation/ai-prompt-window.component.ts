import {
    ChangeDetectionStrategy, Component, ElementRef, afterNextRender, computed, inject, input, model, output, viewChild
} from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';

import { EDIT_PRESET_OPTIONS } from './generation-view';
import { EditPreset, MAX_INSTRUCTION_LENGTH } from './generation.models';
import { blockImplicitSubmit, isSendKey } from './implicit-submit';

/** What the user asked for in the window: a quick preset, a sentence, or both. */
export interface AiPromptAsk {
    readonly preset: EditPreset | null;
    readonly instruction: string | null;
}

/** Where the selection ends on screen (viewport pixels); the floating window sits under it. */
export interface AnchorRect { readonly top: number; readonly bottom: number; readonly left: number; readonly right: number; }

const WINDOW_WIDTH = 416;
const WINDOW_HEIGHT_GUESS = 400;
const EDGE = 8;

let nextWindow = 0;

/**
 * «Попросить Мнему»: the small window that asks what to change in the chosen blocks. Four presets (each sends at once), a field (Enter
 * sends, Shift+Enter is a new line, never while an IME composes), the cost line and the send button. It is shown for as long as it
 * exists: the host renders it with `@if` and takes it away to close it.
 *
 * Two shapes with one content. `popover`: a non-modal `role="dialog"` in the top layer (`popover="manual"`), placed under the end of the
 * selection; focus goes to the field. `sheet`: for a coarse pointer, a modal `<dialog>` at the bottom of the screen (`showModal()`),
 * so the system selection menu is not in its way; focus goes to its title so the keyboard does not cover the presets, and the field
 * has `enterkeyhint="send"`. Esc and «×» close both; the sheet also closes on its backdrop. `closedby` is not used: Safari lacks it.
 * The microphone belongs here once `speechToText.available`; it is not offered while there is no recorder behind it.
 */
@Component({
    selector: 'app-ai-prompt-window',
    imports: [NgTemplateOutlet],
    templateUrl: './ai-prompt-window.component.html',
    styleUrl: './ai-prompt-window.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class AiPromptWindowComponent {
    readonly mode = input.required<'popover' | 'sheet'>();
    /** What is selected, on one line. */
    readonly quote = input('');
    /** The cost line («≈ 0,3 % лимита»); `null` says nothing. */
    readonly cost = input<string | null>(null);
    /** The request being typed; the host keeps it until the selection changes. */
    readonly instruction = model('');
    /** The request is on its way: the field is read-only, the buttons wait, and «Отменить» takes it back. */
    readonly sending = input(false);
    /** Why the last request was refused, in words. */
    readonly error = input<string | null>(null);
    /** Where the selection ends (popover only). */
    readonly anchor = input<AnchorRect | null>(null);

    readonly ask = output<AiPromptAsk>();
    /** The user took back a request that has not been answered. */
    readonly retract = output<void>();
    /** The user closed the window; `true` when focus should go back to the document (Esc, «×»), `false` after a click outside. */
    readonly dismissed = output<boolean>();

    private readonly field = viewChild<ElementRef<HTMLTextAreaElement>>('field');
    private readonly surface = viewChild<ElementRef<HTMLElement>>('surface');
    private readonly title = viewChild<ElementRef<HTMLElement>>('title');

    private readonly uid = `mn-ai-window-${nextWindow++}`;
    protected readonly titleId = `${this.uid}-title`;
    protected readonly fieldId = `${this.uid}-field`;
    protected readonly costId = `${this.uid}-cost`;
    protected readonly errorId = `${this.uid}-error`;
    protected readonly quoteId = `${this.uid}-quote`;
    protected readonly presets = EDIT_PRESET_OPTIONS;
    protected readonly maxLength = MAX_INSTRUCTION_LENGTH;
    protected readonly blockImplicitSubmit = blockImplicitSubmit;
    protected readonly empty = computed(() => this.instruction().trim().length === 0);
    protected readonly placement = computed(() => this.place(this.anchor()));
    protected readonly describedBy = computed(() => [this.cost() !== null ? this.costId : null, this.error() !== null ? this.errorId : null]
        .filter((id): id is string => id !== null).join(' ') || null);

    constructor() {
        afterNextRender(() => this.show());
    }

    protected onKeydown(event: KeyboardEvent): void {
        if (!isSendKey(event)) return;
        event.preventDefault();
        this.sendText();
    }

    protected onSubmit(event: Event): void {
        event.preventDefault();
        this.sendText();
    }

    protected onInput(event: Event): void {
        this.instruction.set((event.target as HTMLTextAreaElement).value);
    }

    protected sendText(): void {
        if (this.sending() || this.empty()) return;
        this.ask.emit({ preset: null, instruction: this.instruction().trim() });
    }

    protected sendPreset(preset: EditPreset): void {
        if (this.sending()) return;
        const text = this.instruction().trim();
        this.ask.emit({ preset, instruction: text.length === 0 ? null : text });
    }

    protected dismiss(restoreFocus: boolean): void {
        this.dismissed.emit(restoreFocus);
    }

    /** Esc in a modal dialog closes it natively; the host takes the window away instead, so the dialog never closes behind its back. */
    protected onCancel(event: Event): void {
        event.preventDefault();
        this.dismiss(true);
    }

    /** A click on the dialog itself (not on its content) is a click on the backdrop. */
    protected onSheetClick(event: MouseEvent): void {
        if (event.target === this.surface()?.nativeElement) this.dismiss(false);
    }

    private show(): void {
        const surface = this.surface()?.nativeElement;
        if (surface === undefined) return;
        if (this.mode() === 'sheet') {
            const dialog = surface as HTMLDialogElement;
            if (!dialog.open) dialog.showModal();
            this.title()?.nativeElement.focus();
        } else {
            if (typeof surface.showPopover === 'function') {
                try { surface.showPopover(); } catch { /* already open */ }
            }
            this.field()?.nativeElement.focus();
        }
    }

    /** Under the end of the selection, inside the viewport; above it when there is no room below. */
    private place(anchor: AnchorRect | null): { top: string; bottom: string; left: string; width: string } {
        const view = { width: typeof window === 'undefined' ? 1024 : window.innerWidth, height: typeof window === 'undefined' ? 768 : window.innerHeight };
        const width = Math.min(WINDOW_WIDTH, view.width - 2 * EDGE);
        if (anchor === null) return { top: '20vh', bottom: 'auto', left: `${Math.max(EDGE, (view.width - width) / 2)}px`, width: `${width}px` };
        const left = Math.min(Math.max(anchor.left, EDGE), view.width - width - EDGE);
        const below = view.height - anchor.bottom;
        if (below >= WINDOW_HEIGHT_GUESS) {
            return { top: `${Math.max(EDGE, anchor.bottom + EDGE)}px`, bottom: 'auto', left: `${left}px`, width: `${width}px` };
        }
        if (anchor.top >= WINDOW_HEIGHT_GUESS) {
            return { top: 'auto', bottom: `${Math.max(EDGE, view.height - anchor.top + EDGE)}px`, left: `${left}px`, width: `${width}px` };
        }
        // No room on either side (a short window): the bottom of the screen, over the text, never off the screen.
        return { top: 'auto', bottom: `${EDGE}px`, left: `${left}px`, width: `${width}px` };
    }
}
