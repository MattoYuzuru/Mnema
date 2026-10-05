import {
    ChangeDetectionStrategy, Component, ElementRef, afterNextRender, afterRenderEffect, computed, input, model, output, signal, untracked,
    viewChild
} from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';

import { MicButtonComponent } from '../speech/mic-button.component';
import { EDIT_PRESET_OPTIONS } from './generation-view';
import { EditPreset, MAX_INSTRUCTION_LENGTH } from './generation.models';
import { blockImplicitSubmit, isSendKey } from './implicit-submit';
import { AnchorRect, placeNear, viewport } from './place-near';

/** What the user asked for in the window: a quick preset, a sentence, or both. */
export interface AiPromptAsk {
    readonly preset: EditPreset | null;
    readonly instruction: string | null;
}

const WINDOW_WIDTH = 416;
const WINDOW_HEIGHT_GUESS = 400;

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
 * The microphone (AI-15, #298) sits under the field once `speechToText.available`; its transcript is put into the field and never sent.
 */
@Component({
    selector: 'app-ai-prompt-window',
    imports: [NgTemplateOutlet, MicButtonComponent],
    templateUrl: './ai-prompt-window.component.html',
    styleUrl: './ai-prompt-window.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class AiPromptWindowComponent {
    /** `speechToText` is available: the window offers the microphone next to its field. */
    readonly speechAvailable = input(false);
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
    /** The budget does not fit this edit: the button stays pressable (an empty field too) and the press explains, as the composer's does. */
    readonly limited = input(false);
    /** Where the selection ends (popover only). */
    readonly anchor = input<AnchorRect | null>(null);

    readonly ask = output<AiPromptAsk>();
    /** The user took back a request that has not been answered. */
    readonly retract = output<void>();
    /** The user closed the window; `true` when focus should go back to the document (Esc, «×»), `false` after a click outside. */
    readonly dismissed = output<boolean>();

    private readonly field = viewChild<ElementRef<HTMLTextAreaElement>>('field');
    protected readonly fieldTarget = (): HTMLTextAreaElement | null => this.field()?.nativeElement ?? null;
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
    /** The height the content really takes (known after the first render and whenever it grows): the window is placed with it. */
    private readonly measured = signal<number | null>(null);
    private readonly composing = signal(false);
    protected readonly placement = computed(() => placeNear(this.anchor(), { width: WINDOW_WIDTH, height: this.measured() ?? WINDOW_HEIGHT_GUESS }, viewport()));
    protected readonly describedBy = computed(() => [this.cost() !== null ? this.costId : null, this.error() !== null ? this.errorId : null]
        .filter((id): id is string => id !== null).join(' ') || null);

    constructor() {
        afterNextRender(() => this.show());
        // A message or a cost line makes the window taller: measure it again, so it is placed on the side where it fits.
        afterRenderEffect(() => {
            this.error(); this.cost(); this.sending(); this.anchor();
            const surface = this.surface()?.nativeElement;
            if (surface === undefined || this.mode() !== 'popover' || surface.scrollHeight === 0) return;
            const height = Math.ceil(surface.scrollHeight) + 2;
            untracked(() => { if (Math.abs(height - (this.measured() ?? 0)) > 1) this.measured.set(height); });
        });
    }

    protected onCompositionStart(): void { this.composing.set(true); }
    protected onCompositionEnd(): void { this.composing.set(false); }

    /** Esc during an IME composition cancels the composition, not the window. */
    protected onEscape(event: KeyboardEvent): void {
        if (event.isComposing || event.keyCode === 229 || this.composing()) return;
        this.dismiss(true);
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
        if (this.sending() || (this.empty() && !this.limited())) return;
        this.ask.emit({ preset: null, instruction: this.empty() ? null : this.instruction().trim() });
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
        if (!this.composing()) this.dismiss(true);
    }

    /**
     * A click on the dialog element itself is a click on the backdrop (the padding belongs to the inner wrapper, so it never is one).
     * It closes the sheet as Esc does: the typed request stays for the same selection and focus goes back to the document.
     */
    protected onSheetClick(event: MouseEvent): void {
        if (event.target === this.surface()?.nativeElement) this.dismiss(true);
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
}
