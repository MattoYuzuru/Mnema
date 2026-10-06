import { signal } from '@angular/core';

/** Voice input prefers the formats the server takes as they are (`contracts/speech`): MP4 first (Safari), then Ogg and WebM with Opus. */
export const SPEECH_MIME_PREFERENCE: readonly string[] = ['audio/mp4', 'audio/ogg;codecs=opus', 'audio/webm;codecs=opus'];
/** A recording stops by itself here (the server's own limit is 60 s). */
export const SPEECH_MAX_MS = 60_000;

export const MICROPHONE_DENIED = 'Нет доступа к микрофону. Разрешите его в настройках браузера.';

export interface RecordedAudio {
    readonly blob: Blob;
    /** The MIME type the recorder really used: it is sent as the `Content-Type`. */
    readonly mimeType: string;
    /** Measured by the recorder, 1 to {@link SPEECH_MAX_MS}. */
    readonly durationMs: number;
}

/** Why a recording ended: the user stopped it, the time limit did, or the microphone went away (what was recorded is kept). */
export type RecordingEnd = 'stopped' | 'limit' | 'device';
export type RecorderState = 'idle' | 'requesting' | 'recording';

/** The calm sentences of the recorder; a host with another way on (a file instead of text) replaces them. */
export interface RecorderMessages {
    readonly unavailable: string;
    readonly format: string;
    readonly interrupted: string;
    readonly empty: string;
    readonly startFailed: string;
    readonly denied: string;
    readonly notFound: string;
    readonly busy: string;
    readonly other: string;
}

const DEFAULT_MESSAGES: RecorderMessages = {
    unavailable: 'Запись здесь недоступна. Напишите текстом.',
    format: 'Браузер не поддерживает подходящий формат записи. Напишите текстом.',
    interrupted: 'Запись прервалась. Попробуйте ещё раз.',
    empty: 'Запись получилась пустой. Проверьте микрофон и попробуйте ещё раз.',
    startFailed: 'Не удалось начать запись. Напишите текстом.',
    denied: MICROPHONE_DENIED,
    notFound: 'Микрофон не найден. Подключите его или напишите текстом.',
    busy: 'Микрофон занят другим приложением. Закройте его и попробуйте ещё раз.',
    other: MICROPHONE_DENIED
};

export interface AudioRecorderOptions {
    readonly maxMs?: number;
    readonly mimePreference?: readonly string[];
    readonly messages?: Partial<RecorderMessages>;
    /** Called with the sentence whenever the recording could not start or was cut (it is also in {@link AudioRecorder.error}). */
    readonly onError?: (message: string) => void;
    /** The recording ended and produced audio. Never called after {@link AudioRecorder.cancel}. */
    readonly onFinished: (audio: RecordedAudio, reason: RecordingEnd) => void;
}

/**
 * One short microphone recording, shared by the voice input of the composer, the edit window, the capture field and the Study answer.
 * The microphone is asked for only by {@link start} (after an explicit press), the device tracks are released when the recording ends,
 * is cancelled or the owner is destroyed, and the recording stops by itself at the limit. The elapsed time is a signal that moves once a
 * second: a host shows it, and announces only the start and the end. No data leaves the recorder except through `onFinished`.
 */
export class AudioRecorder {
    readonly state = signal<RecorderState>('idle');
    /** Whole seconds are what is shown: the signal moves once a second while recording. */
    readonly elapsedMs = signal(0);
    /** A calm sentence when the recording could not start or was cut; `null` otherwise. */
    readonly error = signal<string | null>(null);

    private readonly maxMs: number;
    private readonly mimePreference: readonly string[];
    private readonly onFinished: AudioRecorderOptions['onFinished'];
    private readonly messages: RecorderMessages;
    private readonly onError: AudioRecorderOptions['onError'];
    private recorder: MediaRecorder | null = null;
    private stream: MediaStream | null = null;
    private chunks: Blob[] = [];
    private ticker: ReturnType<typeof setInterval> | null = null;
    private limit: ReturnType<typeof setTimeout> | null = null;
    private startedAt = 0;
    private token = 0;
    private reason: RecordingEnd = 'stopped';

    constructor(options: AudioRecorderOptions) {
        this.maxMs = options.maxMs ?? SPEECH_MAX_MS;
        this.mimePreference = options.mimePreference ?? SPEECH_MIME_PREFERENCE;
        this.onFinished = options.onFinished;
        this.onError = options.onError;
        this.messages = { ...DEFAULT_MESSAGES, ...options.messages };
    }

    /** Asks for the microphone and starts. Resolves `true` when recording, `false` (with {@link error}) when it could not. */
    async start(): Promise<boolean> {
        if (this.state() !== 'idle') return false;
        this.error.set(null);
        if (!navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === 'undefined') {
            this.fail(this.messages.unavailable);
            return false;
        }
        const mime = this.mimePreference.find(candidate => MediaRecorder.isTypeSupported(candidate));
        if (mime === undefined) {
            this.fail(this.messages.format);
            return false;
        }
        const token = ++this.token;
        this.state.set('requesting');
        let stream: MediaStream;
        try {
            stream = await navigator.mediaDevices.getUserMedia({ audio: true });
        } catch (error) {
            if (token === this.token) { this.state.set('idle'); this.fail(microphoneError(error, this.messages)); }
            return false;
        }
        // Cancelled, or the owner went away, while the permission prompt was open: release the device and record nothing.
        if (token !== this.token) { stream.getTracks().forEach(track => track.stop()); return false; }
        try {
            this.stream = stream;
            this.chunks = [];
            this.reason = 'stopped';
            const recorder = new MediaRecorder(stream, { mimeType: mime });
            this.recorder = recorder;
            recorder.ondataavailable = event => { if (event.data.size > 0) this.chunks.push(event.data); };
            recorder.onerror = () => { this.fail(this.messages.interrupted); this.cancel(); };
            recorder.onstop = () => this.finish(recorder, token);
            // A device that disappears mid-recording ends its track: keep what was recorded so far.
            stream.getAudioTracks().forEach(track => { track.onended = () => { this.reason = 'device'; this.stop(); }; });
            recorder.start();
            this.startedAt = Date.now();
            this.elapsedMs.set(0);
            this.state.set('recording');
            this.ticker = setInterval(() => this.elapsedMs.set(Math.min(Date.now() - this.startedAt, this.maxMs)), 1000);
            this.limit = setTimeout(() => { this.reason = 'limit'; this.stop(); }, this.maxMs);
            return true;
        } catch {
            this.release();
            this.fail(this.messages.startFailed);
            return false;
        }
    }

    /** Ends the recording; `onFinished` receives the audio. */
    stop(): void {
        if (this.recorder?.state === 'recording') this.recorder.stop();
    }

    /** Drops a pending permission request or a running recording; nothing is produced. */
    cancel(): void {
        this.token++;
        if (this.recorder !== null) {
            this.recorder.onstop = null;
            if (this.recorder.state === 'recording') this.recorder.stop();
        }
        this.chunks = [];
        this.release();
    }

    /** The owner is going away: the microphone is released. */
    dispose(): void {
        this.cancel();
    }

    private finish(recorder: MediaRecorder, token: number): void {
        const measured = Math.min(Math.max(Date.now() - this.startedAt, 1), this.maxMs);
        const chunks = this.chunks;
        const reason = this.reason;
        this.release();
        if (token !== this.token) return;
        const blob = new Blob(chunks, { type: recorder.mimeType });
        if (blob.size === 0) { this.fail(this.messages.empty); return; }
        this.onFinished({ blob, mimeType: recorder.mimeType, durationMs: measured }, reason);
    }

    private fail(message: string): void {
        this.error.set(message);
        this.onError?.(message);
    }

    /** Releases everything the recorder holds: timers, the recorder and the device tracks. */
    private release(): void {
        if (this.ticker !== null) clearInterval(this.ticker);
        if (this.limit !== null) clearTimeout(this.limit);
        this.ticker = null;
        this.limit = null;
        this.stream?.getTracks().forEach(track => track.stop());
        this.stream = null;
        this.recorder = null;
        this.state.set('idle');
    }
}

function microphoneError(error: unknown, messages: RecorderMessages): string {
    const name = typeof error === 'object' && error !== null && 'name' in error ? String((error as { name: unknown }).name) : '';
    switch (name) {
        case 'NotAllowedError':
        case 'SecurityError': return messages.denied;
        case 'NotFoundError':
        case 'DevicesNotFoundError':
        case 'OverconstrainedError': return messages.notFound;
        case 'NotReadableError':
        case 'AbortError': return messages.busy;
        default: return messages.other;
    }
}

/** «0:07»: elapsed time for display. */
export function clockText(milliseconds: number): string {
    const seconds = Math.floor(milliseconds / 1000);
    return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
}
