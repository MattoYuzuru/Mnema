import { HttpErrorResponse } from '@angular/common/http';

import { SpeechErrorCode, SpeechProtocolError } from './speech-input.models';

/** What a failed speech call tells the UI (`contracts/speech`: problem+json with a stable `code`), parsed defensively. */
export interface SpeechProblem {
    /** `0` for no answer, `-1` for an answer that could not be read. */
    readonly status: number;
    readonly code: string | null;
    readonly window: 'DAY' | 'MONTH' | null;
    readonly renewsAt: string | null;
    /** Whole seconds to wait (`429`). */
    readonly retryAfter: number | null;
    /** The outcome is unknown (no answer, a server error): the same command may be repeated. */
    readonly uncertain: boolean;
}

export function readSpeechProblem(error: unknown): SpeechProblem {
    if (error instanceof HttpErrorResponse) {
        const body = error.error !== null && typeof error.error === 'object' && !Array.isArray(error.error) ? error.error as Record<string, unknown> : {};
        const code = typeof body['code'] === 'string' && body['code'].length <= 64 ? body['code'] : null;
        const window = body['window'] === 'DAY' || body['window'] === 'MONTH' ? body['window'] : null;
        const renews = body['renewsAt'];
        const header = error.headers?.get('Retry-After') ?? null;
        const member = body['retryAfter'];
        const retryAfter = typeof member === 'number' && Number.isSafeInteger(member) && member >= 0 ? member
            : header !== null && /^[0-9]{1,6}$/u.test(header.trim()) ? Number(header.trim()) : null;
        return { status: error.status, code, window, renewsAt: typeof renews === 'string' && Number.isFinite(Date.parse(renews)) ? renews : null,
            retryAfter, uncertain: error.status === 0 || error.status >= 500 };
    }
    return { status: error instanceof SpeechProtocolError ? -1 : 0, code: null, window: null, renewsAt: null, retryAfter: null, uncertain: true };
}

const RENEWAL = new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'long' });

/** The words for a refused or failed call. Calm, and always with the way on: the text field is still there. */
export function speechProblemMessage(problem: SpeechProblem): string {
    if (problem.status === 429) {
        const minutes = problem.retryAfter === null ? null : Math.max(1, Math.ceil(problem.retryAfter / 60));
        return minutes === null ? 'Слишком много записей подряд, попробуйте чуть позже.' : `Слишком много записей подряд, попробуйте через ${minutes} мин.`;
    }
    switch (problem.code) {
        case 'CAPABILITY_UNAVAILABLE': return 'Голосовой ввод сейчас недоступен. Напишите текстом.';
        case 'USAGE_LIMIT_REACHED': {
            if (problem.window === 'DAY') return 'Голосовой ввод на сегодня исчерпан — снова доступен завтра.';
            const day = problem.renewsAt === null ? null : RENEWAL.format(new Date(problem.renewsAt));
            return day === null ? 'Голосовой ввод в этом месяце исчерпан — снова доступен в следующем.' : `Голосовой ввод в этом месяце исчерпан — снова доступен ${day}.`;
        }
        case 'IDEMPOTENCY_CONFLICT': return 'Эта запись уже отправлялась. Запишите ещё раз.';
        case 'PAYLOAD_TOO_LARGE': return 'Запись слишком большая — запишите покороче.';
        case 'INVALID_REQUEST': return 'Запись не удалось отправить. Попробуйте ещё раз или напишите текстом.';
        default: break;
    }
    if (problem.status === 413) return 'Запись слишком большая — запишите покороче.';
    if (problem.status === 0 || problem.status === -1) return 'Нет связи. Попробуйте ещё раз или напишите текстом.';
    return 'Не удалось распознать речь — напишите текстом.';
}

/** The words for a transcript that ended `FAILED`. */
export function speechFailureMessage(code: SpeechErrorCode | null): string {
    switch (code) {
        case 'NO_SPEECH': return 'Не расслышала речь — попробуйте ещё раз.';
        case 'TOO_LONG': return 'Запись длиннее минуты — запишите покороче.';
        case 'UNSUPPORTED_AUDIO': return 'Не удалось разобрать запись — попробуйте ещё раз или напишите текстом.';
        default: return 'Не удалось распознать речь — напишите текстом.';
    }
}
