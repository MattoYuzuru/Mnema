import { Injectable } from '@angular/core';
import { EMPTY, Observable, delay, of, throwError } from 'rxjs';

import { PublicationApiService } from '../features/own-decks/publication/publication-api.service';
import {
    PublicationCommand,
    PublicationState,
    PublicationWriteResult,
    Topic
} from '../features/own-decks/publication/publication.models';

export const SG_PRIVATE_DECK = 'd6000000-0000-4000-8000-000000000001';
export const SG_SHARED_DECK = 'd6000000-0000-4000-8000-000000000002';

const HEAD = 'd6000000-0000-4000-8000-0000000000a1';
const PUBLISHED = 'd6000000-0000-4000-8000-0000000000a2';

const TOPICS: readonly Topic[] = [
    { topicId: 'languages', nameRu: 'Языки', nameEn: 'Languages', ordinal: 10, children: [
        { topicId: 'english', nameRu: 'Английский', nameEn: 'English', ordinal: 10, children: [] },
        { topicId: 'japanese', nameRu: 'Японский', nameEn: 'Japanese', ordinal: 20, children: [] }
    ] },
    { topicId: 'science', nameRu: 'Наука', nameEn: 'Science', ordinal: 20, children: [
        { topicId: 'biology', nameRu: 'Биология', nameEn: 'Biology', ordinal: 10, children: [] }
    ] },
    { topicId: 'other', nameRu: 'Другое', nameEn: 'Other', ordinal: 30, children: [] }
];

function initial(deckId: string): PublicationState {
    const shared = deckId === SG_SHARED_DECK;
    return {
        deckId, visibility: shared ? 'LINK' : 'PRIVATE', publicCode: shared ? 'Kq7xT3mNpR' : null, link: shared ? '/d/Kq7xT3mNpR' : null,
        publishedRevisionId: shared ? PUBLISHED : null, publishedAt: shared ? '2026-10-11T09:30:00Z' : null, headRevisionId: HEAD,
        unpublishedChanges: shared ? 4 : null,
        metadata: shared ? { topicId: 'japanese', contentLanguage: 'ru', targetLanguage: 'ja', level: 'A2', tags: ['jlpt n5'] }
            : { topicId: null, contentLanguage: null, targetLanguage: null, level: null, tags: [] },
        suggested: { contentLanguage: 'ru', topicIds: ['japanese'] }, releaseNote: null, requestsEnabled: true,
        checklist: { description: true, topic: shared, language: shared, publicProfile: true,
            catalogThreshold: { met: false, materials: 3, exercises: 2, needMaterials: 10, needExercises: 1 }, blockedMedia: [] },
        rowVersion: shared ? '1' : '0'
    };
}

/**
 * The publication specimens of the styleguide talk to this instead of the server (the page needs no backend): a private deck and a
 * deck shared by link with four unpublished changes. A save applies the command to a copy kept for the life of the page.
 */
@Injectable()
export class DemoPublicationApi implements Pick<PublicationApiService, 'read' | 'save' | 'topics'> {
    private readonly states = new Map<string, PublicationState>();

    read(deckId: string): Observable<PublicationState> {
        return of(this.stateOf(deckId)).pipe(delay(150));
    }

    save(deckId: string, rowVersion: string, command: PublicationCommand): Observable<PublicationWriteResult> {
        const current = this.stateOf(deckId);
        if (rowVersion !== current.rowVersion) return throwError(() => ({ status: 412, error: { code: 'VERSION_CONFLICT' } }));
        const publish = command.publish;
        const next: PublicationState = {
            ...current,
            visibility: command.visibility,
            publicCode: current.publicCode ?? 'Kq7xT3mNpR',
            link: command.visibility === 'PUBLIC' ? '/d/Kq7xT3mNpR/primer' : '/d/Kq7xT3mNpR',
            metadata: command.metadata,
            requestsEnabled: command.requestsEnabled,
            publishedRevisionId: publish === null ? current.publishedRevisionId : publish.expectedHeadRevisionId,
            publishedAt: publish === null ? current.publishedAt : '2026-10-11T12:00:00Z',
            unpublishedChanges: publish === null ? current.unpublishedChanges : 0,
            releaseNote: publish === null ? current.releaseNote : publish.releaseNote,
            checklist: { ...current.checklist, topic: command.metadata.topicId !== null, language: command.metadata.contentLanguage !== null },
            rowVersion: String(Number(current.rowVersion) + 1)
        };
        this.states.set(deckId, next);
        const result: PublicationWriteResult = {
            acknowledgement: { commandId: command.commandId, changed: true, publication: next }, etag: `"${next.rowVersion}"`, replayed: false
        };
        return of(result).pipe(delay(250));
    }

    topics(): Observable<readonly Topic[]> {
        return of(TOPICS).pipe(delay(100));
    }

    private stateOf(deckId: string): PublicationState {
        let state = this.states.get(deckId);
        if (state === undefined) {
            state = initial(deckId);
            this.states.set(deckId, state);
        }
        return state;
    }
}

/** The checklist looks up material names only for blocked images, which the specimens never have; the page needs no backend. */
@Injectable()
export class DemoItemApi {
    list(): Observable<never> { return EMPTY; }
}
