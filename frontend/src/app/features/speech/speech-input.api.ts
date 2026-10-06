import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { appConfig } from '../../app.config';
import {
    SpeechConsent, SpeechConsentTerms, SpeechInputCreated, SpeechInputView, SpeechProtocolError, SpeechPurpose, parseConsent, parseCreated, parseInput
} from './speech-input.models';

export interface CreateSpeechInput {
    readonly purpose: SpeechPurpose;
    /** BCP 47 hint; omitted when unknown. */
    readonly lang?: string | null;
    /** The owner's deck: its terms become recognition hints. */
    readonly deckId?: string | null;
    readonly durationMs: number;
    /** One key per recording: a repeat with the same key and body returns the stored answer. */
    readonly idempotencyKey: string;
}

/**
 * HTTP boundary of voice input (`contracts/speech`): the consent, the raw recording and the transcript. The audio is sent as the raw body
 * with its real `Content-Type`; nothing about it is kept here.
 */
@Injectable({ providedIn: 'root' })
export class SpeechInputApiService {
    private readonly http = inject(HttpClient);
    private readonly baseUrl = appConfig.learningApiBaseUrl.replace(/\/$/u, '');

    getConsent(): Observable<SpeechConsent> {
        return this.http.get<unknown>(`${this.baseUrl}/speech-consent`, { observe: 'response' }).pipe(map(response => {
            expectStatus(response, 200);
            return parseConsent(response.body);
        }));
    }

    putConsent(terms: SpeechConsentTerms): Observable<void> {
        return this.http.put<unknown>(`${this.baseUrl}/speech-consent`, { version: terms.version, processing: terms.processing }, { observe: 'response' })
            .pipe(map(response => { if (response.status < 200 || response.status > 299) throw new SpeechProtocolError('Unexpected consent status.'); }));
    }

    /** Always available to the account, even when voice input is disabled; an absent consent is also a successful withdrawal. */
    withdrawConsent(): Observable<void> {
        return this.http.delete<void>(`${this.baseUrl}/speech-consent`, { observe: 'response' }).pipe(map(response => {
            expectStatus(response, 204);
        }));
    }

    create(blob: Blob, mimeType: string, request: CreateSpeechInput): Observable<SpeechInputCreated> {
        let params = new HttpParams().set('purpose', request.purpose);
        if (request.lang) params = params.set('lang', request.lang);
        if (request.deckId) params = params.set('deckId', request.deckId);
        return this.http.post<unknown>(`${this.baseUrl}/speech-inputs`, blob, {
            observe: 'response', params,
            headers: { 'Content-Type': mimeType, 'Idempotency-Key': request.idempotencyKey, 'X-Audio-Duration-Ms': String(Math.round(request.durationMs)) }
        }).pipe(map(response => {
            expectStatus(response, 202);
            return parseCreated(response.body);
        }));
    }

    read(speechInputId: string): Observable<SpeechInputView> {
        return this.http.get<unknown>(`${this.baseUrl}/speech-inputs/${encodeURIComponent(speechInputId)}`, { observe: 'response' }).pipe(map(response => {
            expectStatus(response, 200);
            return parseInput(response.body);
        }));
    }

    /** Idempotent: an absent input is also `204`. */
    remove(speechInputId: string): Observable<void> {
        return this.http.delete<void>(`${this.baseUrl}/speech-inputs/${encodeURIComponent(speechInputId)}`).pipe(map(() => undefined));
    }
}

function expectStatus(response: HttpResponse<unknown>, status: number): void {
    if (response.status !== status) throw new SpeechProtocolError('Unexpected speech status.');
}
