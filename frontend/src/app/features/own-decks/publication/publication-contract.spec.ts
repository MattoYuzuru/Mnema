import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';

import metadataFixture from '../../../../../../contracts/decks/metadata.json';
import publication from '../../../../../../contracts/decks/publication.json';
import { AuthoringProtocolError } from '../../authoring/authoring.models';
import { DECK_VISIBILITIES } from '../own-deck.models';
import { PublicationApiService } from './publication-api.service';
import {
    CHECKLIST_KEYS,
    CONTENT_LEVELS,
    PUBLICATION_LEVELS,
    PublicationCommand,
    RELEASE_NOTE_MAX_CODE_POINTS,
    failedChecklist,
    parsePublicationAcknowledgement,
    parsePublicationState,
    parseTopics,
    publicationFailureOf,
    releaseNoteLength,
    releaseNoteValue,
    topicLeaves
} from './publication.models';
import { publicationFailureText } from './publication.text';

/**
 * contracts/decks/publication.json is the one wire contract of the owner's publication. Every fixture must parse, the request
 * must serialize to its fixture, and the contract's constants and invariants hold on this side too.
 */
describe('Publication wire contract (contracts/decks/publication.json)', () => {
    const deckId = publication.state.never.body.deckId;
    const headers = { 'Cache-Control': 'private, no-store' };
    let http: HttpTestingController;
    let api: PublicationApiService;

    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
        http = TestBed.inject(HttpTestingController);
        api = TestBed.inject(PublicationApiService);
    });
    afterEach(() => http.verify());

    it('shares its constants with the client', () => {
        expect(PUBLICATION_LEVELS).toEqual(publication.constants.visibilities);
        expect(CONTENT_LEVELS).toEqual(publication.constants.levels);
        expect(CHECKLIST_KEYS).toEqual(publication.constants.checklistKeys);
        expect(RELEASE_NOTE_MAX_CODE_POINTS).toBe(publication.constants.releaseNoteMaxCodePoints);
        expect(DECK_VISIBILITIES).toEqual(publication.ownerDeck.visibilities);
        expect(DECK_VISIBILITIES).toEqual(metadataFixture.visibilities.values);
    });

    it('reads the never-published and the public state exactly, with the ETag of the row version', async () => {
        for (const fixture of [publication.state.never, publication.state.public]) {
            const reading = firstValueFrom(api.read(deckId));
            http.expectOne(`/api/decks/${deckId}/publication`).flush(fixture.body, { headers: { ...headers, ETag: fixture.headers.ETag } });
            expect(await reading).toEqual(fixture.body);
        }
    });

    it('holds the invariants of the state fixtures', () => {
        const never = parsePublicationState(publication.state.never.body);
        expect(never.visibility).toBe('PRIVATE');
        expect([never.publicCode, never.link, never.publishedRevisionId, never.unpublishedChanges]).toEqual([null, null, null, null]);
        expect(never.rowVersion).toBe('0');
        const shared = parsePublicationState(publication.state.public.body);
        expect(shared.link).toBe(publication.state.public.body.link);
        expect(shared.link!.startsWith(`/d/${shared.publicCode}/`)).toBe(true);
        expect(shared.unpublishedChanges).toBe(4);
        expect(shared.headRevisionId).not.toBe(shared.publishedRevisionId);
        expect(shared.metadata.tags).toEqual(['jlpt n5', 'кандзи']);
    });

    it('parses the blocked-media checklist of the contract', () => {
        const state = parsePublicationState({
            ...publication.state.public.body, checklist: publication.state.blocked.checklist
        });
        expect(state.checklist.blockedMedia).toEqual([
            { memberKey: '55555555-5555-4555-8555-555555555555', exerciseId: null, reason: 'NC_LICENSE' },
            { memberKey: null, exerciseId: '66666666-6666-4666-8666-666666666666', reason: 'NC_LICENSE' }
        ]);
        expect(failedChecklist(state.checklist)).toEqual(['blockedMedia']);
        expect(failedChecklist(parsePublicationState(publication.state.never.body).checklist)).toEqual(['topic', 'language', 'publicProfile']);
        expect(failedChecklist(parsePublicationState(publication.state.never.body).checklist, { topic: true, language: true })).toEqual(['publicProfile']);
    });

    it('sends the exact command with the If-Match of the loaded version and parses the acknowledgement', async () => {
        const request = publication.put.request;
        const result = firstValueFrom(api.save(deckId, '0', request.body as PublicationCommand));
        const sent = http.expectOne(`/api/decks/${deckId}/publication`);
        expect(sent.request.method).toBe('PUT');
        expect(sent.request.headers.get('If-Match')).toBe(request.headers['If-Match']);
        expect(sent.request.body).toEqual(request.body);
        sent.flush(publication.put.acknowledgement.body, { headers: { ...headers, ...publication.put.acknowledgement.headers } });
        const written = await result;
        expect(written.acknowledgement).toEqual(publication.put.acknowledgement.body);
        expect(written.replayed).toBe(false);
        expect(written.acknowledgement.publication.rowVersion).toBe('1');
    });

    it('sends the no-publish command as null and treats an exact retry as a replay without an ETag', async () => {
        const command = publication.put.requestNoPublish.body as PublicationCommand;
        const result = firstValueFrom(api.save(deckId, '3', command));
        const sent = http.expectOne(`/api/decks/${deckId}/publication`);
        expect(sent.request.body.publish).toBeNull();
        sent.flush({ ...publication.put.acknowledgement.body, commandId: command.commandId }, { headers: { ...headers, 'Idempotency-Replayed': 'true' } });
        expect((await result).replayed).toBe(true);
    });

    it('rejects an answer that is cacheable, carries the wrong ETag, another deck or another command', async () => {
        const read = (body: object, extra: Record<string, string>) => {
            const reading = firstValueFrom(api.read(deckId));
            http.expectOne(`/api/decks/${deckId}/publication`).flush(body, { headers: extra });
            return reading;
        };
        await expect(read(publication.state.never.body, { ETag: '"0"' })).rejects.toThrowError(AuthoringProtocolError);
        await expect(read(publication.state.never.body, { ...headers, ETag: '"5"' })).rejects.toThrowError(AuthoringProtocolError);
        await expect(read({ ...publication.state.never.body, deckId: '99999999-9999-4999-8999-999999999999' }, { ...headers, ETag: '"0"' }))
            .rejects.toThrowError(AuthoringProtocolError);
        const command = publication.put.request.body as PublicationCommand;
        const saving = firstValueFrom(api.save(deckId, '0', command));
        http.expectOne(`/api/decks/${deckId}/publication`).flush({ ...publication.put.acknowledgement.body, commandId: '018f1d98-5c10-7abc-8abc-0123456789ff' },
            { headers: { ...headers, ETag: '"1"' } });
        await expect(saving).rejects.toThrowError(AuthoringProtocolError);
    });

    it('reads the topic directory and finds its leaves', async () => {
        const reading = firstValueFrom(api.topics());
        http.expectOne('/api/topics').flush(publication.topics.body, { headers: publication.topics.headers });
        const topics = await reading;
        expect(topics.map(topic => topic.topicId)).toEqual(['languages', 'other']);
        expect(topicLeaves(topics).map(topic => topic.topicId)).toEqual(['english', 'japanese', 'other']);

        const cached = firstValueFrom(api.topics());
        http.expectOne('/api/topics').flush(publication.topics.body, { headers });
        await expect(cached).rejects.toThrowError(AuthoringProtocolError);
    });

    it('rejects a state or directory that breaks the shape', () => {
        const good = publication.state.public.body;
        const broken: unknown[] = [
            { ...good, visibility: 'public' },
            { ...good, extra: true },
            { ...good, link: null },
            { ...good, publicCode: null },
            { ...good, link: '/d/other/slug' },
            { ...good, publishedAt: null },
            { ...good, unpublishedChanges: null },
            { ...good, rowVersion: '03' },
            { ...good, metadata: { ...good.metadata, tags: ['a', 'a'] } },
            { ...good, metadata: { ...good.metadata, level: 'D1' } },
            { ...good, metadata: { ...good.metadata, contentLanguage: 'RU' } },
            { ...good, checklist: { ...good.checklist, blockedMedia: [{ memberKey: good.deckId, reason: 'OTHER' }] } },
            { ...good, suggested: { contentLanguage: 'ru', topicIds: ['a', 'b', 'c', 'd'] } },
            { ...publication.state.never.body, visibility: 'LINK' }
        ];
        for (const value of broken) expect(() => parsePublicationState(value)).toThrowError(AuthoringProtocolError);
        expect(() => parsePublicationAcknowledgement({ ...publication.put.acknowledgement.body, changed: 'yes' })).toThrowError(AuthoringProtocolError);
        expect(() => parseTopics({ topics: [{ topicId: 'a', nameRu: 'А', nameEn: 'A', ordinal: 1 }] })).toThrowError(AuthoringProtocolError);
    });

    it('maps the problems of the contract to plain words', () => {
        const failure = (problem: { status: number; code: string; failed?: string[] }) =>
            publicationFailureOf(new HttpErrorResponse({ status: problem.status, error: problem }))!;
        const requirements = failure(publication.problems.publicationRequirements);
        expect(requirements).toEqual({ status: 409, code: 'PUBLICATION_REQUIREMENTS', failed: ['topic', 'language', 'publicProfile'] });
        expect(publicationFailureText(requirements)).toContain('тема, язык, публичный профиль');
        expect(failure(publication.problems.staleVersion).status).toBe(412);
        expect(publicationFailureText(failure(publication.problems.staleVersion))).toContain('Колода изменилась');
        expect(failure(publication.problems.publicationRequired).code).toBe('PUBLICATION_REQUIRED');
        expect(publicationFailureText(failure(publication.problems.publicationRequired))).toContain('Повторите');
        expect(publicationFailureText(publicationFailureOf(new HttpErrorResponse({ status: 0 })))).toContain('соединение');
        expect(publicationFailureOf(new Error('x'))).toBeNull();
        expect(publicationFailureText(null)).toContain('соединение');
    });

    it('counts the release note in code points and sends blank as null', () => {
        expect(releaseNoteLength('  😀😀 ')).toBe(2);
        expect(releaseNoteValue('   ')).toBeNull();
        expect(releaseNoteValue(' Добавлены слова ')).toBe('Добавлены слова');
    });
});
