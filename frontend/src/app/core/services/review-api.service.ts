import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { appConfig } from '../../app.config';
import {
    ReviewNextCardResponse,
    ReviewAnswerRequest,
    ReviewAnswerResponse,
    ReviewDeckAlgorithmResponse,
    ReviewSummaryResponse,
    UpdateAlgorithmRequest,
    ReviewStatsRequest,
    ReviewStatsResponse
} from '../models/review.models';

@Injectable({ providedIn: 'root' })
export class ReviewApiService {
    private readonly baseUrl = `${appConfig.coreApiBaseUrl}/review`;

    constructor(private http: HttpClient) {}

    getNextCard(userDeckId: string): Observable<ReviewNextCardResponse> {
        return this.http.get<ReviewNextCardResponse>(`${this.baseUrl}/decks/${userDeckId}/next`);
    }

    getSummary(): Observable<ReviewSummaryResponse> {
        return this.http.get<ReviewSummaryResponse>(`${this.baseUrl}/summary`);
    }

    answerCard(userDeckId: string, userCardId: string, request: ReviewAnswerRequest): Observable<ReviewAnswerResponse> {
        return this.http.post<ReviewAnswerResponse>(`${this.baseUrl}/decks/${userDeckId}/cards/${userCardId}/answer`, request);
    }

    getDeckAlgorithm(userDeckId: string): Observable<ReviewDeckAlgorithmResponse> {
        return this.http.get<ReviewDeckAlgorithmResponse>(`${this.baseUrl}/decks/${userDeckId}/algorithm`);
    }

    updateDeckAlgorithm(userDeckId: string, request: UpdateAlgorithmRequest): Observable<ReviewDeckAlgorithmResponse> {
        return this.http.put<ReviewDeckAlgorithmResponse>(`${this.baseUrl}/decks/${userDeckId}/algorithm`, request);
    }

    getStats(request: ReviewStatsRequest): Observable<ReviewStatsResponse> {
        const params: Record<string, string> = {};
        if (request.userDeckId) {
            params['userDeckId'] = request.userDeckId;
        }
        if (request.from) {
            params['from'] = request.from;
        }
        if (request.to) {
            params['to'] = request.to;
        }
        if (request.timeZone) {
            params['timeZone'] = request.timeZone;
        }
        if (request.dayCutoffMinutes !== null && request.dayCutoffMinutes !== undefined) {
            params['dayCutoffMinutes'] = String(request.dayCutoffMinutes);
        }
        if (request.forecastDays !== null && request.forecastDays !== undefined) {
            params['forecastDays'] = String(request.forecastDays);
        }
        return this.http.get<ReviewStatsResponse>(`${this.baseUrl}/stats`, { params });
    }
}
