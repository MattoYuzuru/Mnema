import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, defer, map, throwError, timeout } from 'rxjs';

import { appConfig } from '../app.config';
import {
    COMPLAINT_DECK_CODE,
    COMPLAINT_TEXT_MAX,
    COMPLAINT_USERNAME,
    ComplaintFailure,
    ComplaintReason,
    codePoints,
    complaintBody,
    complaintFailureOf
} from './complaint.models';

/** A request that has not answered by then is an unknown outcome; filing is idempotent, so the reader may press «Отправить» again. */
const FILING_TIMEOUT_MS = 15_000;

/**
 * Files a complaint (Share/15, `contracts/moderation/complaints.json`). Signed-in readers only: the bearer is attached by the
 * interceptor. The deck is named by its public code, the profile by its username. A repeat while the first complaint is open is
 * the same answer, so a retry after an unknown outcome is harmless. Every failure leaves here as a {@link ComplaintFailure}.
 */
@Injectable({ providedIn: 'root' })
export class ComplaintApiService {
    private readonly http = inject(HttpClient);
    private readonly baseUrl = `${appConfig.learningApiBaseUrl.replace(/\/$/, '')}/public`;

    fileDeck(code: string, reason: ComplaintReason, text: string): Observable<void> {
        if (!COMPLAINT_DECK_CODE.test(code)) return throwError(() => new ComplaintFailure('not-found'));
        return this.file(`${this.baseUrl}/decks/${code}/complaints`, reason, text);
    }

    /** Ready for the profile page (epic 3); no screen calls it yet. */
    fileProfile(username: string, reason: ComplaintReason, text: string): Observable<void> {
        if (!COMPLAINT_USERNAME.test(username)) return throwError(() => new ComplaintFailure('not-found'));
        return this.file(`${this.baseUrl}/profiles/${encodeURIComponent(username)}/complaints`, reason, text);
    }

    private file(url: string, reason: ComplaintReason, text: string): Observable<void> {
        const body = complaintBody(reason, text);
        if (codePoints(body.text ?? '') > COMPLAINT_TEXT_MAX) return throwError(() => new ComplaintFailure('rejected'));
        return defer(() => this.http.post<unknown>(url, body, { observe: 'response' })).pipe(
            timeout(FILING_TIMEOUT_MS),
            map(response => {
                const answer = response.body;
                const received = response.status === 202 && answer !== null && typeof answer === 'object' && !Array.isArray(answer)
                    && Object.keys(answer).length === 1 && (answer as Record<string, unknown>)['status'] === 'RECEIVED';
                if (!received) throw new ComplaintFailure('unavailable');
            }),
            catchError((error: unknown) => throwError(() => complaintFailureOf(error)))
        );
    }
}
