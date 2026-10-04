import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';

import { AuthoringProtocolError } from '../authoring/authoring.models';
import { GenerationApiService } from './generation-api.service';
import { readProblem } from './generation-problem';
import {
    IMAGE_SEARCH_COST, IMAGE_SEARCH_RUNNING, attributionLine, candidateByline, editOutcomeNote, editProblemMessage, imageSourceLabel, selectionProblemMessage,
    slotFailureReason, turnFailureReason
} from './generation-view';
import {
    IMAGE_SEARCH_ERROR_CODES, IMAGE_SOURCES, RequestValidationError, parseArtifactDetail, parseTurn, serializeEdit, serializeSelection
} from './generation.models';
import { clone, examples, httpContract, ids, pathOf, privateHeaders, problemResponse, statesContract } from './generation-test-data';

const image = '00000000-0000-4000-8000-000000000009';
const command = ids.command;
const candidate = 'ca0d0000-0000-4000-8000-000000000002';
const revision = '4e700000-0000-4000-8000-000000000003';

/** The item detail of the contract with its audio slot replaced by the image-search slot of the contract. */
function detailWithSlot(change: (slot: any) => void = () => undefined): Record<string, any> {
    const detail = clone(examples['artifactDetailItem']);
    const slot = clone(examples['mediaSlotImageSearch']);
    change(slot);
    detail['mediaSlots'] = [slot];
    return detail;
}

describe('image search on the wire (contracts/generation, AI-10 #296)', () => {
    describe('the slot of a found image', () => {
        it('reads the contract example: the chosen candidate, the attribution and every candidate', () => {
            const [slot] = parseArtifactDetail(detailWithSlot()).mediaSlots;
            expect(slot).toMatchObject({ slotKey: 'i1', kind: 'IMAGE', state: 'READY', voice: null,
                attribution: { source: 'PIXABAY', author: 'Ann', license: 'Pixabay Content License', licenseUrl: null, shareAlike: false,
                    sourcePageUrl: 'https://pixabay.com/photos/fox-1/' } });
            expect(slot!.candidates.map(held => [held.source, held.shareAlike, held.chosen, held.state])).toEqual([
                ['PIXABAY', false, true, 'READY'], ['WIKIMEDIA', true, false, 'READY'], ['WIKIMEDIA', false, false, 'READY']]);
            expect(slot!.candidates[1]).toMatchObject({ candidateId: candidate, author: 'Jörg Hempel', width: 640, height: 427,
                licenseUrl: 'https://creativecommons.org/licenses/by-sa/4.0/' });
        });

        it('reads the slots of every other artifact as having no attribution and no candidates', () => {
            const [slot] = parseArtifactDetail(examples['artifactDetailItem']).mediaSlots;
            expect(slot).toMatchObject({ attribution: null, candidates: [] });
            // A server that does not send the members yet (an exercise's audio slot) is read the same way.
            const old = clone(examples['artifactDetailItem']);
            delete old['mediaSlots'][0].attribution;
            delete old['mediaSlots'][0].candidates;
            expect(parseArtifactDetail(old).mediaSlots[0]).toMatchObject({ attribution: null, candidates: [] });
        });

        it('reads the mode of a slot: search or generate for an image, null for audio, strictly', () => {
            expect(parseArtifactDetail(detailWithSlot()).mediaSlots[0]!.mode).toBe('search');
            expect(parseArtifactDetail(examples['artifactDetailItem']).mediaSlots[0]!.mode).toBeNull();
            expect(parseArtifactDetail(detailWithSlot(slot => { slot.mode = 'generate'; })).mediaSlots[0]!.mode).toBe('generate');
            expect(parseArtifactDetail(detailWithSlot(slot => { delete slot.mode; })).mediaSlots[0]!.mode).toBeNull();
            expect(() => parseArtifactDetail(detailWithSlot(slot => { slot.mode = 'upload'; }))).toThrow(AuthoringProtocolError);
        });

        it('knows exactly the sources of the contract', () => {
            const listed = (httpContract['schemas'].imageCandidate.source as string).split('|').map(entry => entry.trim());
            expect([...IMAGE_SOURCES].sort()).toEqual([...listed].sort());
        });

        it('refuses an unknown source or state, a member it does not know, a missing member and two chosen candidates', () => {
            const bad = (change: (slot: any) => void) => () => parseArtifactDetail(detailWithSlot(change));
            expect(bad(slot => { slot.candidates[0].source = 'PEXELS'; })).toThrow(AuthoringProtocolError);
            expect(bad(slot => { slot.attribution.source = 'FLICKR'; })).toThrow(AuthoringProtocolError);
            expect(bad(slot => { slot.candidates[0].state = 'LATER'; })).toThrow(AuthoringProtocolError);
            expect(bad(slot => { slot.candidates[0].extra = 1; })).toThrow(AuthoringProtocolError);
            expect(bad(slot => { delete slot.candidates[0].license; })).toThrow(AuthoringProtocolError);
            expect(bad(slot => { slot.candidates[0].shareAlike = 'yes'; })).toThrow(AuthoringProtocolError);
            expect(bad(slot => { slot.candidates[0].candidateId = 'x'; })).toThrow(AuthoringProtocolError);
            expect(bad(slot => { slot.candidates[0].width = -1; })).toThrow(AuthoringProtocolError);
            expect(bad(slot => { slot.candidates[1].chosen = true; })).toThrow(AuthoringProtocolError);
            expect(bad(slot => { slot.candidates = Array.from({ length: 13 }, () => clone(slot.candidates[1])); })).toThrow(AuthoringProtocolError);
        });

        it('treats a link that is not https as absent: it never reaches a page', () => {
            const [slot] = parseArtifactDetail(detailWithSlot(held => {
                held.attribution.sourcePageUrl = 'javascript:alert(1)';
                held.candidates[0].sourcePageUrl = 'http://pixabay.com/photos/fox-1/';
                held.candidates[1].licenseUrl = '//creativecommons.org/licenses/by-sa/4.0/';
                held.candidates[2].sourcePageUrl = 'not a url';
            })).mediaSlots;
            expect(slot!.attribution!.sourcePageUrl).toBeNull();
            expect(slot!.candidates.map(held => held.sourcePageUrl)).toEqual([null, 'https://commons.wikimedia.org/wiki/File:Fox_2.jpg', null]);
            expect(slot!.candidates[1]!.licenseUrl).toBeNull();
        });
    });

    describe('the turn', () => {
        it('reads turnImageSearchApplied, and a failed search with the slot error code it ends with', () => {
            expect(parseTurn(examples['turnImageSearchApplied'])).toMatchObject({ status: 'APPLIED', action: 'IMAGE_SEARCH', instruction: 'лиса зимой',
                resultRevisionId: revision, targetNodeIds: [image], errorCode: null });
            const failed = { ...clone(examples['turnImageSearchApplied']), status: 'FAILED', resultRevisionId: null };
            expect(parseTurn({ ...failed, errorCode: 'NO_RESULT' }).errorCode).toBe('NO_RESULT');
            expect(parseTurn({ ...failed, errorCode: 'PROVIDER_UNAVAILABLE' }).errorCode).toBe('PROVIDER_UNAVAILABLE');
            expect(parseTurn({ ...failed, errorCode: 'DEADLINE_EXCEEDED' }).errorCode).toBe('DEADLINE_EXCEEDED');
            expect(parseTurn({ ...failed, errorCode: 'SOMETHING_NEW' }).errorCode).toBeNull();
            // The redo of audio adds the rejection of the pipeline (states.json turn.audioErrorCodes).
            expect(parseTurn({ ...failed, errorCode: 'VERIFICATION_REJECTED' }).errorCode).toBe('VERIFICATION_REJECTED');
            expect(parseTurn({ ...failed, errorCode: 'ESTIMATE_EXCEEDED' }).errorCode).toBe('ESTIMATE_EXCEEDED');
        });

        it('knows exactly the image search error codes of states.json', () => {
            expect([...IMAGE_SEARCH_ERROR_CODES].sort()).toEqual(Object.keys(statesContract['turn'].imageSearchErrorCodes).sort());
        });

        it('serializes a search as the contract states it: one image, the query trimmed and left out when blank', () => {
            const base = { expectedRevisionId: ids.revision, action: 'IMAGE_SEARCH' as const, nodeIds: [image] };
            expect(serializeEdit({ ...base, instruction: '  лиса зимой  ' }, command)).toEqual({ commandId: command, expectedRevisionId: ids.revision,
                action: 'IMAGE_SEARCH', target: { nodeIds: [image] }, instruction: 'лиса зимой' });
            for (const instruction of [undefined, null, '', '   \n ']) {
                expect(serializeEdit({ ...base, instruction }, command)).toEqual({ commandId: command, expectedRevisionId: ids.revision,
                    action: 'IMAGE_SEARCH', target: { nodeIds: [image] } });
            }
        });

        it('counts the query in code points (200 of them) and refuses a search that names no image, two images or a preset', () => {
            const base = { expectedRevisionId: ids.revision, action: 'IMAGE_SEARCH' as const, nodeIds: [image] };
            expect(() => serializeEdit({ ...base, instruction: '🦊'.repeat(200) }, command)).not.toThrow();
            expect(() => serializeEdit({ ...base, instruction: '🦊'.repeat(201) }, command)).toThrow(RequestValidationError);
            expect(() => serializeEdit({ ...base, nodeIds: [] }, command)).toThrow(RequestValidationError);
            expect(() => serializeEdit({ ...base, nodeIds: [image, ids.first] }, command)).toThrow(RequestValidationError);
            expect(() => serializeEdit({ ...base, preset: 'SIMPLER' }, command)).toThrow(RequestValidationError);
        });

        it('serializes the choice of another image', () => {
            expect(serializeSelection({ expectedRevisionId: ids.revision, candidateId: candidate }, command)).toEqual({ commandId: command,
                expectedRevisionId: ids.revision, candidateId: candidate });
            expect(() => serializeSelection({ expectedRevisionId: ids.revision, candidateId: 'x' }, command)).toThrow(AuthoringProtocolError);
        });
    });

    describe('selectMediaCandidate', () => {
        let api: GenerationApiService;
        let http: HttpTestingController;
        const contract = () => httpContract['endpoints'].find((endpoint: any) => endpoint.operationId === 'selectMediaCandidate');
        const request = { expectedRevisionId: ids.revision, candidateId: candidate };

        beforeEach(() => {
            TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
            api = TestBed.inject(GenerationApiService);
            http = TestBed.inject(HttpTestingController);
        });
        afterEach(() => http.verify());

        const call = () => firstValueFrom(api.selectMediaCandidate(ids.deckId, ids.sessionId, ids.first, 'i1', request, command));
        const path = () => pathOf('selectMediaCandidate', { slotKey: 'i1' });
        const answer = () => ({ ...detailWithSlot(), rowVersion: '5' });

        it('posts the choice to the slot path of http.json and reads the artifact on the new revision', async () => {
            const result = call();
            const sent = http.expectOne(candidate => candidate.url === path());
            expect(sent.request.method).toBe(contract().method);
            expect(sent.request.headers.has('If-Match')).toBe(false);
            expect(sent.request.body).toEqual({ commandId: command, expectedRevisionId: ids.revision, candidateId: candidate });
            expect(sent.request.body).toEqual({ ...contract().requestBody.example, commandId: command, expectedRevisionId: ids.revision });
            sent.flush(answer(), { headers: { ...privateHeaders, ETag: '"5"' } });
            const detail = await result;
            expect(detail.mediaSlots[0]!.candidates).toHaveLength(3);
            expect(detail.rowVersion).toBe('5');
        });

        it('percent-encodes the slot key and refuses an empty one before any request', async () => {
            const odd = firstValueFrom(api.selectMediaCandidate(ids.deckId, ids.sessionId, ids.first, 'a b/c', request, command));
            const sent = http.expectOne(candidate => candidate.url.endsWith('/media-slots/a%20b%2Fc/selection'));
            sent.flush(answer(), { headers: { ...privateHeaders, ETag: '"5"' } });
            await odd;
            await expect(firstValueFrom(api.selectMediaCandidate(ids.deckId, ids.sessionId, ids.first, '', request, command)))
                .rejects.toBeInstanceOf(AuthoringProtocolError);
            http.expectNone(() => true);
        });

        it('accepts a replay and a no-op without a new ETag, and refuses another status, a cacheable answer, a wrong ETag or another artifact', async () => {
            const attempt = async (flush: (sent: ReturnType<typeof http.expectOne>) => void, outcome: 'ok' | 'refused') => {
                const result = call();
                flush(http.expectOne(candidate => candidate.url === path()));
                if (outcome === 'ok') await result;
                else await expect(result).rejects.toBeInstanceOf(AuthoringProtocolError);
            };
            await attempt(sent => sent.flush(answer(), { headers: privateHeaders }), 'ok');
            await attempt(sent => sent.flush(answer(), { headers: { ...privateHeaders, 'Idempotency-Replayed': 'true' } }), 'ok');
            await attempt(sent => sent.flush(answer(), { headers: { ...privateHeaders, ETag: '"6"' } }), 'refused');
            await attempt(sent => sent.flush(answer(), { headers: {} }), 'refused');
            await attempt(sent => sent.flush(answer(), { status: 202, statusText: 'Accepted', headers: privateHeaders }), 'refused');
            await attempt(sent => sent.flush({ ...answer(), artifactId: ids.second }, { headers: privateHeaders }), 'refused');
            await attempt(sent => sent.flush({ ...answer(), mediaSlots: [{ ...clone(examples['mediaSlotImageSearch']), state: 'LATER' }] }, { headers: privateHeaders }), 'refused');
        });

        it('lists the problems the words below are written for', () => {
            const codes = contract().errors.map((error: any) => `${error.status} ${error.code}`);
            expect(codes).toEqual(expect.arrayContaining(['412 VERSION_CONFLICT', '409 GENERATION_STATE_CONFLICT', '409 EDIT_IN_PROGRESS', '404 RESOURCE_NOT_FOUND']));
        });
    });

    describe('the words', () => {
        const attribution = parseArtifactDetail(detailWithSlot()).mediaSlots[0]!.attribution!;

        it('names the sources and writes the attribution line without the parts that are empty', () => {
            expect(IMAGE_SOURCES.map(imageSourceLabel)).toEqual(['Pixabay', 'Openverse', 'Wikimedia Commons', 'Тестовый источник']);
            expect(attributionLine(attribution)).toBe('Фото: Ann · Pixabay · Pixabay Content License');
            expect(attributionLine({ ...attribution, author: '' })).toBe('Pixabay · Pixabay Content License');
            expect(attributionLine({ ...attribution, author: '  ', license: '', source: 'STUB' })).toBe('Тестовый источник');
            expect(attributionLine({ ...attribution, source: 'WIKIMEDIA', license: 'CC BY-SA 4.0', author: 'Jörg Hempel' })).toBe('Фото: Jörg Hempel · Wikimedia Commons · CC BY-SA 4.0');
            expect(candidateByline(attribution)).toBe('Pixabay · Ann');
            expect(candidateByline({ ...attribution, author: '' })).toBe('Pixabay');
        });

        it('says why a slot is empty, in the words the issue gives', () => {
            expect(slotFailureReason('NO_RESULT')).toBe('Не нашлось изображений со свободной лицензией.');
            expect(slotFailureReason('PROVIDER_UNAVAILABLE')).toBe('Источники изображений сейчас недоступны.');
            expect(slotFailureReason('VERIFICATION_REJECTED')).toBe('Файл не прошёл проверку.');
            expect(slotFailureReason('DEADLINE_EXCEEDED')).toBe('Поиск занял слишком долго.');
            expect(slotFailureReason(null)).toBe('Изображение не удалось подобрать.');
            expect(turnFailureReason('NO_RESULT')).toBe('Что-то пошло не так.');
        });

        it('has the sentences of the running, applied and failed search', () => {
            expect(IMAGE_SEARCH_RUNNING).toBe('Ищу похожие изображения…');
            expect(IMAGE_SEARCH_COST).toBe('1 кредит из ИИ-бюджета');
            expect(editOutcomeNote('APPLIED', 'IMAGE_SEARCH')).toBe('Подобрала другое изображение.');
            expect(editOutcomeNote('FAILED', 'IMAGE_SEARCH')).toContain('Не нашлось подходящих изображений');
            expect(editOutcomeNote('CANCELLED', 'IMAGE_SEARCH')).toContain('Поиск остановлен');
        });

        it('explains a refused search and a refused choice calmly', () => {
            expect(editProblemMessage(readProblem(problemResponse(412, { code: 'VERSION_CONFLICT' })), 'IMAGE_SEARCH')).toContain('повторите поиск');
            expect(editProblemMessage(readProblem(problemResponse(409, { code: 'EDIT_IN_PROGRESS' })), 'IMAGE_SEARCH')).toContain('дождитесь окончания');
            expect(editProblemMessage(readProblem(problemResponse(400, { code: 'INVALID_REQUEST' })), 'IMAGE_SEARCH')).toBe('Для этого изображения поиск не подходит.');
            expect(editProblemMessage(readProblem(problemResponse(409, { code: 'USAGE_LIMIT_REACHED' })), 'IMAGE_SEARCH')).toContain('лимита ИИ');
            expect(selectionProblemMessage(readProblem(problemResponse(412, { code: 'VERSION_CONFLICT' })))).toContain('выберите изображение ещё раз');
            expect(selectionProblemMessage(readProblem(problemResponse(409, { code: 'EDIT_IN_PROGRESS' })))).toContain('дождитесь окончания');
            expect(selectionProblemMessage(readProblem(problemResponse(409, { code: 'GENERATION_STATE_CONFLICT', reason: 'ILLEGAL_STATE' })))).toContain('состояние материала изменилось');
            expect(selectionProblemMessage(readProblem(problemResponse(422, { code: 'RESOURCE_LIMIT_EXCEEDED', limit: 'REVISIONS_PER_ARTIFACT' })))).toContain('предел версий');
            expect(selectionProblemMessage(readProblem(problemResponse(404)))).not.toBe('');
        });
    });
});
