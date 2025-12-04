import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Page } from '../models/page.models';
import { PublicDeckDTO, PublicCardDTO, PublicDeckCardsPage } from '../models/public-deck.models';
import { UserDeckDTO } from '../models/user-deck.models';
import { appConfig } from '../../app.config';

@Injectable({ providedIn: 'root' })
export class PublicDeckApiService {
    private readonly baseUrl = `${appConfig.coreApiBaseUrl}/decks/public`;

    constructor(private http: HttpClient) {}

    getPublicDecks(page: number, limit: number): Observable<Page<PublicDeckDTO>> {
        const params = new HttpParams()
            .set('page', page.toString())
            .set('limit', limit.toString());
        return this.http.get<Page<PublicDeckDTO>>(this.baseUrl, { params });
    }

    getPublicDeck(deckId: string, version?: number): Observable<PublicDeckDTO> {
        let params = new HttpParams();
        if (version !== undefined) {
            params = params.set('version', version.toString());
        }
        return this.http.get<PublicDeckDTO>(`${this.baseUrl}/${deckId}`, { params });
    }

    getPublicDeckCards(
        deckId: string,
        version?: number,
        page?: number,
        limit?: number
    ): Observable<PublicDeckCardsPage> {
        let params = new HttpParams();
        if (version !== undefined) {
            params = params.set('version', version.toString());
        }
        if (page !== undefined) {
            params = params.set('page', page.toString());
        }
        if (limit !== undefined) {
            params = params.set('limit', limit.toString());
        }
        return this.http.get<PublicDeckCardsPage>(`${this.baseUrl}/${deckId}/cards`, { params });
    }

    patchPublicDeck(
        deckId: string,
        body: Partial<PublicDeckDTO>,
        version?: number
    ): Observable<PublicDeckDTO> {
        let params = new HttpParams();
        if (version !== undefined) {
            params = params.set('version', version.toString());
        }
        return this.http.patch<PublicDeckDTO>(`${this.baseUrl}/${deckId}`, body, { params });
    }

    fork(deckId: string): Observable<UserDeckDTO> {
        return this.http.post<UserDeckDTO>(`${this.baseUrl}/${deckId}/fork`, {});
    }
}
