import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Page } from '../models/page.models';
import { UserDeckDTO } from '../models/user-deck.models';
import { PublicDeckDTO } from '../models/public-deck.models';
import { appConfig } from '../../app.config';

@Injectable({ providedIn: 'root' })
export class DeckApiService {
    private readonly baseUrl = `${appConfig.coreApiBaseUrl}/decks`;

    constructor(private http: HttpClient) {}

    getMyDecks(page: number, limit: number): Observable<Page<UserDeckDTO>> {
        const params = new HttpParams()
            .set('page', page.toString())
            .set('limit', limit.toString());
        return this.http.get<Page<UserDeckDTO>>(`${this.baseUrl}/mine`, { params });
    }

    getUserDeck(userDeckId: string): Observable<UserDeckDTO> {
        return this.http.get<UserDeckDTO>(`${this.baseUrl}/${userDeckId}`);
    }

    createDeck(body: Partial<PublicDeckDTO>): Observable<UserDeckDTO> {
        return this.http.post<UserDeckDTO>(this.baseUrl, body);
    }

    patchDeck(userDeckId: string, body: Partial<UserDeckDTO>): Observable<UserDeckDTO> {
        return this.http.patch<UserDeckDTO>(`${this.baseUrl}/${userDeckId}`, body);
    }

    syncDeck(userDeckId: string): Observable<UserDeckDTO> {
        return this.http.post<UserDeckDTO>(`${this.baseUrl}/${userDeckId}/sync`, {});
    }

    deleteDeck(userDeckId: string): Observable<void> {
        return this.http.delete<void>(`${this.baseUrl}/${userDeckId}`);
    }

    getUserDeckSize(userDeckId: string): Observable<{ deckId: string; cardsQty: number }> {
        return this.http.get<{ deckId: string; cardsQty: number }>(`${this.baseUrl}/${userDeckId}/size`);
    }
}
