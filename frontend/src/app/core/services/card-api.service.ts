import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Page } from '../models/page.models';
import { UserCardDTO, CreateCardRequest } from '../models/user-card.models';
import { appConfig } from '../../app.config';

@Injectable({ providedIn: 'root' })
export class CardApiService {
    private readonly baseUrl = `${appConfig.coreApiBaseUrl}/decks`;

    constructor(private http: HttpClient) {}

    getUserCards(userDeckId: string, page: number, limit: number): Observable<Page<UserCardDTO>> {
        const params = new HttpParams()
            .set('page', page.toString())
            .set('limit', limit.toString());
        return this.http.get<Page<UserCardDTO>>(`${this.baseUrl}/${userDeckId}/cards`, { params });
    }

    getUserCard(userDeckId: string, cardId: string): Observable<UserCardDTO> {
        return this.http.get<UserCardDTO>(`${this.baseUrl}/${userDeckId}/cards/${cardId}`);
    }

    createCard(userDeckId: string, body: CreateCardRequest): Observable<UserCardDTO> {
        return this.http.post<UserCardDTO>(`${this.baseUrl}/${userDeckId}/cards`, body);
    }

    createCardsBatch(userDeckId: string, body: CreateCardRequest[]): Observable<UserCardDTO[]> {
        return this.http.post<UserCardDTO[]>(`${this.baseUrl}/${userDeckId}/cards/batch`, body);
    }

    patchUserCard(
        userDeckId: string,
        cardId: string,
        body: Partial<UserCardDTO>
    ): Observable<UserCardDTO> {
        return this.http.patch<UserCardDTO>(`${this.baseUrl}/${userDeckId}/cards/${cardId}`, body);
    }

    deleteUserCard(userDeckId: string, cardId: string): Observable<void> {
        return this.http.delete<void>(`${this.baseUrl}/${userDeckId}/cards/${cardId}`);
    }
}
