import { ChangeDetectionStrategy, Component, ElementRef, afterNextRender, computed, input, output, signal, viewChild } from '@angular/core';

import { AUDIO_REDO_HINT, AUDIO_RUNNING } from './generation-view';
import { SPEECH_VOICES, SpeechVoice } from './generation.models';

let nextPanel = 0;

/**
 * The inline panel under an audio: «Женский голос / Мужской голос», a hint about what a redo costs, «Озвучить», «Отмена» (AI-09, #297).
 * Like the image search panel it is a group in the flow of the document, not a dialog: focus goes to the checked voice, Esc and «Отмена»
 * close it, and while the clip is synthesised the controls are `aria-disabled` (never `disabled`: focus stays) and a status says so.
 * The voice is a native radio pair, so the arrow keys move between the two. It answers with `null` when the voice is the one the clip
 * has (the request then omits it: the same voice is a new take) and with the voice when it is another one.
 */
@Component({
    selector: 'app-audio-redo-panel',
    templateUrl: './audio-redo-panel.component.html',
    styleUrl: './audio-redo-panel.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class AudioRedoPanelComponent {
    /** The voice of the clip now: preselected. */
    readonly voice = input<SpeechVoice>('female');
    /** A redo is being sent or is running. */
    readonly pending = input(false);
    readonly error = input<string | null>(null);
    readonly submitted = output<SpeechVoice | null>();
    readonly cancelled = output<void>();

    private readonly group = viewChild.required<ElementRef<HTMLElement>>('group');
    private readonly uid = `mn-audio-redo-${nextPanel++}`;
    protected readonly name = `${this.uid}-voice`;
    protected readonly hintId = `${this.uid}-hint`;
    protected readonly errorId = `${this.uid}-error`;
    protected readonly options = SPEECH_VOICES.map(value => ({ value, label: value === 'female' ? 'Женский голос' : 'Мужской голос' }));
    protected readonly hint = AUDIO_REDO_HINT;
    protected readonly running = AUDIO_RUNNING;
    protected readonly picked = signal<SpeechVoice | null>(null);
    protected readonly chosen = computed(() => this.picked() ?? this.voice());
    protected readonly describedBy = computed(() => this.error() !== null ? `${this.hintId} ${this.errorId}` : this.hintId);

    constructor() {
        afterNextRender(() => this.group().nativeElement.querySelector<HTMLInputElement>('input:checked')?.focus());
    }

    protected pick(event: Event, voice: SpeechVoice): void {
        if (this.pending()) { event.preventDefault(); return; }
        this.picked.set(voice);
    }

    protected send(): void {
        if (this.pending()) return;
        this.submitted.emit(this.chosen() === this.voice() ? null : this.chosen());
    }

    protected cancel(): void {
        if (this.pending()) return;
        this.cancelled.emit();
    }

    /** Esc closes the panel, but during an IME composition it cancels the composition only. */
    protected onEscape(event: KeyboardEvent): void {
        if (event.isComposing || event.keyCode === 229) return;
        event.preventDefault();
        this.cancel();
    }
}
