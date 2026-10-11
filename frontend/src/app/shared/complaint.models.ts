import { HttpErrorResponse } from '@angular/common/http';

/**
 * Wire vocabulary of filing a complaint about a public deck or profile (`contracts/moderation/complaints.json`, Share/15, #437).
 * The reporter sends a closed reason and an optional text; the only answer is `202 {status: "RECEIVED"}`. Nothing is ever said
 * about the decision.
 */
export const COMPLAINT_REASONS = ['COPYRIGHT', 'PERSONAL_DATA', 'ILLEGAL', 'SPAM', 'OTHER'] as const;
export type ComplaintReason = (typeof COMPLAINT_REASONS)[number];
/** The optional text is at most this many characters (Unicode code points), after trimming. */
export const COMPLAINT_TEXT_MAX = 1000;
/** Ten characters of base58, the public code of a deck (the same alphabet as the public view). */
export const COMPLAINT_DECK_CODE = /^[1-9A-HJ-NP-Za-km-z]{10}$/u;
/** The Identity profile username. */
export const COMPLAINT_USERNAME = /^[A-Za-z0-9_.-]{3,50}$/u;

export const COMPLAINT_REASON_LABELS: Readonly<Record<ComplaintReason, string>> = {
    COPYRIGHT: 'Нарушены авторские права',
    PERSONAL_DATA: 'Раскрыты личные данные',
    ILLEGAL: 'Запрещённое содержимое',
    SPAM: 'Спам или реклама',
    OTHER: 'Другое'
};

/** One short line under the group while a reason is chosen (the live explanation of `app-segmented-choice`). */
export const COMPLAINT_REASON_HINTS: Readonly<Record<ComplaintReason, string>> = {
    COPYRIGHT: 'Текст, картинка или звук использованы без разрешения правообладателя.',
    PERSONAL_DATA: 'В материалах есть чьи-то личные данные, которыми делиться нельзя.',
    ILLEGAL: 'Содержимое нарушает закон.',
    SPAM: 'Колода нужна для рекламы или рассылки, а не для учёбы.',
    OTHER: 'Опишите причину своими словами ниже.'
};

/** What went wrong with filing, in the words the dialog needs. */
export type ComplaintFailureKind = 'not-found' | 'rate-limited' | 'sign-in' | 'rejected' | 'unavailable';

export class ComplaintFailure extends Error {
    constructor(readonly kind: ComplaintFailureKind, readonly retryAfter: number | null = null) {
        super(`Complaint failed: ${kind}`);
        this.name = 'ComplaintFailure';
    }
}

/** 404 is the opaque «no such target» (also a hidden deck); 429 the daily limit; 401 a guest or an ended session. */
export function complaintFailureOf(error: unknown): ComplaintFailure {
    if (error instanceof ComplaintFailure) return error;
    if (!(error instanceof HttpErrorResponse)) return new ComplaintFailure('unavailable');
    switch (error.status) {
        case 401: return new ComplaintFailure('sign-in');
        case 404: return new ComplaintFailure('not-found');
        case 429: return new ComplaintFailure('rate-limited', retryAfterOf(error));
        case 400: case 403: return new ComplaintFailure('rejected');
        default: return new ComplaintFailure('unavailable');
    }
}

function retryAfterOf(error: HttpErrorResponse): number | null {
    const header = error.headers?.get('Retry-After') ?? null;
    return header !== null && /^\d{1,6}$/u.test(header) ? Number(header) : null;
}

/** Characters (code points) of a text, the unit of the 1000 limit. */
export function codePoints(text: string): number { return Array.from(text).length; }

/**
 * The request body: the reason, and the text only when it is not blank. The text is trimmed; control characters other than a line
 * feed and a tab (a paste can carry them) are dropped, the server would refuse them.
 */
export function complaintBody(reason: ComplaintReason, text: string): { readonly reason: ComplaintReason; readonly text?: string } {
    // eslint-disable-next-line no-control-regex
    const clean = text.replace(/\r\n?/gu, '\n').replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/gu, '').trim();
    return clean.length === 0 ? { reason } : { reason, text: clean };
}
