import { HttpClient, HttpHeaders, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, defer, map } from 'rxjs';

import { appConfig } from '../../app.config';
import {
    AttemptCommand,
    AttemptFeedback,
    AttemptOutcome,
    PreparingStudySession,
    ReadyStudySession,
    StudyBinding,
    StudyExerciseType,
    StudyMode,
    StudyPresentation,
    StudyProtocolError,
    StudySession,
    StudyWriteResult
} from './study.models';

@Injectable({ providedIn: 'root' })
export class StudyApiService {
    private readonly http = inject(HttpClient);
    private readonly baseUrl = appConfig.learningApiBaseUrl.replace(/\/$/, '');

    start(deckId: string, commandId: string): Observable<StudyWriteResult<StudySession>> {
        return defer(() => {
            const deck = entity(deckId);
            const command = commandIdValue(commandId);
            return this.http.post<unknown>(`${this.baseUrl}/decks/${deck}/study-sessions`, {
                commandId: command, mode: 'SCHEDULED', budget: { maxPresentations: 20 }
            }, { observe: 'response' });
        }).pipe(map(response => {
            if (response.status !== 201 && response.status !== 202) throw protocol('Unexpected session status.');
            privateResponse(response);
            const value = parseSession(response.body, deckId);
            const replayed = replayHeader(response.headers);
            const location = response.headers.get('Location');
            const expected = `/api/decks/${deckId.toLowerCase()}/study-sessions/${value.sessionId}`;
            if (location !== expected) throw protocol('Invalid Study session location.');
            return { value, replayed };
        }));
    }

    read(deckId: string, sessionId: string): Observable<StudySession> {
        return defer(() => this.http.get<unknown>(
            `${this.baseUrl}/decks/${entity(deckId)}/study-sessions/${entity(sessionId)}`,
            { observe: 'response' }
        )).pipe(map(response => {
            if (response.status !== 200) throw protocol('Unexpected session read status.');
            privateResponse(response);
            return parseSession(response.body, deckId, sessionId);
        }));
    }

    submit(deckId: string, sessionId: string, command: AttemptCommand): Observable<StudyWriteResult<AttemptOutcome>> {
        return defer(() => {
            validateCommand(command);
            return this.http.post<unknown>(
                `${this.baseUrl}/decks/${entity(deckId)}/study-sessions/${entity(sessionId)}/attempts`,
                command, { observe: 'response' }
            );
        }).pipe(map(response => {
            if (response.status !== 200) throw protocol('Unexpected attempt status.');
            privateResponse(response);
            const value = parseOutcome(response.body);
            if (value.attemptId !== command.attemptId || value.presentationId !== command.presentationId) {
                throw protocol('Attempt acknowledgement mismatch.');
            }
            return { value, replayed: replayHeader(response.headers) };
        }));
    }
}

function parseSession(value: unknown, expectedDeck: string, expectedSession?: string): StudySession {
    if (isRecord(value) && value['status'] === 'PREPARING') {
        const object = exact(value, ['sessionId', 'mode', 'status', 'statusUrl']);
        const session: PreparingStudySession = {
            sessionId: entity(object['sessionId']), mode: mode(object['mode']), status: 'PREPARING',
            statusUrl: text(object['statusUrl'], 2048)
        };
        identity(session.sessionId, expectedSession);
        const expectedUrl = `/api/decks/${expectedDeck.toLowerCase()}/study-sessions/${session.sessionId}`;
        if (session.statusUrl !== expectedUrl) throw protocol('Invalid preparation status URL.');
        return session;
    }
    const object = exact(value, [
        'sessionId', 'deckId', 'mode', 'status', 'timezone', 'localStudyDate', 'deckRevisionId',
        'exerciseGenerationId', 'selectionPolicyVersion', 'seed', 'expiresAt', 'reducer', 'nextCursor', 'presentations'
    ]);
    const status = object['status'];
    if (status !== 'ACTIVE' && status !== 'EMPTY' && status !== 'COMPLETE') throw protocol('Invalid session status.');
    const reducer = exact(object['reducer'], ['id', 'version', 'configId', 'configHash']);
    if (!Array.isArray(object['presentations']) || object['presentations'].length > 20) {
        throw protocol('Invalid presentation page.');
    }
    const session: ReadyStudySession = {
        sessionId: entity(object['sessionId']), deckId: entity(object['deckId']), mode: mode(object['mode']), status,
        timezone: text(object['timezone'], 80), localStudyDate: localDate(object['localStudyDate']),
        deckRevisionId: entity(object['deckRevisionId']), exerciseGenerationId: entity(object['exerciseGenerationId']),
        selectionPolicyVersion: text(object['selectionPolicyVersion'], 100),
        reducer: { id: text(reducer['id'], 100), version: text(reducer['version'], 100),
            configId: entity(reducer['configId']), configHash: hash(reducer['configHash']) },
        seed: unsigned(object['seed']), nextCursor: cursor(object['nextCursor']), expiresAt: instant(object['expiresAt']),
        presentations: object['presentations'].map(parsePresentation)
    };
    identity(session.sessionId, expectedSession);
    if (session.deckId !== expectedDeck.toLowerCase()) throw protocol('Session deck mismatch.');
    if (session.status !== 'ACTIVE' && session.presentations.length !== 0) throw protocol('Terminal session has presentations.');
    if (new Set(session.presentations.map(item => item.presentationId)).size !== session.presentations.length) {
        throw protocol('Duplicate presentation identity.');
    }
    return session;
}

function parsePresentation(value: unknown): StudyPresentation {
    const object = exact(value, [
        'presentationId', 'nonce', 'ordinal', 'exerciseRevisionId', 'type', 'objectiveId', 'objectiveRevisionId',
        'learningEpoch', 'reference', 'prompt', 'options', 'bindings', 'evaluator'
    ]);
    const prompt = exact(object['prompt'], ['kind', 'text']);
    if (prompt['kind'] !== 'TEXT') throw protocol('Invalid prompt projection.');
    const evaluator = exact(object['evaluator'], ['id', 'version']);
    if (!Array.isArray(object['options']) || object['options'].length > 6 || !Array.isArray(object['bindings'])
        || object['bindings'].length < 1 || object['bindings'].length > 16) throw protocol('Invalid presentation content.');
    const options = object['options'].map(option => {
        const item = exact(option, ['optionId', 'text']);
        return { optionId: entity(item['optionId']), text: text(item['text'], 1024) };
    });
    const bindings = object['bindings'].map(parseBinding);
    if (bindings.filter(binding => binding.role === 'ASSESSED').length !== 1) throw protocol('Invalid assessed binding.');
    const type = exerciseType(object['type']);
    const optionBindings = bindings.filter(binding => binding.role === 'OPTION');
    if (type === 'SINGLE_CHOICE') {
        const optionIds = new Set(options.map(option => option.optionId));
        if (options.length < 2 || options.length > 6 || optionIds.size !== options.length
            || optionBindings.length !== options.length
            || optionBindings.some(binding => !optionIds.has(binding.bindingId))) {
            throw protocol('Invalid choice options.');
        }
    } else if (options.length !== 0 || optionBindings.length !== 0) throw protocol('Unexpected choice options.');
    return {
        presentationId: entity(object['presentationId']), nonce: text(object['nonce'], 100, 16),
        ordinal: count(object['ordinal'], 99), exerciseRevisionId: entity(object['exerciseRevisionId']),
        type, objectiveId: entity(object['objectiveId']),
        objectiveRevisionId: entity(object['objectiveRevisionId']), learningEpoch: unsigned(object['learningEpoch']),
        reference: text(object['reference'], 4096, 0), prompt: { kind: 'TEXT', text: text(prompt['text'], 4096, 0) },
        options, bindings, evaluator: { id: text(evaluator['id'], 100), version: text(evaluator['version'], 100) }
    };
}

function parseBinding(value: unknown): StudyBinding {
    const object = exact(value, ['bindingId', 'role', 'memberKey', 'itemRevisionId', 'ordinal', 'nodeIds', 'display']);
    const role = object['role'];
    if (role !== 'ASSESSED' && role !== 'CUE' && role !== 'OPTION' && role !== 'CONTEXT') throw protocol('Invalid binding role.');
    if (!Array.isArray(object['nodeIds']) || object['nodeIds'].length > 16) throw protocol('Invalid binding nodes.');
    const display = exact(object['display'], ['kind']);
    return { bindingId: entity(object['bindingId']), role, memberKey: entity(object['memberKey']),
        itemRevisionId: entity(object['itemRevisionId']), ordinal: count(object['ordinal'], 15),
        nodeIds: object['nodeIds'].map(entity), display: { kind: text(display['kind'], 100) } };
}

function parseOutcome(value: unknown): AttemptOutcome {
    if (!isRecord(value)) throw protocol('Invalid attempt outcome.');
    const practice = 'canonicalEffects' in value;
    const object = exact(value, practice
        ? ['attemptId', 'presentationId', 'mode', 'status', 'evidence', 'feedback', 'transition', 'canonicalEffects']
        : ['attemptId', 'presentationId', 'mode', 'status', 'evidence', 'feedback', 'transition']);
    const status = object['status'];
    if (status !== 'ASSESSED' && status !== 'NOT_ASSESSED' && status !== 'UNAVAILABLE') throw protocol('Invalid outcome status.');
    const parsedMode = mode(object['mode']);
    if (practice && (object['canonicalEffects'] !== false || parsedMode === 'SCHEDULED')) throw protocol('Invalid practice effect.');
    const transition = object['transition'] === null ? null : parseTransition(object['transition']);
    if (status === 'ASSESSED' && parsedMode === 'SCHEDULED' && transition === null) throw protocol('Missing transition.');
    parseEvidence(object['evidence'], status, parsedMode);
    return { attemptId: commandIdValue(object['attemptId']), presentationId: entity(object['presentationId']),
        mode: parsedMode, status, feedback: parseFeedback(object['feedback']),
        canonicalEffects: practice ? false : parsedMode === 'SCHEDULED', transition };
}

function parseEvidence(value: unknown, status: AttemptOutcome['status'], parsedMode: StudyMode): void {
    if (status !== 'ASSESSED' || parsedMode !== 'SCHEDULED') {
        if (value !== null) throw protocol('Unexpected canonical evidence.');
        return;
    }
    const object = exact(value, ['objectiveId', 'objectiveRevisionId', 'result', 'evidenceClass', 'reasonCodes']);
    entity(object['objectiveId']); entity(object['objectiveRevisionId']);
    if (!['CORRECT', 'PARTIAL', 'UNSURE', 'INCORRECT'].includes(String(object['result']))
        || !['HIGH', 'MEDIUM', 'LOW'].includes(String(object['evidenceClass']))
        || !Array.isArray(object['reasonCodes']) || object['reasonCodes'].length > 20
        || object['reasonCodes'].some(item => typeof item !== 'string')) throw protocol('Invalid canonical evidence.');
}

function parseFeedback(value: unknown): AttemptFeedback {
    if (!isRecord(value) || typeof value['result'] !== 'string') throw protocol('Invalid feedback.');
    const result = value['result'];
    if (!['CORRECT', 'PARTIAL', 'UNSURE', 'INCORRECT', 'NOT_ASSESSED', 'UNAVAILABLE'].includes(result)) {
        throw protocol('Invalid feedback result.');
    }
    const allowed = result === 'NOT_ASSESSED' ? ['result']
        : result === 'UNAVAILABLE' ? ['result', 'reasonCodes']
            : ('reference' in value ? ['result', 'reference', 'appliedRules'] : ['result', 'appliedRules']);
    const object = exact(value, allowed);
    const strings = (field: string): readonly string[] => {
        const values = object[field] ?? [];
        if (!Array.isArray(values) || values.length > 20 || values.some(item => typeof item !== 'string')) {
            throw protocol('Invalid feedback details.');
        }
        return values as string[];
    };
    return { result: result as AttemptFeedback['result'],
        reference: 'reference' in object ? text(object['reference'], 4096, 0) : null,
        appliedRules: strings('appliedRules'), reasonCodes: strings('reasonCodes') };
}

function parseTransition(value: unknown): AttemptOutcome['transition'] {
    const object = exact(value, ['learningEpoch', 'sequence', 'beforeLevel', 'afterLevel', 'acceptedAt', 'nextDue',
        'reducerId', 'reducerVersion', 'configId', 'configHash']);
    unsigned(object['learningEpoch']); unsigned(object['sequence']); instant(object['acceptedAt']);
    text(object['reducerId'], 100); text(object['reducerVersion'], 100); entity(object['configId']); hash(object['configHash']);
    return { beforeLevel: count(object['beforeLevel'], 7), afterLevel: count(object['afterLevel'], 7),
        nextDue: instant(object['nextDue']) };
}

function validateCommand(command: AttemptCommand): void {
    commandIdValue(command.attemptId); entity(command.presentationId); text(command.nonce, 100, 16);
    if (!Array.isArray(command.hintsUsed) || command.hintsUsed.length > 8
        || command.hintsUsed.some(value => typeof value !== 'string' || value.length === 0 || value.length > 64)
        || !Number.isSafeInteger(command.durationMs) || command.durationMs < 0 || command.durationMs > 3_600_000) {
        throw protocol('Invalid attempt command.');
    }
    const response = command.response;
    if (response.kind === 'TEXT') text(response.text, 4096, 0);
    else if (response.kind === 'SELF_CHECK') {
        if (!['NOT_RECALLED', 'HINTED', 'PARTIAL', 'FULL'].includes(response.rating)) throw protocol('Invalid rating.');
    } else if (response.kind === 'CHOICE') entity(response.optionId);
    else if (response.kind !== 'CANCEL') throw protocol('Invalid response kind.');
    if (command.confidence !== null && !['KNEW', 'UNSURE', 'GUESSED'].includes(command.confidence)) {
        throw protocol('Invalid confidence.');
    }
}

function privateResponse(response: HttpResponse<unknown>): void {
    const directives = (response.headers.get('Cache-Control') ?? '').toLowerCase().split(',').map(value => value.trim());
    if (!directives.includes('private') || !directives.includes('no-store')) throw protocol('Study response can be cached.');
}

function replayHeader(headers: HttpHeaders): boolean {
    const value = headers.get('Idempotency-Replayed');
    if (value !== null && value !== 'true') throw protocol('Invalid replay header.');
    return value === 'true';
}

function exact(value: unknown, keys: readonly string[]): Record<string, unknown> {
    if (!isRecord(value)) throw protocol('Expected Study object.');
    const actual = Object.keys(value);
    if (actual.length !== keys.length || actual.some(key => !keys.includes(key))) throw protocol('Unexpected Study shape.');
    return value;
}
function isRecord(value: unknown): value is Record<string, unknown> { return typeof value === 'object' && value !== null && !Array.isArray(value); }
function protocol(message: string): StudyProtocolError { return new StudyProtocolError(message); }
function entity(value: unknown): string { if (typeof value !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u.test(value)) throw protocol('Invalid entity ID.'); return value.toLowerCase(); }
function commandIdValue(value: unknown): string { return entity(value); }
function text(value: unknown, maximum: number, minimum = 1): string { if (typeof value !== 'string' || value.length < minimum || new TextEncoder().encode(value).length > maximum) throw protocol('Invalid Study text.'); return value; }
function count(value: unknown, maximum: number): number { if (!Number.isSafeInteger(value) || (value as number) < 0 || (value as number) > maximum) throw protocol('Invalid count.'); return value as number; }
function unsigned(value: unknown): string { if (typeof value !== 'string' || !/^(0|[1-9]\d{0,19})$/u.test(value)) throw protocol('Invalid unsigned value.'); return value; }
function instant(value: unknown): string { const result = text(value, 64); if (!/^\d{4}-\d{2}-\d{2}T/u.test(result) || !Number.isFinite(Date.parse(result))) throw protocol('Invalid instant.'); return result; }
function localDate(value: unknown): string { const result = text(value, 10); if (!/^\d{4}-\d{2}-\d{2}$/u.test(result)) throw protocol('Invalid local date.'); return result; }
function hash(value: unknown): string { const result = text(value, 80); if (!/^sha256:[0-9a-f]{64}$/u.test(result)) throw protocol('Invalid config hash.'); return result; }
function cursor(value: unknown): string | null { if (value === null) return null; return text(value, 4096); }
function mode(value: unknown): StudyMode { if (value !== 'SCHEDULED' && value !== 'REPLAY' && value !== 'PRACTICE') throw protocol('Invalid Study mode.'); return value; }
function exerciseType(value: unknown): StudyExerciseType { if (value !== 'SELF_CHECK' && value !== 'TYPED' && value !== 'CLOZE_SINGLE' && value !== 'SINGLE_CHOICE') throw protocol('Invalid exercise type.'); return value; }
function identity(actual: string, expected?: string): void { if (expected !== undefined && actual !== expected.toLowerCase()) throw protocol('Session identity mismatch.'); }
