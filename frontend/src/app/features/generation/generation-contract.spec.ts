import { buildNativeRenderState } from '../../content/rendering/native-render-state';
import { AuthoringProtocolError } from '../authoring/authoring.models';
import { DEFAULT_BUILDER_VALUE, buildExercisesSpec } from './exercise-builder';
import { readProposal } from './exercise-proposal';
import { DEFAULT_SETTINGS } from './generation-settings.component';
import { buildMaterialsSpec } from './generation-composer.component';
import { readProblem } from './generation-problem';
import { HttpErrorResponse } from '@angular/common/http';
import {
    ARTIFACT_ERROR_CODES, ARTIFACT_STATES, EFFORTS, OPERATION_TABLES, SESSION_STATES, SLOT_STATES, isApprovable, isRetryable,
    NoteOverrides, SpecSource, parseApprovalAck, parseArtifactDetail, parseArtifactSummary, parseEstimate, parseEventsPage, parseHandoff,
    parseNoteArchive, parseSessionDetail, parseSessionPage, parseSessionSummary, previewDocument, serializeMaterialsSpec, serializeExercisesSpec
} from './generation.models';
import {
    artifactDetailWithNote, clone, errorsContract, eventsContract, examples, httpContract, ids, noteArchiveAnswer, noteIds, problemResponse,
    sessionWithNotes, statesContract, usageContract
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
                'editArtifact', 'revertArtifact', 'cancelSession', 'deleteSession', 'approvePlan']);
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
            // `archiveUsedNotes` (#290) joins http.json with the backend; until then it cannot be looked up there.
            const implemented = ['getCapabilities', 'estimateGeneration', 'createSession', 'listSessions', 'listActiveSessions', 'getSession',
                'cancelSession', 'deleteSession', 'listEvents', 'getArtifact', 'approveArtifact', 'approveArtifacts', 'rejectArtifact',
                'undoRejectArtifact', 'handoffArtifact', 'retryArtifact', 'archiveUsedNotes', 'editArtifact', 'revertArtifact', 'selectMediaCandidate', 'createIntent', 'approvePlan'];
            const ids = (httpContract['endpoints'] as { operationId: string }[]).map(endpoint => endpoint.operationId);
            for (const operation of implemented) expect(ids, operation).toContain(operation);
            expect(ids.filter(operation => !implemented.includes(operation)).sort()).toEqual([]);
        });

        it('knows every problem code the contract lists for the operations it calls', () => {
            const codes = Object.keys(errorsContract['codes']);
            for (const code of ['GENERATION_STATE_CONFLICT', 'USAGE_LIMIT_REACHED', 'CAPABILITY_UNAVAILABLE', 'RESOURCE_LIMIT_EXCEEDED', 'VERSION_CONFLICT']) {
                expect(codes).toContain(code);
            }
        });
    });
    describe('notes as sources (#290: overrides, note status, archival)', () => {
        const note = (overrides?: NoteOverrides, role: 'SOURCE' | 'STYLE_EXAMPLE' = 'SOURCE', id = noteIds.first): SpecSource =>
            ({ role, type: 'NOTE', noteId: id, noteRowVersion: '3', ...(overrides === undefined ? {} : { overrides }) });
        const sourcesOf = (spec: ReturnType<typeof serializeMaterialsSpec>) => spec['sources'] as Record<string, unknown>[];

        it('parses the required notes counters of a session', () => {
            expect(parseSessionDetail(sessionWithNotes({ used: 4, archivable: 3 })).notes).toEqual({ used: 4, archivable: 3 });
            expect(parseSessionDetail(sessionWithNotes({ used: 0, archivable: 0 })).notes).toEqual({ used: 0, archivable: 0 });
            expect(() => parseSessionDetail(sessionWithNotes({ used: 1, archivable: 2 }))).toThrow(AuthoringProtocolError);
            expect(() => parseSessionDetail(sessionWithNotes({ used: 1, archivable: 1, extra: 1 } as never))).toThrow(AuthoringProtocolError);
            expect(() => parseSessionDetail(sessionWithNotes(null, null, { notes: null }))).toThrow(AuthoringProtocolError);
            const { notes: _omitted, ...withoutNotes } = examples['sessionDetail'];
            expect(() => parseSessionDetail(withoutNotes)).toThrow(AuthoringProtocolError);
        });

        it('reads the research of an artifact strictly (#299): absent or null is none, https only, numbered in order', () => {
            const item = (research?: unknown): Record<string, unknown> => ({ ...clone(examples['artifactDetailItem']), ...(research === undefined ? {} : { research }) });
            const result = (n: number, url = `https://example.org/${n}`): Record<string, unknown> => ({ n, url, title: `Источник ${n}`, provider: 'YANDEX' });
            expect(parseArtifactDetail(item()).research).toBeNull();
            expect(parseArtifactDetail(item(null)).research).toBeNull();
            expect(parseArtifactDetail(item({ requests: 2, results: [result(1), result(2)] })).research).toEqual({ requests: 2,
                results: [{ n: 1, url: 'https://example.org/1', title: 'Источник 1', provider: 'YANDEX' }, { n: 2, url: 'https://example.org/2', title: 'Источник 2', provider: 'YANDEX' }] });
            expect(parseArtifactDetail(item({ requests: 0, results: [] })).research).toEqual({ requests: 0, results: [] });
            for (const bad of [
                { requests: 1, results: [result(1, 'http://example.org/1')] }, { requests: 1, results: [result(1, 'https://user:pw@example.org/')] },
                { requests: 1, results: [result(1, 'javascript:alert(1)')] }, { requests: 1, results: [result(2)] }, { requests: -1, results: [] },
                { requests: 1, results: [{ ...result(1), extra: true }] }, { requests: 1 }, { requests: 1, results: Array.from({ length: 31 }, (_, i) => result(i + 1)) }
            ]) {
                expect(() => parseArtifactDetail(item(bad)), JSON.stringify(bad)).toThrow(AuthoringProtocolError);
            }
        });

        it('reads the status of the pinned note of an artifact, and tolerates its absence and a status it does not know', () => {
            expect(parseArtifactDetail(examples['artifactDetailItem']).noteSources).toEqual([{ noteId: '20700000-0000-4000-8000-000000000001', noteRowVersion: '3', status: 'CURRENT' }]);
            expect(parseArtifactDetail(examples['artifactDetailExercise']).noteSources).toEqual([]);
            for (const status of ['CURRENT', 'CHANGED', 'ARCHIVED', 'DELETED']) {
                expect(parseArtifactDetail(artifactDetailWithNote(status)).noteSources).toEqual([{ noteId: noteIds.first, noteRowVersion: '3', status }]);
            }
            expect(parseArtifactDetail(artifactDetailWithNote(null)).noteSources[0]?.status).toBeNull();
            expect(parseArtifactDetail(artifactDetailWithNote('SOMETHING_NEW')).noteSources[0]?.status).toBeNull();
            const mixed = artifactDetailWithNote('CHANGED');
            mixed['sourceRefs'].push({ type: 'ITEM', memberKey: ids.first, itemRevisionId: ids.revision });
            expect(parseArtifactDetail(mixed).noteSources).toHaveLength(1);
            const broken = artifactDetailWithNote('CHANGED');
            broken['sourceRefs'][0].noteId = 'not-an-id';
            expect(() => parseArtifactDetail(broken)).toThrow(AuthoringProtocolError);
        });

        it('serializes sparse overrides exactly as the contract states them, and never an empty object', () => {
            const settings = { ...DEFAULT_SETTINGS, effort: 'MEDIUM' as const };
            const built = (...sources: SpecSource[]) => serializeMaterialsSpec({ ...buildMaterialsSpec('', settings, [], { image: true, audio: true }), sources });
            const full: NoteOverrides = { effort: 'DETAILED', media: { audio: { enabled: true, lang: 'ko', voice: null }, imageSearch: false } };
            expect(sourcesOf(built(note(full)))[0]).toEqual({ role: 'SOURCE', type: 'NOTE', noteId: noteIds.first, noteRowVersion: '3', overrides: full });
            expect(sourcesOf(built(note({ effort: 'SHORT' })))[0]!['overrides']).toEqual({ effort: 'SHORT' });
            expect(sourcesOf(built(note({ media: { imageSearch: true } })))[0]!['overrides']).toEqual({ media: { imageSearch: true } });
            expect(sourcesOf(built(note(undefined)))[0]).not.toHaveProperty('overrides');
            expect(sourcesOf(built(note({})))[0]).not.toHaveProperty('overrides');
            expect(sourcesOf(built(note({ media: {} })))[0]).not.toHaveProperty('overrides');
            expect(JSON.stringify(built(note({}), note({ media: {} }, 'SOURCE', noteIds.second)))).not.toContain('overrides');
        });

        it('sends the sources of the contract example unchanged, and reads its echo (overrides are not part of the echo the UI keeps)', () => {
            const example = examples['specMaterialsOverrides'];
            const sent = serializeMaterialsSpec({ ...buildMaterialsSpec('', DEFAULT_SETTINGS, [], { image: true, audio: true }),
                sources: example.sources.map((source: any) => note(source.overrides, 'SOURCE', source.noteId)).map((source: SpecSource, index: number) =>
                    ({ ...source, noteRowVersion: example.sources[index].noteRowVersion } as SpecSource)) });
            expect(sourcesOf(sent)).toEqual(example.sources);
            const echoed = parseSessionDetail({ ...examples['sessionDetail'], spec: example });
            expect(echoed.spec).toMatchObject({ kind: 'MATERIALS', outputLanguage: 'ru' });
        });

        it('refuses overrides where they do not apply: a style example, or any mode but ONE_PER_NOTE', () => {
            const base = buildMaterialsSpec('', DEFAULT_SETTINGS, [], { image: true, audio: true });
            expect(() => serializeMaterialsSpec({ ...base, sources: [note({ effort: 'SHORT' }, 'STYLE_EXAMPLE')] })).toThrow(AuthoringProtocolError);
            expect(() => serializeMaterialsSpec({ ...base, settings: { ...base.settings, notesMode: 'MERGE_INTO_ONE' },
                sources: [note({ effort: 'SHORT' })] })).toThrow(AuthoringProtocolError);
            expect(() => serializeMaterialsSpec({ ...base, settings: { ...base.settings, notesMode: 'MERGE_INTO_ONE' },
                sources: [note(undefined), note(undefined, 'SOURCE', noteIds.second)] })).not.toThrow();
        });

        it('parses the archival answer with archived and skipped notes, and an empty one', () => {
            expect(parseNoteArchive(noteArchiveAnswer([noteIds.first, noteIds.second], [{ noteId: noteIds.third, reason: 'CHANGED' }]), false)).toEqual({
                archived: [noteIds.first, noteIds.second], skipped: [{ noteId: noteIds.third, reason: 'CHANGED' }], replayed: false });
            expect(parseNoteArchive(noteArchiveAnswer([]), true)).toEqual({ archived: [], skipped: [], replayed: true });
            for (const reason of ['ALREADY_ARCHIVED', 'DELETED']) {
                expect(parseNoteArchive(noteArchiveAnswer([], [{ noteId: noteIds.first, reason }]), false).skipped[0]?.reason).toBe(reason);
            }
            expect(() => parseNoteArchive(noteArchiveAnswer([], [{ noteId: noteIds.first, reason: 'NOPE' }]), false)).toThrow(AuthoringProtocolError);
            expect(() => parseNoteArchive({ archived: [] }, false)).toThrow(AuthoringProtocolError);
        });
    });
});

const problemResponseOf = (body: Record<string, unknown>) => new HttpErrorResponse({ status: 422, error: body });

describe('Exercise generation wire contract (contracts/generation, AI-13 #291)', () => {
    it('sends exactly the generationSpec.EXERCISES example for the same choices', () => {
        const example = examples['specExercises'];
        const spec = buildExercisesSpec(example.targets, { ...DEFAULT_BUILDER_VALUE, quantityMode: 'EXACT', perTarget: 3 }, example.outputLanguage);
        expect(serializeExercisesSpec(spec)).toEqual(example);
    });

    it('reads the exercise detail example: the stored command, the display and the quotes of the pinned revision', () => {
        const detail = parseArtifactDetail(examples['artifactDetailExercise']);
        expect(detail.display).toEqual(examples['artifactDetailExercise'].display);
        const proposal = readProposal(detail)!;
        expect(proposal.mechanic).toBe(detail.display!.mechanic);
        expect(proposal.objectiveTitle).toBe(detail.display!.objectiveTitle);
        // Every MATERIAL block of the stored exercise has its quote, so the card renders without another request.
        const blocks = JSON.stringify(proposal.exercise.content).match(/"nodeId":"[0-9a-f-]{36}"/gu) ?? [];
        for (const block of blocks) expect(Object.keys(proposal.quotes)).toContain(block.slice(10, -1));
    });

    it('reads the exercise acknowledgements: single, and a bulk answer that may mix both kinds', () => {
        const single = parseApprovalAck(examples['approvalAckExercise'], false);
        expect(single.artifacts[0]!.publishedRef.kind).toBe('EXERCISE');
        const bulk = parseApprovalAck(examples['approvalAckBulk'], false);
        expect(bulk.artifacts.length).toBeGreaterThan(0);
    });

    it('documents the optional replacement on the approval request and nowhere else', () => {
        const endpoints = httpContract['endpoints'] as { operationId: string; requestBodyOptional?: Record<string, string> }[];
        const approve = endpoints.find(endpoint => endpoint.operationId === 'approveArtifact')!;
        expect(Object.keys(approve.requestBodyOptional ?? {})).toEqual(['replacement']);
        expect(endpoints.find(endpoint => endpoint.operationId === 'approveArtifacts')?.requestBodyOptional?.['replacement']).toBeUndefined();
    });

    it('limits what the contract says: 20 targets, 10 per target, 60 per session', () => {
        const limit = (errorsContract['codes'].RESOURCE_LIMIT_EXCEEDED.example as Record<string, unknown>)['limits'];
        expect(limit).toEqual({ maxExerciseTargets: 20, maxExercisesPerTarget: 10, maxExercisesPerSession: 60 });
        expect(readProblem(problemResponseOf({ code: 'RESOURCE_LIMIT_EXCEEDED', limit: 'EXERCISES_PER_SESSION', limits: limit })).limits).toEqual(limit);
    });

    describe('the plan of a plan-first session (AI-14, #295, decision 17)', () => {
        it('reads the session before and after the launch with its plan, and every other session with `plan: null`', () => {
            for (const name of ['sessionDetail', 'sessionDetailCreated', 'sessionDetailCancelled']) expect(parseSessionDetail(examples[name]).plan, name).toBeNull();
            const ready = parseSessionDetail(examples['sessionDetailPlanReady']);
            expect(ready).toMatchObject({ state: 'PLAN_READY', artifacts: [], usage: { reservedCredits: 10, spentCredits: 20 } });
            expect(ready.plan).toMatchObject({ kind: 'EXERCISES', approved: false });
            const approved = parseSessionDetail(examples['sessionDetailPlanApproved']);
            expect(approved).toMatchObject({ state: 'RUNNING', rowVersion: '4' });
            expect(approved.plan).toMatchObject({ approved: true });
            expect(approved.artifacts).toHaveLength(approved.plan!.totals.artifacts);
        });

        it('does not read a session without the plan member: the key is part of the contract', () => {
            const body = clone(examples['sessionDetail']);
            delete body['plan'];
            expect(() => parseSessionDetail(body)).toThrow(AuthoringProtocolError);
        });

        it('asks for the plan the contract way: planFirst goes on the wire of both specs, and is false unless it was asked for', () => {
            const materials = serializeMaterialsSpec(buildMaterialsSpec('Объясни', { ...DEFAULT_SETTINGS, planFirst: true }, [], { image: false, audio: false }));
            expect((materials['settings'] as Record<string, unknown>)['planFirst']).toBe(true);
            expect((serializeMaterialsSpec(buildMaterialsSpec('Объясни', DEFAULT_SETTINGS, [], { image: false, audio: false }))['settings'] as Record<string, unknown>)['planFirst']).toBe(false);
            const target = { memberKey: ids.first, itemRevisionId: ids.revision, title: 'x', exerciseCount: null };
            const planned = serializeExercisesSpec(buildExercisesSpec([target], { ...DEFAULT_BUILDER_VALUE, planFirst: true }));
            expect((planned['settings'] as Record<string, unknown>)['planFirst']).toBe(true);
            expect((serializeExercisesSpec(buildExercisesSpec([target], DEFAULT_BUILDER_VALUE))['settings'] as Record<string, unknown>)['planFirst']).toBe(false);
        });

        it('reads the plan line of the estimate apart from the batch, and nothing when there is none', () => {
            const planned = { ...clone(usageContract['estimateResponse']), breakdown: [{ operation: 'SMART_PLAN_FLASH', count: 1, credits: 20 },
                ...clone(usageContract['estimateResponse']).breakdown] };
            expect(parseEstimate(planned).planCredits).toBe(20);
            expect(parseEstimate(usageContract['estimateResponse']).planCredits).toBeNull();
        });

        it('names the new refusal buckets and limits the contract lists for the plan', () => {
            const approve = (httpContract['endpoints'] as { operationId: string; errors: { status: number; code: string; limit?: string }[] }[]).find(endpoint => endpoint.operationId === 'approvePlan')!;
            // Every refusal the plan editor words (see `describePlanProblem`) is one the contract lists, and nothing else is a surprise to it.
            expect(approve.errors.map(error => error.code).sort()).toEqual(['CAPABILITY_UNAVAILABLE', 'GENERATION_STATE_CONFLICT', 'IDEMPOTENCY_CONFLICT', 'INVALID_REQUEST',
                'RESOURCE_LIMIT_EXCEEDED', 'RESOURCE_NOT_FOUND', 'USAGE_LIMIT_REACHED', 'VERSION_CONFLICT']);
            expect(approve.errors.find(error => error.status === 422)!.limit).toBe('EXERCISES_PER_TARGET | EXERCISES_PER_SESSION | ARTIFACTS_PER_SESSION');
        });
    });
});
