/** Wire and view models of the speech input contract (`contracts/speech`, `speech-v1`). Answers are parsed strictly; a stranger is a protocol error. */

export class SpeechProtocolError extends Error {}

export const SPEECH_PURPOSES = ['COMPOSER', 'EDIT', 'CAPTURE', 'STUDY_ANSWER'] as const;
export type SpeechPurpose = (typeof SPEECH_PURPOSES)[number];
export const PROCESSING_REGIONS = ['RU', 'ABROAD'] as const;
export type ProcessingRegion = (typeof PROCESSING_REGIONS)[number];
export const SPEECH_STATES = ['QUEUED', 'TRANSCRIBING', 'DONE', 'FAILED'] as const;
export type SpeechState = (typeof SPEECH_STATES)[number];
export const SPEECH_ERROR_CODES = ['UNAVAILABLE', 'NO_SPEECH', 'UNSUPPORTED_AUDIO', 'TOO_LONG'] as const;
export type SpeechErrorCode = (typeof SPEECH_ERROR_CODES)[number];

/** The consent a processing region needs: the version changes when the active route's region changes, so a move abroad asks again. */
export interface SpeechConsentTerms { readonly version: string | number; readonly processing: ProcessingRegion; }
export interface SpeechConsent {
    readonly required: SpeechConsentTerms;
    readonly accepted: (SpeechConsentTerms & { readonly acceptedAt: string }) | null;
}

export interface SpeechInputCreated { readonly speechInputId: string; readonly state: SpeechState; readonly pollAfterMs: number; readonly expiresAt: string; }
export interface SpeechInputView {
    readonly speechInputId: string;
    readonly state: SpeechState;
    readonly text: string | null;
    readonly seconds: number | null;
    readonly lang: string | null;
    readonly garbled: boolean;
    readonly errorCode: SpeechErrorCode | null;
    readonly expiresAt: string;
}

function object(value: unknown, keys: readonly string[]): Record<string, unknown> {
    if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new SpeechProtocolError('Expected an object.');
    const record = value as Record<string, unknown>;
    const extra = Object.keys(record).filter(key => !keys.includes(key));
    if (extra.length > 0) throw new SpeechProtocolError('Unexpected member.');
    return record;
}

function oneOf<T extends string>(value: unknown, allowed: readonly T[]): T {
    if (typeof value !== 'string' || !(allowed as readonly string[]).includes(value)) throw new SpeechProtocolError('Unexpected value.');
    return value as T;
}

function id(value: unknown): string {
    if (typeof value !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu.test(value)) throw new SpeechProtocolError('Invalid id.');
    return value.toLowerCase();
}

function instant(value: unknown): string {
    if (typeof value !== 'string' || !Number.isFinite(Date.parse(value))) throw new SpeechProtocolError('Invalid instant.');
    return value;
}

function terms(value: unknown, extra: readonly string[] = []): SpeechConsentTerms {
    const record = object(value, ['version', 'processing', ...extra]);
    const version = record['version'];
    if ((typeof version !== 'string' || version.length === 0 || version.length > 64) && (typeof version !== 'number' || !Number.isSafeInteger(version))) {
        throw new SpeechProtocolError('Invalid consent version.');
    }
    return { version, processing: oneOf(record['processing'], PROCESSING_REGIONS) };
}

export function parseConsent(value: unknown): SpeechConsent {
    const record = object(value, ['required', 'accepted']);
    const accepted = record['accepted'];
    return {
        required: terms(record['required']),
        accepted: accepted === null ? null : { ...terms(accepted, ['acceptedAt']), acceptedAt: instant((accepted as Record<string, unknown>)['acceptedAt']) }
    };
}

/** Whether the consent on record covers the terms the active route needs now (same version and region). */
export function consentCovers(consent: SpeechConsent): boolean {
    const held = consent.accepted;
    return held !== null && held.version === consent.required.version && held.processing === consent.required.processing;
}

export function parseCreated(value: unknown): SpeechInputCreated {
    const record = object(value, ['speechInputId', 'state', 'pollAfterMs', 'expiresAt']);
    const poll = record['pollAfterMs'];
    if (typeof poll !== 'number' || !Number.isFinite(poll) || poll < 0) throw new SpeechProtocolError('Invalid poll interval.');
    return { speechInputId: id(record['speechInputId']), state: oneOf(record['state'], SPEECH_STATES), pollAfterMs: poll, expiresAt: instant(record['expiresAt']) };
}

export function parseInput(value: unknown): SpeechInputView {
    const record = object(value, ['speechInputId', 'state', 'text', 'seconds', 'lang', 'garbled', 'errorCode', 'expiresAt']);
    const text = record['text'];
    const seconds = record['seconds'];
    const lang = record['lang'];
    const code = record['errorCode'];
    if (text !== null && typeof text !== 'string') throw new SpeechProtocolError('Invalid text.');
    if (seconds !== null && (typeof seconds !== 'number' || !Number.isFinite(seconds) || seconds < 0)) throw new SpeechProtocolError('Invalid seconds.');
    if (lang !== null && typeof lang !== 'string') throw new SpeechProtocolError('Invalid language.');
    if (typeof record['garbled'] !== 'boolean') throw new SpeechProtocolError('Invalid flag.');
    return { speechInputId: id(record['speechInputId']), state: oneOf(record['state'], SPEECH_STATES), text, seconds, lang, garbled: record['garbled'],
        errorCode: code === null ? null : oneOf(code, SPEECH_ERROR_CODES), expiresAt: instant(record['expiresAt']) };
}
