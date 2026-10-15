import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { appConfig } from '../../app.config';
import { AuthoringProtocolError, requireCount, requireEntity, requireObject, requireVersion } from './authoring.models';

export interface MaterialNavigation {
    readonly deckId: string;
    readonly memberKey: string;
    readonly deckRevisionId: string;
    readonly deckVersion: string;
    readonly ordinal: number;
    readonly total: number;
    readonly previousMemberKey: string | null;
    readonly nextMemberKey: string | null;
}

@Injectable({ providedIn: 'root' })
export class MaterialNavigationApiService {
    private readonly http = inject(HttpClient);
    read(deckId: string, memberKey: string): Observable<MaterialNavigation> {
        const deck = requireEntity(deckId); const member = requireEntity(memberKey);
        return this.http.get<unknown>(`${appConfig.learningApiBaseUrl.replace(/\/$/, '')}/decks/${deck}/items/${member}/navigation`,
            { observe: 'response' }).pipe(map(response => {
            const object = requireObject(response.body, ['deckId', 'memberKey', 'deckRevisionId', 'deckVersion', 'ordinal', 'total',
                'previousMemberKey', 'nextMemberKey']);
            const result: MaterialNavigation = {
                deckId: requireEntity(object['deckId']), memberKey: requireEntity(object['memberKey']),
                deckRevisionId: requireEntity(object['deckRevisionId']), deckVersion: requireVersion(object['deckVersion']),
                ordinal: requireCount(object['ordinal'], 99_999), total: requireCount(object['total'], 100_000),
                previousMemberKey: object['previousMemberKey'] === null ? null : requireEntity(object['previousMemberKey']),
                nextMemberKey: object['nextMemberKey'] === null ? null : requireEntity(object['nextMemberKey'])
            };
            const cache = (response.headers.get('Cache-Control') ?? '').split(',').map(value => value.trim().toLowerCase());
            if (result.deckId !== deck || result.memberKey !== member || result.ordinal >= result.total
                || (result.previousMemberKey === null) !== (result.ordinal === 0)
                || (result.nextMemberKey === null) !== (result.ordinal + 1 === result.total)
                || result.previousMemberKey === member || result.nextMemberKey === member
                || (result.previousMemberKey !== null && result.previousMemberKey === result.nextMemberKey)
                || response.headers.get('ETag') !== `"${result.deckVersion}"` || !cache.includes('private') || !cache.includes('no-store')) {
                throw new AuthoringProtocolError('Invalid material navigation.');
            }
            return result;
        }));
    }
}
