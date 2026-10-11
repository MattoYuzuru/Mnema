import { Injectable } from '@angular/core';
import { Observable, delay, of, throwError } from 'rxjs';

import type { ComplaintApiService } from '../shared/complaint-api.service';
import { ComplaintFailure, ComplaintReason } from '../shared/complaint.models';

/**
 * The complaint window of the styleguide talks to this instead of the server (the page needs no backend). It always answers «received»,
 * except for two scripted texts so the states can be seen: «404» (the deck is gone for the reader) and «429» (the daily limit).
 */
@Injectable()
export class DemoComplaintApi implements Pick<ComplaintApiService, 'fileDeck' | 'fileProfile'> {
    fileDeck(_code: string, _reason: ComplaintReason, text: string): Observable<void> { return this.answer(text); }

    fileProfile(_username: string, _reason: ComplaintReason, text: string): Observable<void> { return this.answer(text); }

    private answer(text: string): Observable<void> {
        const script = text.trim();
        if (script === '404') return throwError(() => new ComplaintFailure('not-found')).pipe(delay(300));
        if (script === '429') return throwError(() => new ComplaintFailure('rate-limited', 60)).pipe(delay(300));
        return of(undefined).pipe(delay(300));
    }
}
