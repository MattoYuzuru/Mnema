import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { appConfig } from '../../app.config';
import {
    ReviewNextCardResponse,
    ReviewAnswerRequest,
    ReviewAnswerResponse,
    ReviewDeckAlgorithmResponse,
    UpdateAlgorithmRequest
} from '../models/review.models';

@Injectable({ providedIn: 'root' })
export class ReviewApiService {
    private readonly baseUrl = `${appConfig.coreApiBaseUrl}/review`;

    constructor(private http: HttpClient) {}

    getNextCard(userDeckId: string): Observable<ReviewNextCardResponse> {
        return this.http.get<ReviewNextCardResponse>(`${this.baseUrl}/decks/${userDeckId}/next`);
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
}
