import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, defer, map } from 'rxjs';

import { appConfig } from '../../../app.config';
import { AuthoringProtocolError, requireCount, requireEntity, requireInstant, requireObject } from '../../authoring/authoring.models';

/** `GET /api/decks/{deckId}/moderation` (`contracts/moderation/complaints.json`, `ownerNotice`). */
export interface DeckModeration {
    readonly deckId: string;
    /** The console owner hid the deck from the Community after a complaint. */
    readonly hiddenByModeration: boolean;
    readonly hiddenAt: string | null;
    /** Heads of this deck that are taken down right now. */
    readonly takenDown: { readonly materials: number; readonly exercises: number };
}

/** The owner's notice about his own deck (a source or a copy). Everybody else gets the opaque 404; the notice is decoration for the hub, so a failure is quiet. */
@Injectable({ providedIn: 'root' })
export class ModerationApiService {
    private readonly http = inject(HttpClient);
    private readonly baseUrl = appConfig.learningApiBaseUrl.replace(/\/$/, '');

    notice(deckId: string): Observable<DeckModeration> {
        return defer(() => this.http.get<unknown>(`${this.baseUrl}/decks/${encodeURIComponent(requireEntity(deckId))}/moderation`)).pipe(
            map(body => {
                const notice = parseDeckModeration(body);
                if (notice.deckId !== deckId.toLowerCase()) throw new AuthoringProtocolError('Moderation notice deck mismatch.');
                return notice;
            }));
    }
}

export function parseDeckModeration(value: unknown): DeckModeration {
    const object = requireObject(value, ['deckId', 'hiddenByModeration', 'hiddenAt', 'takenDown']);
    if (typeof object['hiddenByModeration'] !== 'boolean') throw new AuthoringProtocolError('Invalid moderation flag.');
    const hiddenAt = object['hiddenAt'] === null ? null : requireInstant(object['hiddenAt']);
    if ((hiddenAt === null) === object['hiddenByModeration']) throw new AuthoringProtocolError('Inconsistent moderation notice.');
    const counts = requireObject(object['takenDown'], ['materials', 'exercises']);
    return {
        deckId: requireEntity(object['deckId']), hiddenByModeration: object['hiddenByModeration'], hiddenAt,
        takenDown: { materials: requireCount(counts['materials'], 100_000), exercises: requireCount(counts['exercises'], 1_000_000) }
    };
}
