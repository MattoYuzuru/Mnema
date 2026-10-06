import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, computed, inject, input, output, signal, viewChild } from '@angular/core';

import { AudioRecorder, RecordedAudio, RecordingEnd, clockText, SPEECH_MAX_MS } from '../../shared/audio-recorder';
import { UsageApiService } from '../usage/usage-api.service';
import { speechCounterText } from '../usage/usage-view';
import { TargetSelection, TextTarget, insertTranscript, selectionOf } from './insert-transcript';
import { SpeechInputService } from './speech-input.service';
import { SpeechConsent, SpeechPurpose } from './speech-input.models';

type MicPhase = 'idle' | 'checking' | 'consent' | 'requesting' | 'recording' | 'transcribing';

/** A transcript that was put into the field: the host may remember that the text came from speech. */
export interface InsertedTranscript { readonly text: string; readonly garbled: boolean; }

/**
 * The microphone of one text field (AI-15, #298). The host shows it only where `speechToText` is available. A press asks for the consent
 * on the first use (a native modal dialog says what is recorded, where it is processed and that the audio is deleted), then records up to
 * 60 s; «Остановить запись» ends it, the recording is recognised, and the text goes into the field at the caret (or after the text, with a
 * space). It is never sent: the owner reads and edits it, then sends it as they would any text. One polite status says what is going on,
 * once at each change; the elapsed time is only drawn. Everything the microphone holds is released when this goes away.
 */
@Component({
    selector: 'app-mic-button',
    templateUrl: './mic-button.component.html',
    styleUrl: './mic-button.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
    host: { '(keydown.escape)': 'onEscape($event)' }
})
export class MicButtonComponent {
    readonly purpose = input.required<SpeechPurpose>();
    /** The field the transcript goes into, read at the moment the recording starts and when the text arrives. */
    readonly field = input.required<() => TextTarget | null>();
    readonly lang = input<string | null>(null);
    readonly deckId = input<string | null>(null);
    /** The visible name of the idle button. */
    readonly label = input('Начать запись');
    readonly disabled = input(false);
    readonly inserted = output<InsertedTranscript>();

    private readonly speech = inject(SpeechInputService);
    private readonly usageApi = inject(UsageApiService);
    private readonly destroyRef = inject(DestroyRef);
    private readonly dialog = viewChild<ElementRef<HTMLDialogElement>>('dialog');
    private readonly button = viewChild<ElementRef<HTMLButtonElement>>('button');
    private readonly recorder = new AudioRecorder({
        onFinished: (audio, reason) => this.recorded(audio, reason),
        // A recorder that breaks while recording (not while starting: `begin` reports that) puts the button back to idle with the reason.
        onError: message => { if (this.phase() === 'recording') this.fail(message); }
    });
    private abort: AbortController | null = null;
    private selection: TargetSelection | null = null;
    private pending: { readonly audio: RecordedAudio; readonly key: string } | null = null;
    private destroyed = false;

    protected readonly phase = signal<MicPhase>('idle');
    protected readonly consent = signal<SpeechConsent | null>(null);
    /** The one polite status of the field: what began, what ended. */
    protected readonly status = signal('');
    protected readonly error = signal<string | null>(null);
    protected readonly usageLine = signal<string | null>(null);
    protected readonly elapsed = computed(() => `${clockText(this.recorder.elapsedMs())} из ${clockText(SPEECH_MAX_MS)}`);
    protected readonly busy = computed(() => this.phase() === 'checking' || this.phase() === 'requesting' || this.phase() === 'transcribing');
    protected readonly region = computed(() => this.consent()?.required.processing ?? 'RU');
    protected readonly consentId = `mn-mic-consent-${Math.random().toString(36).slice(2, 8)}`;

    constructor() {
        this.destroyRef.onDestroy(() => {
            this.destroyed = true;
            this.abort?.abort();
            this.recorder.dispose();
        });
        // The fair-use counter is a quiet note: it is read once, and a failed read just leaves it out.
        this.usageApi.load().subscribe({ next: usage => this.usageLine.set(speechCounterText(usage)), error: () => this.usageLine.set(null) });
    }

    protected async press(): Promise<void> {
        if (this.disabled()) return;
        const phase = this.phase();
        if (phase === 'recording') { this.recorder.stop(); return; }
        if (phase !== 'idle') return;
        this.error.set(null);
        this.status.set('');
        this.phase.set('checking');
        const check = await this.speech.checkConsent();
        if (this.destroyed) return;
        if (!check.ok) {
            if (check.consent === null) { this.fail(check.message); return; }
            this.askConsent(check.consent);
            return;
        }
        await this.begin();
    }

    private async begin(): Promise<void> {
        const target = this.field()();
        this.selection = target === null ? null : selectionOf(target);
        this.phase.set('requesting');
        const started = await this.recorder.start();
        if (this.destroyed) return;
        if (!started) { this.fail(this.recorder.error() ?? 'Не удалось начать запись. Напишите текстом.'); return; }
        this.phase.set('recording');
        this.status.set('Запись идёт. Нажмите «Остановить запись», когда закончите.');
    }

    private recorded(audio: RecordedAudio, reason: RecordingEnd): void {
        if (this.destroyed) return;
        this.status.set(reason === 'limit' ? 'Достигнут предел записи — минута. Распознаю…' : 'Распознаю…');
        void this.recognise({ audio, key: crypto.randomUUID() });
    }

    private async recognise(job: { readonly audio: RecordedAudio; readonly key: string }): Promise<void> {
        this.pending = null;
        this.phase.set('transcribing');
        const abort = this.abort = new AbortController();
        const outcome = await this.speech.transcribe({ audio: job.audio, purpose: this.purpose(), lang: this.lang(), deckId: this.deckId(), idempotencyKey: job.key }, abort.signal);
        if (this.destroyed || abort.signal.aborted) return;
        this.abort = null;
        if (outcome.ok) { this.put(outcome.text, outcome.garbled); return; }
        if (outcome.kind === 'consent') {
            // The server wants a consent the page did not know about (it changed): keep the recording and ask, then send it again.
            this.pending = job;
            const check = await this.speech.checkConsent();
            if (this.destroyed) return;
            if (!check.ok && check.consent !== null) { this.askConsent(check.consent); return; }
            this.pending = null;
            this.fail('Не удалось получить согласие. Напишите текстом.');
            return;
        }
        if (outcome.kind === 'error') this.fail(outcome.message);
        else this.phase.set('idle');
    }

    private put(text: string, garbled: boolean): void {
        const target = this.field()();
        this.phase.set('idle');
        if (target === null) { this.fail('Поле для текста недоступно. Напишите текстом.'); return; }
        const inserted = insertTranscript(target, this.selection ?? selectionOf(target), text);
        this.selection = null;
        this.status.set('Текст добавлен в поле. Проверьте его и отправьте сами.');
        this.inserted.emit({ text: inserted, garbled });
    }

    private fail(message: string): void {
        this.phase.set('idle');
        this.error.set(message);
        this.status.set(message);
    }

    // --- Consent ---

    private askConsent(consent: SpeechConsent): void {
        this.consent.set(consent);
        this.phase.set('consent');
        const dialog = this.dialog()?.nativeElement;
        if (dialog !== undefined && !dialog.open) dialog.showModal();
    }

    protected async agree(): Promise<void> {
        const consent = this.consent();
        if (consent === null) return;
        this.closeDialog();
        this.phase.set('checking');
        const saved = await this.speech.accept(consent);
        if (this.destroyed) return;
        if (!saved.ok) { this.fail(saved.message); return; }
        const job = this.pending;
        if (job !== null) await this.recognise(job);
        else await this.begin();
    }

    protected decline(): void {
        this.pending = null;
        this.closeDialog();
        this.phase.set('idle');
        this.focusButton();
    }

    /** Esc (and the platform's own «cancel») closes the disclosure like «Не сейчас». */
    protected onDialogCancel(event: Event): void {
        event.preventDefault();
        this.decline();
    }

    /** The edit window and other hosts close on Esc: inside the dialog it belongs to the dialog. */
    protected onEscape(event: Event): void {
        if (this.phase() === 'consent') event.stopPropagation();
    }

    private closeDialog(): void {
        const dialog = this.dialog()?.nativeElement;
        if (dialog?.open) dialog.close();
    }

    protected cancel(): void {
        this.abort?.abort();
        this.abort = null;
        this.pending = null;
        this.recorder.cancel();
        this.phase.set('idle');
        this.status.set('Запись отменена.');
        this.focusButton();
    }

    private focusButton(): void {
        queueMicrotask(() => this.button()?.nativeElement.focus());
    }
}
