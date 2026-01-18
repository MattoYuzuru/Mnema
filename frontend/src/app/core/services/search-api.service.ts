import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Page } from '../models/page.models';
import { UserCardDTO } from '../models/user-card.models';
import { CardTemplateDTO } from '../models/template.models';
import { appConfig } from '../../app.config';

@Injectable({ providedIn: 'root' })
export class SearchApiService {
    private readonly baseUrl = `${appConfig.coreApiBaseUrl}/search`;

    constructor(private http: HttpClient) {}

    searchUserCards(
        userDeckId: string,
        query: string,
        tags: string[] | null,
        page: number,
        limit: number
    ): Observable<Page<UserCardDTO>> {
        let params = new HttpParams()
            .set('page', page.toString())
            .set('limit', limit.toString())
            .set('query', query);

        if (tags && tags.length > 0) {
            tags.forEach(tag => {
                params = params.append('tags', tag);
            });
        }

        return this.http.get<Page<UserCardDTO>>(`${this.baseUrl}/decks/${userDeckId}/cards`, { params });
    }

    searchTemplates(
        query: string,
        scope: 'public' | 'mine' | 'all',
        page: number,
        limit: number
    ): Observable<Page<CardTemplateDTO>> {
        const params = new HttpParams()
            .set('query', query)
            .set('scope', scope)
            .set('page', page.toString())
            .set('limit', limit.toString());

        return this.http.get<Page<CardTemplateDTO>>(`${this.baseUrl}/templates`, { params });
    }
}
