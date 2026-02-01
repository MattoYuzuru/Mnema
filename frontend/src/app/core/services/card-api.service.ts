import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Page } from '../models/page.models';
import { UserCardDTO, CreateCardRequest, MissingFieldSummary, DuplicateGroup } from '../models/user-card.models';
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
        body: Partial<UserCardDTO>,
        scope?: 'local' | 'global'
    ): Observable<UserCardDTO> {
        let params = new HttpParams();
        if (scope) {
            params = params.set('scope', scope);
        }
        return this.http.patch<UserCardDTO>(`${this.baseUrl}/${userDeckId}/cards/${cardId}`, body, { params });
    }

    deleteUserCard(userDeckId: string, cardId: string, scope?: 'local' | 'global'): Observable<void> {
        let params = new HttpParams();
        if (scope) {
            params = params.set('scope', scope);
        }
        return this.http.delete<void>(`${this.baseUrl}/${userDeckId}/cards/${cardId}`, { params });
    }

    getMissingFieldSummary(userDeckId: string, fields: string[], sampleLimit: number): Observable<MissingFieldSummary> {
        return this.http.post<MissingFieldSummary>(`${this.baseUrl}/${userDeckId}/cards/missing-fields`, {
            fields,
            sampleLimit
        });
    }

    getDuplicateGroups(
        userDeckId: string,
        fields: string[],
        limitGroups = 10,
        perGroupLimit = 5
    ): Observable<DuplicateGroup[]> {
        return this.http.post<DuplicateGroup[]>(`${this.baseUrl}/${userDeckId}/cards/duplicates`, {
            fields,
            limitGroups,
            perGroupLimit
        });
    }
}
