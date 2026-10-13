import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map, timeout } from 'rxjs';
import { appConfig } from '../../app.config';
import { CommunityQuery, deckParams } from './community-api.service';
import { InterestProfile, RecommendationPage, parseInterestProfile, parseRecommendationPage } from './recommendations.models';
export type CatalogSurface = 'catalog' | 'search' | 'for-you' | 'author' | 'detail';
export interface CardAttribution { readonly code: string; readonly surface: CatalogSurface; readonly position: number | null; readonly rankVersion: 'recs-v1' | 'catalog-v1' }
export interface InterestCommand { readonly commandId: string; readonly topicIds?: readonly string[] }
@Injectable({ providedIn: 'root' })
export class RecommendationsApiService {
    private readonly http = inject(HttpClient);
    private readonly base = `${appConfig.learningApiBaseUrl.replace(/\/$/u, '')}/community`;
    profile(): Observable<InterestProfile> { return this.http.get<unknown>(`${this.base}/interests`).pipe(timeout(10000), map(parseInterestProfile)); }
    change(command: InterestCommand, version: string, reset: boolean): Observable<InterestProfile> {
        const options = { headers: new HttpHeaders({ 'If-Match': `"${version}"` }) };
        const request = reset ? this.http.post<unknown>(`${this.base}/interests:reset`, command, options)
            : this.http.put<unknown>(`${this.base}/interests`, command, options);
        return request.pipe(timeout(10000), map(parseInterestProfile));
    }
    recommendations(query: CommunityQuery, cursor: string | null = null, limit = 24): Observable<RecommendationPage> {
        const params = deckParams(query, cursor, limit).set('sort', 'for-you');
        return this.http.get<unknown>(`${this.base}/recommendations`, { params }).pipe(timeout(10000), map(body => parseRecommendationPage(body, limit)));
    }
    event(kind: 'DETAIL_VIEW' | 'SEARCH_CLICK', context: CardAttribution): Observable<void> {
        return this.http.post<void>(`${this.base}/events`, { commandId: crypto.randomUUID(), kind, code: context.code, surface: context.surface, position: context.position, rankVersion: context.rankVersion });
    }
    exposures(surface: CatalogSurface, rankVersion: CardAttribution['rankVersion'], items: readonly { readonly code: string; readonly position: number | null }[]): Observable<void> {
        return this.http.post<void>(`${this.base}/exposures`, { commandId: crypto.randomUUID(), surface, rankVersion, items: items.map(item => ({ code: item.code, position: item.position })) });
    }
}
