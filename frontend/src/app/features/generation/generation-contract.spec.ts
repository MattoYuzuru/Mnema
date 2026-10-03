import { buildNativeRenderState } from '../../content/rendering/native-render-state';
import { AuthoringProtocolError } from '../authoring/authoring.models';
import { DEFAULT_SETTINGS } from './generation-settings.component';
import { buildMaterialsSpec } from './generation-composer.component';
import { readProblem } from './generation-problem';
import {
    ARTIFACT_ERROR_CODES, ARTIFACT_STATES, EFFORTS, OPERATION_TABLES, SESSION_STATES, SLOT_STATES, isApprovable, isRetryable,
    parseApprovalAck, parseArtifactDetail, parseArtifactSummary, parseEstimate, parseEventsPage, parseHandoff, parseSessionDetail,
    parseSessionPage, parseSessionSummary, previewDocument, serializeMaterialsSpec
} from './generation.models';
import {
    clone, errorsContract, eventsContract, examples, httpContract, ids, problemResponse, statesContract, usageContract
} from './generation-test-data';

/**
 * `contracts/generation` and `contracts/usage` are the wire contract of the Workshop, shared with the backend: every example
 * must parse strictly, and the client tables (allowed operations, enums, the spec it sends) must equal the contract.
 */
describe('Generation wire contract (contracts/generation)', () => {
    describe('sessions and artifacts', () => {
        it('parses every session example and keeps the artifact summaries in order', () => {
            for (const name of ['sessionDetail', 'sessionDetailCreated', 'sessionDetailCancelled']) {
                const session = parseSessionDetail(examples[name]);
                expect(session.artifacts.map(artifact => artifact.artifactId), name).toEqual(examples[name].artifacts.map((a: any) => a.artifactId));
                expect(session.spec.kind).toBe('MATERIALS');
                expect(session.spec.prompt).toBe(examples[name].spec.prompt);
            }
            expect(parseSessionDetail(examples['sessionDetail']).notes).toEqual({ used: 0, archivable: 0 });
            const summary = parseSessionSummary(examples['sessionSummary']);
            expect(summary).toMatchObject({ state: 'RUNNING', approvableCount: 6, usage: { reservedCredits: 21, spentCredits: 11 } });
            expect(summary.artifactCounts.PROPOSED).toBe(7);
        });

        it('agrees with the server about what is approvable: the example session has exactly approvableCount of them', () => {
            const session = parseSessionDetail(examples['sessionDetail']);
            expect(session.artifacts.filter(isApprovable)).toHaveLength(session.approvableCount);
            const cancelled = parseSessionDetail(examples['sessionDetailCancelled']);
            expect(cancelled.artifacts.filter(isApprovable)).toHaveLength(cancelled.approvableCount);
            expect(cancelled.artifacts.filter(isRetryable)).toHaveLength(1);
        });

        it('parses a session page and both artifact details; the item payload is a native-v1 document that renders', () => {
            expect(parseSessionPage({ items: [examples['sessionSummary']], nextCursor: null }).items).toHaveLength(1);
            const item = parseArtifactDetail(examples['artifactDetailItem']);
            expect(item.revision?.payload.kind).toBe('NATIVE_DOCUMENT');
            if (item.revision?.payload.kind !== 'NATIVE_DOCUMENT') throw new Error('item payload expected');
            expect(buildNativeRenderState(item.revision.payload.document).status).toBe('ready');
            expect(item.mediaSlots).toEqual([expect.objectContaining({ slotKey: 'a1', kind: 'AUDIO', state: 'READY' })]);
            const exercise = parseArtifactDetail(examples['artifactDetailExercise']);
            expect(exercise.revision?.payload.kind).toBe('EXERCISE_COMMAND');
            expect(exercise.targetKind).toBe('EXERCISE');
        });

        it('accepts an artifact that has no revision yet and refuses a revision that is not the current one', () => {
            const queued = { ...clone(examples['artifactDetailItem']), state: 'QUEUED', currentRevisionId: null, revision: null, mediaSlots: [] };
            expect(parseArtifactDetail(queued).revision).toBeNull();
            const mismatch = clone(examples['artifactDetailItem']);
            mismatch.currentRevisionId = ids.second;
            expect(() => parseArtifactDetail(mismatch)).toThrow(AuthoringProtocolError);
        });

        it('refuses unknown fields, unknown states and malformed versions', () => {
            expect(() => parseArtifactSummary({ ...examples['artifactSummaryProposed'], extra: 1 })).toThrow(AuthoringProtocolError);
            expect(() => parseArtifactSummary({ ...examples['artifactSummaryProposed'], state: 'LATER' })).toThrow(AuthoringProtocolError);
            expect(() => parseArtifactSummary({ ...examples['artifactSummaryProposed'], rowVersion: 4 })).toThrow(AuthoringProtocolError);
            expect(() => parseArtifactSummary({ ...examples['artifactSummaryProposed'], mediaSlotCounts: { total: 1, ready: 1, failed: 1 } }))
                .toThrow(AuthoringProtocolError);
            expect(() => parseSessionDetail({ ...examples['sessionDetail'], kind: 'POEM' })).toThrow(AuthoringProtocolError);
        });
    });

    describe('approval, hand-off and the other command answers', () => {
        it('parses the single and the bulk acknowledgement of the contract', () => {
            expect(parseApprovalAck(examples['approvalAckItem'], false).artifacts[0]!.publishedRef).toMatchObject({ kind: 'ITEM', ordinal: 4 });
            expect(parseApprovalAck(examples['approvalAckExercise'], true)).toMatchObject({ replayed: true, deckVersion: '9' });
            expect(parseApprovalAck(examples['approvalAckBulk'], false).artifacts.map(artifact => artifact.publishedRef.kind))
                .toEqual(['ITEM', 'EXERCISE']);
        });

        it('parses the hand-off response of http.json', () => {
            const handoff = (httpContract['endpoints'] as any[]).find(endpoint => endpoint.operationId === 'handoffArtifact').success.body;
            const result = parseHandoff(handoff);
            expect(result.artifact.state).toBe('HANDED_OFF');
            expect(result.draft).toEqual({ draftId: 'd4af7000-0000-4000-8000-000000000001', deckId: ids.deckId, memberKey: null,
                baseRevisionId: null });
        });

        it('parses the reject and retry answers as artifact summaries', () => {
            for (const name of ['rejectArtifact', 'retryArtifact']) {
                const body = (httpContract['endpoints'] as any[]).find(endpoint => endpoint.operationId === name).success.body;
                expect(parseArtifactSummary(body).artifactId).toBe(ids.first);
            }
        });
    });

    describe('events', () => {
        it('parses the polling envelope, its empty answer and one example of every event type', () => {
            const page = parseEventsPage(eventsContract['envelope'].example);
            expect(page.events.map(event => event.type)).toEqual(['ARTIFACT_STATE', 'BLOCKS_APPENDED', 'MEDIA_SLOT_STATE', 'USAGE_UPDATED', 'SESSION_STATE']);
            expect(page.cursor).toBe('5');
            expect(page.activeSteps).toHaveLength(1);
            expect(parseEventsPage(eventsContract['envelope'].emptyExample)).toMatchObject({ events: [], cursor: '5', session: { state: 'REVIEW' } });
            for (const [type, definition] of Object.entries(eventsContract['types'] as Record<string, any>)) {
                const envelope = { events: [definition.example], cursor: definition.example.seq, session: { state: 'RUNNING', rowVersion: '12' },
                    activeSteps: [] };
                expect(parseEventsPage(envelope).events[0]?.type, type).toBe(type);
            }
        });

        it('ignores an event of an unknown type but still moves the cursor, as the contract says', () => {
            const envelope = clone(eventsContract['envelope'].example);
            envelope.events.push({ seq: '6', type: 'FROM_THE_FUTURE', sessionId: ids.sessionId, artifactId: null,
                occurredAt: '2026-10-02T09:00:06Z', payload: { anything: true } });
            envelope.cursor = '6';
            const page = parseEventsPage(envelope);
            expect(page.events).toHaveLength(5);
            expect(page.cursor).toBe('6');
        });

        it('turns appended blocks into a preview document that the renderer accepts', () => {
            const appended = parseEventsPage(eventsContract['envelope'].example).events.find(event => event.type === 'BLOCKS_APPENDED');
            if (appended?.type !== 'BLOCKS_APPENDED') throw new Error('blocks expected');
            expect(appended).toMatchObject({ generation: 1, startIndex: 2 });
            expect(buildNativeRenderState(previewDocument(appended.blocks)).status).toBe('ready');
        });

        it('skips an event of a known type that does not read, counts it and still moves the cursor', () => {
            const envelope = clone(eventsContract['envelope'].example);
            envelope.events[0].payload.artifactVersion = 4;
            envelope.events[4].payload.state = 'FROM_THE_FUTURE';
            const page = parseEventsPage(envelope);
            expect(page.unreadable).toBe(2);
            expect(page.events.map(event => event.type)).toEqual(['BLOCKS_APPENDED', 'MEDIA_SLOT_STATE', 'USAGE_UPDATED']);
            expect(page.cursor).toBe('5');
        });

        it('degrades malformed draft blocks to no preview and an unknown error code to a generic failure', () => {
            const envelope = clone(eventsContract['envelope'].example);
            envelope.events[1].payload.blocks = [{ id: 'not-a-node' }];
            envelope.events[0].payload.errorCode = 'NEW_CODE_OF_THE_FUTURE';
            const page = parseEventsPage(envelope);
            expect(page.unreadable).toBe(0);
            expect(page.events.find(event => event.type === 'BLOCKS_APPENDED')).toBeUndefined();
            expect(page.events[0]).toMatchObject({ type: 'ARTIFACT_STATE', errorCode: null });
            const summary = parseArtifactSummary({ ...examples['artifactSummaryProposed'], errorCode: 'NEW_CODE_OF_THE_FUTURE' });
            expect(summary.errorCode).toBeNull();
        });

        it('refuses a broken envelope as before', () => {
            const envelope = clone(eventsContract['envelope'].example);
            envelope.cursor = 5;
            expect(() => parseEventsPage(envelope)).toThrow(AuthoringProtocolError);
        });
    });

    describe('estimate (contracts/usage)', () => {
        it('parses the estimate examples: affordable, short of budget, and the Free week that never fits', () => {
            expect(parseEstimate(usageContract['estimateResponse'])).toMatchObject({
                canStart: true, percentOfPeriodAllowance: { p50: 4, p95: 6 }, blockingBuckets: [], personalDataWarning: false });
            const short = parseEstimate(usageContract['estimateResponseShortfall']);
            expect(short).toMatchObject({ canStart: false, shortfallCredits: 32, personalDataWarning: true });
            expect(short.blockingBuckets[0]).toMatchObject({ bucket: 'CREDITS', fitsAfterRenewal: true, plan: 'PLUS' });
            expect(parseEstimate(usageContract['estimateResponseFreeBlocked']).blockingBuckets[0]).toMatchObject({ window: 'WEEK', fitsAfterRenewal: false });
        });

        it('reads USAGE_LIMIT_REACHED problems from the contract examples with the same members', () => {
            for (const name of ['example', 'exampleFreeWeek', 'exampleNotOffered']) {
                const body = usageContract['errors'].USAGE_LIMIT_REACHED[name];
                const problem = readProblem(problemResponse(409, body));
                expect(problem.usage, name).toMatchObject({ bucket: body.bucket, required: body.required, offered: body.offered, plan: body.plan });
            }
        });

        it('sends the estimate request of the contract: a spec with exactly the members of the example', () => {
            const sent = serializeMaterialsSpec(buildMaterialsSpec('Объясни разницу', { ...DEFAULT_SETTINGS, audio: true, audioVoice: 'female' }, [], { image: true, audio: true }));
            // usage.json points at the same example (`forms.spec.spec.$ref`).
            expect(usageContract['estimateRequest'].forms.spec.spec).toEqual({ $ref: '../generation/http.json#/examples/specMaterials' });
            const expected = examples['specMaterials'];
            const shape = (value: any): unknown => Array.isArray(value) ? [] : value !== null && typeof value === 'object'
                ? Object.fromEntries(Object.keys(value).sort().map(key => [key, shape(value[key])])) : typeof value;
            // outputLanguage is optional (the default is the deck language); everything else is exactly the contract's member set.
            const { outputLanguage: _language, ...contractMembers } = expected;
            const sentShape = shape(sent) as Record<string, unknown>;
            expect(sentShape).toEqual(shape(contractMembers));
        });
    });

    describe('client tables equal the contract', () => {
        it('knows exactly the session states, artifact states, slot states and artifact error codes of states.json', () => {
            expect([...SESSION_STATES].sort()).toEqual(Object.keys(statesContract['session'].states).sort());
            expect([...ARTIFACT_STATES].sort()).toEqual(Object.keys(statesContract['artifact'].states).sort());
            expect([...SLOT_STATES].sort()).toEqual(Object.keys(statesContract['mediaSlot'].states).sort());
            expect([...ARTIFACT_ERROR_CODES].sort()).toEqual(Object.keys(statesContract['artifact'].errorCodes).sort());
            expect([...EFFORTS].sort()).toEqual(['AUTO', 'DETAILED', 'MEDIUM', 'SHORT']);
        });

        it('offers an operation only where states.json allows it, for sessions and for artifacts', () => {
            const used = new Set(['approveArtifact', 'approveArtifacts', 'rejectArtifact', 'undoRejectArtifact', 'handoffArtifact', 'retryArtifact',
                'cancelSession', 'deleteSession']);
            const only = (operations: string[]) => operations.filter(operation => used.has(operation)).sort();
            for (const [state, operations] of Object.entries(statesContract['session'].allowedOperations as Record<string, string[]>)) {
                expect([...OPERATION_TABLES.session[state as keyof typeof OPERATION_TABLES.session]].sort(), state).toEqual(only(operations));
            }
            for (const [state, operations] of Object.entries(statesContract['artifact'].allowedOperations as Record<string, string[]>)) {
                expect([...OPERATION_TABLES.artifact[state as keyof typeof OPERATION_TABLES.artifact]].sort(), state).toEqual(only(operations));
            }
        });

        it('stops polling in exactly the terminal session states the contract names', () => {
            const clientCadence = eventsContract['polling'].clientCadence as string;
            for (const state of ['CLOSED', 'CANCELLED', 'EXPIRED']) expect(clientCadence).toContain(state);
            // CLOSED is no longer terminal (#288): an undo of a rejection reopens it, so the store resumes polling after the undo.
            expect(statesContract['session'].states['CLOSED'].terminal).toBe(false);
            expect(statesContract['session'].allowedOperations['CLOSED']).toContain('undoRejectArtifact');
        });

        it('lists no operation the client implements under another method or path than http.json', () => {
            const implemented = ['getCapabilities', 'estimateGeneration', 'createSession', 'listSessions', 'listActiveSessions', 'getSession',
                'cancelSession', 'deleteSession', 'listEvents', 'getArtifact', 'approveArtifact', 'approveArtifacts', 'rejectArtifact',
                'undoRejectArtifact', 'handoffArtifact', 'retryArtifact'];
            const ids = (httpContract['endpoints'] as { operationId: string }[]).map(endpoint => endpoint.operationId);
            for (const operation of implemented) expect(ids, operation).toContain(operation);
            // Edits and reverts belong to AI-11 (#293): not part of this client yet.
            // Note archival (`archiveUsedNotes`) is offered by a later task of the epic.
            expect(ids.filter(operation => !implemented.includes(operation)).sort()).toEqual(['archiveUsedNotes', 'editArtifact', 'revertArtifact']);
        });

        it('knows every problem code the contract lists for the operations it calls', () => {
            const codes = Object.keys(errorsContract['codes']);
            for (const code of ['GENERATION_STATE_CONFLICT', 'USAGE_LIMIT_REACHED', 'CAPABILITY_UNAVAILABLE', 'RESOURCE_LIMIT_EXCEEDED', 'VERSION_CONFLICT']) {
                expect(codes).toContain(code);
            }
        });
    });
});
