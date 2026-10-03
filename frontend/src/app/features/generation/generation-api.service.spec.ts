import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';

import { AuthoringProtocolError } from '../authoring/authoring.models';
import { buildMaterialsSpec } from './generation-composer.component';
import { GenerationApiService } from './generation-api.service';
import { DEFAULT_SETTINGS } from './generation-settings.component';
import { clone, eventsContract, examples, httpContract, ids, pathOf, privateHeaders, usageContract } from './generation-test-data';

describe('GenerationApiService', () => {
    let api: GenerationApiService;
    let http: HttpTestingController;
    const command = ids.command;
    const spec = buildMaterialsSpec('Объясни разницу', DEFAULT_SETTINGS, [], { image: false, audio: false });
    const deckPin = { rowVersion: '8', revisionId: ids.deckRevision };
    const target = { artifactId: ids.first, expectedArtifactVersion: '4', expectedRevisionId: ids.revision };

    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
        api = TestBed.inject(GenerationApiService);
        http = TestBed.inject(HttpTestingController);
    });
    afterEach(() => http.verify());

    /** The request of an operation, checked against its method and path in http.json. */
    function expectOperation(operationId: string, values: Record<string, string> = {}, query = '') {
        const contract = httpContract['endpoints'].find((endpoint: any) => endpoint.operationId === operationId);
        const request = http.expectOne(candidate => candidate.url === `${pathOf(operationId, values).replace(/^\/api/, '/api')}`
            && (query === '' || candidate.urlWithParams.endsWith(`?${query}`)));
        expect(request.request.method, operationId).toBe(contract.method);
        return request;
    }

    it('estimates a spec: POST {spec} to the deck estimate path, and parses the contract answer', async () => {
        const result = firstValueFrom(api.estimate(ids.deckId, spec));
        const request = expectOperation('estimateGeneration');
        expect(Object.keys(request.request.body)).toEqual(['spec']);
        expect(request.request.body.spec).toMatchObject({ kind: 'MATERIALS', prompt: 'Объясни разницу', sources: [] });
        request.flush(usageContract['estimateResponse'], { headers: privateHeaders });
        expect((await result).percentOfPeriodAllowance.p95).toBe(6);
    });

    it('creates a session with a commandId, checks the 201, the ETag and the deck, and reports a replay', async () => {
        const result = firstValueFrom(api.createSession(ids.deckId, spec, command));
        const request = expectOperation('createSession');
        expect(request.request.body).toMatchObject({ commandId: command, spec: { kind: 'MATERIALS' } });
        request.flush(examples['sessionDetailCreated'], { status: 201, statusText: 'Created', headers: { ...privateHeaders, ETag: '"1"' } });
        expect(await result).toMatchObject({ replayed: false, session: { sessionId: ids.sessionId, state: 'RUNNING' } });

        const replay = firstValueFrom(api.createSession(ids.deckId, spec, command));
        expectOperation('createSession').flush(examples['sessionDetailCreated'],
            { status: 201, statusText: 'Created', headers: { ...privateHeaders, 'Idempotency-Replayed': 'true' } });
        expect(await replay).toMatchObject({ replayed: true });
    });

    it('refuses a creation answer with the wrong status, a cacheable response, a wrong ETag, an ETag on a replay or another deck', async () => {
        const attempt = async (flush: (request: ReturnType<typeof expectOperation>) => void) => {
            const result = firstValueFrom(api.createSession(ids.deckId, spec, command));
            flush(expectOperation('createSession'));
            await expect(result).rejects.toBeInstanceOf(AuthoringProtocolError);
        };
        const created = (headers: Record<string, string>, body: object = examples['sessionDetailCreated']) =>
            (request: ReturnType<typeof expectOperation>) => request.flush(body, { status: 201, statusText: 'Created', headers });
        await attempt(request => request.flush(examples['sessionDetailCreated'], { status: 200, statusText: 'OK', headers: { ...privateHeaders, ETag: '"1"' } }));
        await attempt(created({ ETag: '"1"' }));
        await attempt(created({ ...privateHeaders, ETag: '"2"' }));
        await attempt(created({ ...privateHeaders, ETag: '"1"', 'Idempotency-Replayed': 'true' }));
        await attempt(created({ ...privateHeaders, 'Idempotency-Replayed': 'false' }));
        await attempt(created({ ...privateHeaders, ETag: '"1"' }, { ...clone(examples['sessionDetailCreated']), deckId: '22222222-2222-4222-8222-222222222222' }));
    });

    it('lists the sessions of a deck (active only on request) and the active sessions of the account', async () => {
        const deck = firstValueFrom(api.listSessions(ids.deckId, { active: true, cursor: 'c1', limit: 5 }));
        const request = expectOperation('listSessions', {}, 'limit=5&active=true&cursor=c1');
        request.flush({ items: [examples['sessionSummary']], nextCursor: null }, { headers: privateHeaders });
        expect((await deck).items[0]!.sessionId).toBe(ids.sessionId);

        const plain = firstValueFrom(api.listSessions(ids.deckId));
        expectOperation('listSessions', {}, 'limit=20').flush({ items: [], nextCursor: 'next' }, { headers: privateHeaders });
        expect((await plain).nextCursor).toBe('next');

        const account = firstValueFrom(api.listActiveSessions());
        const all = expectOperation('listActiveSessions', {}, 'state=active&limit=20');
        all.flush({ items: [examples['sessionSummary']], nextCursor: null }, { headers: privateHeaders });
        expect((await account).items).toHaveLength(1);

        const paged = firstValueFrom(api.listActiveSessions({ cursor: 'c2', limit: 50 }));
        expectOperation('listActiveSessions', {}, 'state=active&limit=50&cursor=c2').flush({ items: [], nextCursor: null }, { headers: privateHeaders });
        await paged;
    });

    it('reads a session, checks its identity and ETag, and cancels it with a commandId and no If-Match', async () => {
        const session = firstValueFrom(api.getSession(ids.deckId, ids.sessionId));
        expectOperation('getSession').flush(examples['sessionDetail'], { headers: { ...privateHeaders, ETag: '"12"' } });
        expect((await session).rowVersion).toBe('12');

        const wrong = firstValueFrom(api.getSession(ids.deckId, ids.sessionId));
        expectOperation('getSession').flush(examples['sessionDetail'], { headers: { ...privateHeaders, ETag: '"13"' } });
        await expect(wrong).rejects.toBeInstanceOf(AuthoringProtocolError);

        const cancelled = firstValueFrom(api.cancelSession(ids.deckId, ids.sessionId, command));
        const request = expectOperation('cancelSession');
        expect(request.request.body).toEqual({ commandId: command });
        expect(request.request.headers.has('If-Match')).toBe(false);
        request.flush(examples['sessionDetailCancelled'], { headers: { ...privateHeaders, ETag: '"13"' } });
        expect((await cancelled).state).toBe('CANCELLED');
    });

    it('deletes a session: DELETE answers 204 and nothing else', async () => {
        const done = firstValueFrom(api.deleteSession(ids.deckId, ids.sessionId));
        const request = expectOperation('deleteSession');
        expect(request.request.headers.has('If-Match')).toBe(false);
        request.flush(null, { status: 204, statusText: 'No Content', headers: privateHeaders });
        await done;

        const wrong = firstValueFrom(api.deleteSession(ids.deckId, ids.sessionId));
        expectOperation('deleteSession').flush('x', { status: 200, statusText: 'OK', headers: privateHeaders });
        await expect(wrong).rejects.toBeInstanceOf(AuthoringProtocolError);
    });

    it('polls events after a cursor and refuses a malformed cursor before any request', async () => {
        const page = firstValueFrom(api.listEvents(ids.deckId, ids.sessionId, '0', 100));
        expectOperation('listEvents', {}, 'after=0&limit=100').flush(eventsContract['envelope'].example, { headers: privateHeaders });
        expect((await page).cursor).toBe('5');
        await expect(firstValueFrom(api.listEvents(ids.deckId, ids.sessionId, '05'))).rejects.toBeInstanceOf(AuthoringProtocolError);
        http.expectNone(() => true);
    });

    it('reads an artifact and checks its identity, session and ETag', async () => {
        const artifact = firstValueFrom(api.getArtifact(ids.deckId, ids.sessionId, ids.first));
        expectOperation('getArtifact').flush(examples['artifactDetailItem'], { headers: { ...privateHeaders, ETag: '"4"' } });
        expect((await artifact).revision?.revisionId).toBe(ids.revision);

        const other = firstValueFrom(api.getArtifact(ids.deckId, ids.sessionId, ids.second));
        expectOperation('getArtifact', { artifactId: ids.second }).flush(examples['artifactDetailItem'], { headers: { ...privateHeaders, ETag: '"4"' } });
        await expect(other).rejects.toBeInstanceOf(AuthoringProtocolError);
    });

    it('approves one artifact pinned to the deck: If-Match is the quoted deck version, the body names the seen revision', async () => {
        const ack = firstValueFrom(api.approveArtifact(ids.deckId, ids.sessionId, target, deckPin, ids.command));
        const request = expectOperation('approveArtifact');
        expect(request.request.headers.get('If-Match')).toBe('"8"');
        expect(request.request.body).toEqual({ commandId: command, expectedArtifactVersion: '4', expectedRevisionId: ids.revision,
            expectedDeckRevisionId: ids.deckRevision });
        request.flush(examples['approvalAckItem'], { headers: { ...privateHeaders, ETag: '"8"' } });
        expect(await ack).toMatchObject({ deckVersion: '8', replayed: false });
    });

    it('refuses an approval acknowledgement for another artifact, command or ETag, and accepts a replay without ETag', async () => {
        const send = (body: object, headers: Record<string, string>) => {
            const result = firstValueFrom(api.approveArtifact(ids.deckId, ids.sessionId, target, deckPin, ids.command));
            expectOperation('approveArtifact').flush(body, { headers });
            return result;
        };
        await expect(send(examples['approvalAckExercise'], { ...privateHeaders, ETag: '"9"' })).rejects.toBeInstanceOf(AuthoringProtocolError);
        await expect(send({ ...clone(examples['approvalAckItem']), commandId: '018f1d98-5c10-7abc-8abc-0123456789ff' },
            { ...privateHeaders, ETag: '"8"' })).rejects.toBeInstanceOf(AuthoringProtocolError);
        await expect(send(examples['approvalAckItem'], { ...privateHeaders, ETag: '"7"' })).rejects.toBeInstanceOf(AuthoringProtocolError);
        const replayed = await send(examples['approvalAckItem'], { ...privateHeaders, 'Idempotency-Replayed': 'true' });
        expect(replayed.replayed).toBe(true);
    });

    it('approves a bulk selection as one command and checks the answer names exactly those artifacts', async () => {
        const targets = [target, { artifactId: ids.second, expectedArtifactVersion: '3', expectedRevisionId: '4e700000-0000-4000-8000-000000000002' }];
        const ack = firstValueFrom(api.approveArtifacts(ids.deckId, ids.sessionId, targets, deckPin, '018f1d98-5c10-7abc-8abc-0123456789b3'));
        const request = expectOperation('approveArtifacts');
        expect(request.request.headers.get('If-Match')).toBe('"8"');
        expect(request.request.body.artifacts).toHaveLength(2);
        expect(request.request.body).toMatchObject({ expectedDeckRevisionId: ids.deckRevision });
        request.flush(examples['approvalAckBulk'], { headers: { ...privateHeaders, ETag: '"9"' } });
        expect((await ack).artifacts).toHaveLength(2);

        const mismatch = firstValueFrom(api.approveArtifacts(ids.deckId, ids.sessionId, [target], deckPin, '018f1d98-5c10-7abc-8abc-0123456789b3'));
        expectOperation('approveArtifacts').flush(examples['approvalAckBulk'], { headers: { ...privateHeaders, ETag: '"9"' } });
        await expect(mismatch).rejects.toBeInstanceOf(AuthoringProtocolError);
    });

    it('refuses an empty or oversized bulk approval before any request', async () => {
        await expect(firstValueFrom(api.approveArtifacts(ids.deckId, ids.sessionId, [], deckPin, command))).rejects.toBeInstanceOf(AuthoringProtocolError);
        const many = Array.from({ length: 21 }, () => target);
        await expect(firstValueFrom(api.approveArtifacts(ids.deckId, ids.sessionId, many, deckPin, command))).rejects.toBeInstanceOf(AuthoringProtocolError);
        http.expectNone(() => true);
    });

    it('rejects, undoes the rejection (If-Match is the artifact version) and retries; each answer must carry the expected state', async () => {
        const rejectedBody = httpContract['endpoints'].find((e: any) => e.operationId === 'rejectArtifact').success.body;
        const rejected = firstValueFrom(api.rejectArtifact(ids.deckId, ids.sessionId, ids.first, '4', command));
        const reject = expectOperation('rejectArtifact');
        expect(reject.request.body).toEqual({ commandId: command, expectedArtifactVersion: '4' });
        reject.flush(rejectedBody, { headers: { ...privateHeaders, ETag: '"5"' } });
        expect((await rejected).state).toBe('REJECTED');

        const undone = firstValueFrom(api.undoRejectArtifact(ids.deckId, ids.sessionId, ids.first, '5'));
        const undo = expectOperation('undoRejectArtifact');
        expect(undo.request.headers.get('If-Match')).toBe('"5"');
        undo.flush(examples['artifactSummaryProposed'], { headers: { ...privateHeaders, ETag: '"4"' } });
        expect((await undone).state).toBe('PROPOSED');

        const retryBody = httpContract['endpoints'].find((e: any) => e.operationId === 'retryArtifact').success.body;
        const retried = firstValueFrom(api.retryArtifact(ids.deckId, ids.sessionId, ids.first, '2', command));
        const retry = expectOperation('retryArtifact');
        expect(retry.request.body).toEqual({ commandId: command, expectedArtifactVersion: '2' });
        retry.flush(retryBody, { headers: { ...privateHeaders, ETag: '"3"' } });
        expect((await retried).state).toBe('QUEUED');

        const wrongState = firstValueFrom(api.rejectArtifact(ids.deckId, ids.sessionId, ids.first, '4', command));
        expectOperation('rejectArtifact').flush(examples['artifactSummaryProposed'], { headers: { ...privateHeaders, ETag: '"4"' } });
        await expect(wrongState).rejects.toBeInstanceOf(AuthoringProtocolError);
    });

    it('accepts the stored answer of a replayed cancel, reject and retry (no ETag) and refuses an ETag on a replay', async () => {
        const replay = { ...privateHeaders, 'Idempotency-Replayed': 'true' };
        const cancelled = firstValueFrom(api.cancelSession(ids.deckId, ids.sessionId, command));
        expectOperation('cancelSession').flush(examples['sessionDetailCancelled'], { headers: replay });
        expect((await cancelled).state).toBe('CANCELLED');

        const rejectBody = httpContract['endpoints'].find((e: any) => e.operationId === 'rejectArtifact').success.body;
        const rejected = firstValueFrom(api.rejectArtifact(ids.deckId, ids.sessionId, ids.first, '4', command));
        expectOperation('rejectArtifact').flush(rejectBody, { headers: replay });
        expect((await rejected).state).toBe('REJECTED');

        const retryBody = httpContract['endpoints'].find((e: any) => e.operationId === 'retryArtifact').success.body;
        const retried = firstValueFrom(api.retryArtifact(ids.deckId, ids.sessionId, ids.first, '2', command));
        expectOperation('retryArtifact').flush(retryBody, { headers: replay });
        expect((await retried).state).toBe('QUEUED');

        const withEtag = firstValueFrom(api.retryArtifact(ids.deckId, ids.sessionId, ids.first, '2', command));
        expectOperation('retryArtifact').flush(retryBody, { headers: { ...replay, ETag: '"3"' } });
        await expect(withEtag).rejects.toBeInstanceOf(AuthoringProtocolError);
        const cancelEtag = firstValueFrom(api.cancelSession(ids.deckId, ids.sessionId, command));
        expectOperation('cancelSession').flush(examples['sessionDetailCancelled'], { headers: { ...replay, ETag: '"13"' } });
        await expect(cancelEtag).rejects.toBeInstanceOf(AuthoringProtocolError);
    });

    it('hands an artifact off: 201 with the hand-off artifact and the new draft', async () => {
        const body = httpContract['endpoints'].find((e: any) => e.operationId === 'handoffArtifact').success.body;
        const result = firstValueFrom(api.handoffArtifact(ids.deckId, ids.sessionId, target, command));
        const request = expectOperation('handoffArtifact');
        expect(request.request.body).toEqual({ commandId: command, expectedArtifactVersion: '4', expectedRevisionId: ids.revision });
        request.flush(body, { status: 201, statusText: 'Created', headers: { ...privateHeaders, ETag: '"0"' } });
        expect((await result).draft.draftId).toBe('d4af7000-0000-4000-8000-000000000001');

        const wrong = firstValueFrom(api.handoffArtifact(ids.deckId, ids.sessionId, target, command));
        expectOperation('handoffArtifact').flush({ ...clone(body), artifact: { ...clone(body.artifact), state: 'PROPOSED' } },
            { status: 201, statusText: 'Created', headers: privateHeaders });
        await expect(wrong).rejects.toBeInstanceOf(AuthoringProtocolError);
    });

    it('refuses identifiers that are not canonical before any request', async () => {
        await expect(firstValueFrom(api.getSession('../x', ids.sessionId))).rejects.toBeInstanceOf(AuthoringProtocolError);
        await expect(firstValueFrom(api.getArtifact(ids.deckId, ids.sessionId, 'x'))).rejects.toBeInstanceOf(AuthoringProtocolError);
        await expect(firstValueFrom(api.cancelSession(ids.deckId, ids.sessionId, 'not-a-command'))).rejects.toBeInstanceOf(AuthoringProtocolError);
        http.expectNone(() => true);
    });
});
