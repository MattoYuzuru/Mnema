import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { RecordedAudio } from '../../shared/audio-recorder';
import { SpeechInputApiService } from './speech-input.api';
import { SPEECH_DISCLOSURE_VERSION, SpeechConsent, SpeechPurpose, consentCovers } from './speech-input.models';
import { readSpeechProblem, speechFailureMessage, speechProblemMessage } from './speech-problem';

export interface TranscribeRequest {
    readonly audio: RecordedAudio;
    readonly purpose: SpeechPurpose;
    readonly lang?: string | null;
    readonly deckId?: string | null;
    /** The key of this recording: repeating the request after a consent keeps it, so the server never transcribes twice. */
    readonly idempotencyKey: string;
}

export type TranscribeOutcome =
    | { readonly ok: true; readonly text: string; readonly garbled: boolean }
    /** The server wants a consent that is not on record (or is outdated): show the disclosure, then repeat with the same request. */
    | { readonly ok: false; readonly kind: 'consent' }
    | { readonly ok: false; readonly kind: 'cancelled' }
    | { readonly ok: false; readonly kind: 'error'; readonly message: string };

export type ConsentCheck = { readonly ok: true } | { readonly ok: false; readonly consent: SpeechConsent } | { readonly ok: false; readonly consent: null; readonly message: string };

/** The server's deadline per input is 30 s; the client gives up a little after it. */
export const TRANSCRIBE_GIVE_UP_MS = 45_000;
const POLL_FAILURES = 4;
const POLL_FAILURE_MAX_MS = 4_000;
const MIN_POLL_MS = 250;
const MAX_POLL_MS = 1_500;

/**
 * Voice input end to end: the consent check, the upload of one recording and the wait for its transcript. Polling is every
 * `pollAfterMs` while the tab is visible (it pauses while hidden and resumes when the tab returns), backs off on a failing poll, stops at
 * a terminal state, and `signal` takes it back (the server's input is deleted then). Every failure is turned into a calm sentence here;
 * the recording itself never leaves this call.
 */
@Injectable({ providedIn: 'root' })
export class SpeechInputService {
    private readonly api = inject(SpeechInputApiService);

    /** Whether the consent on record covers the active route; otherwise the terms to show. */
    async checkConsent(): Promise<ConsentCheck> {
        try {
            const consent = await firstValueFrom(this.api.getConsent());
            return consentCovers(consent) ? { ok: true } : { ok: false, consent };
        } catch (error) {
            return { ok: false, consent: null, message: speechProblemMessage(readSpeechProblem(error)) };
        }
    }

    /**
     * Records the consent for the region of `consent.required` under the version of the disclosure the person was shown
     * ({@link SPEECH_DISCLOSURE_VERSION}). If the server asks for another version (`409 SPEECH_CONSENT_OUTDATED`) the text on screen is not the one
     * the server needs, so nothing is recorded behind the person's back: they are asked to start again and read the current terms.
     */
    async accept(consent: SpeechConsent): Promise<{ readonly ok: true } | { readonly ok: false; readonly message: string }> {
        try {
            await firstValueFrom(this.api.putConsent({ version: SPEECH_DISCLOSURE_VERSION, processing: consent.required.processing }));
            return { ok: true };
        } catch (error) {
            const problem = readSpeechProblem(error);
            if (problem.code === 'SPEECH_CONSENT_OUTDATED') return { ok: false, message: 'Условия согласия обновились. Обновите страницу и нажмите на микрофон ещё раз.' };
            return { ok: false, message: speechProblemMessage(problem) };
        }
    }

    async transcribe(request: TranscribeRequest, signal: AbortSignal): Promise<TranscribeOutcome> {
        let created;
        try {
            created = await firstValueFrom(this.api.create(request.audio.blob, request.audio.mimeType, {
                purpose: request.purpose, lang: request.lang, deckId: request.deckId, durationMs: request.audio.durationMs, idempotencyKey: request.idempotencyKey }));
        } catch (error) {
            const problem = readSpeechProblem(error);
            if (problem.code === 'SPEECH_CONSENT_REQUIRED') return { ok: false, kind: 'consent' };
            return { ok: false, kind: 'error', message: speechProblemMessage(problem) };
        }
        if (signal.aborted) { this.discard(created.speechInputId); return { ok: false, kind: 'cancelled' }; }
        const deadline = Date.now() + TRANSCRIBE_GIVE_UP_MS;
        let wait = clamp(created.pollAfterMs);
        let failures = 0;
        for (;;) {
            if (!(await this.pause(wait, signal))) { this.discard(created.speechInputId); return { ok: false, kind: 'cancelled' }; }
            if (Date.now() > deadline) { this.discard(created.speechInputId); return { ok: false, kind: 'error', message: speechFailureMessage('UNAVAILABLE') }; }
            try {
                const view = await firstValueFrom(this.api.read(created.speechInputId));
                failures = 0;
                if (view.state === 'DONE') {
                    const text = (view.text ?? '').trim();
                    return text.length === 0 ? { ok: false, kind: 'error', message: speechFailureMessage('NO_SPEECH') } : { ok: true, text, garbled: view.garbled };
                }
                if (view.state === 'FAILED') return { ok: false, kind: 'error', message: speechFailureMessage(view.errorCode) };
                wait = clamp(created.pollAfterMs);
            } catch (error) {
                const problem = readSpeechProblem(error);
                // A 404 is an input that expired or is not ours; a definitive answer other than a server fault is not worth another try.
                if (!problem.uncertain || ++failures > POLL_FAILURES) return { ok: false, kind: 'error', message: speechProblemMessage(problem) };
                wait = Math.min(clamp(created.pollAfterMs) * 2 ** failures, POLL_FAILURE_MAX_MS);
            }
        }
    }

    /** Takes an input back (best effort: the audio is deleted by the server when the work ends anyway). */
    private discard(speechInputId: string): void {
        this.api.remove(speechInputId).subscribe({ error: () => { /* it expires by itself */ } });
    }

    /** Waits `ms`, and while the tab is hidden waits for it to be visible again. `false` when taken back. */
    private pause(ms: number, signal: AbortSignal): Promise<boolean> {
        return new Promise<boolean>(resolve => {
            let timer: ReturnType<typeof setTimeout> | null = null;
            const finish = (value: boolean): void => {
                if (timer !== null) clearTimeout(timer);
                signal.removeEventListener('abort', onAbort);
                document.removeEventListener('visibilitychange', onVisibility);
                resolve(value);
            };
            const onAbort = (): void => finish(false);
            const arm = (): void => {
                if (timer !== null) clearTimeout(timer);
                timer = document.visibilityState === 'hidden' ? null : setTimeout(() => finish(true), ms);
            };
            const onVisibility = (): void => arm();
            if (signal.aborted) { resolve(false); return; }
            signal.addEventListener('abort', onAbort, { once: true });
            document.addEventListener('visibilitychange', onVisibility);
            arm();
        });
    }
}

function clamp(pollAfterMs: number): number {
    return Math.min(Math.max(pollAfterMs, MIN_POLL_MS), MAX_POLL_MS);
}
