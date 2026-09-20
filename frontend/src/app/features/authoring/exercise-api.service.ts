import { HttpClient, HttpHeaders, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, defer, map } from 'rxjs';

import { appConfig } from '../../app.config';
import { expectedEtag } from '../own-decks/own-deck.models';
import {
    AuthoringProtocolError, newCommandId, requireCommand, requireCount, requireCursor, requireEntity, requireInstant,
    requireObject, requireVersion
} from './authoring.models';
import {
    AnswerContract, BindingRole, EXERCISE_PAGE_SIZE, ExerciseAcknowledgement, ExerciseBinding, ExerciseDetail,
    ExerciseObjective, ExercisePage, ExercisePrompt, ExerciseSummary, ExerciseType, ExerciseWriteResult
} from './exercise.models';

@Injectable({ providedIn: 'root' })
export class ExerciseApiService {
    private readonly http = inject(HttpClient);
    private readonly baseUrl = appConfig.learningApiBaseUrl.replace(/\/$/, '');

    list(deckId: string, memberKey: string, cursor: string | null = null): Observable<ExercisePage> {
        return defer(() => {
            const deck = requireEntity(deckId);
            let params = new HttpParams().set('memberKey', requireEntity(memberKey))
                .set('limit', EXERCISE_PAGE_SIZE.toString());
            if (cursor !== null) params = params.set('cursor', requireCursor(cursor)!);
            return this.http.get<unknown>(`${this.baseUrl}/decks/${encodeURIComponent(deck)}/exercises`, {
                params, observe: 'response'
            });
        }).pipe(map(response => {
            requirePrivate(response);
            const page = parsePage(response.body);
            requireEtag(response, page.deckVersion);
            if (page.deckId !== deckId.toLowerCase()
                || page.exercises.some(value => value.objective.memberKey !== memberKey.toLowerCase())) {
                throw new AuthoringProtocolError('Exercise page scope mismatch.');
            }
            return page;
        }));
    }

    read(deckId: string, exerciseId: string): Observable<ExerciseDetail> {
        return defer(() => this.http.get<unknown>(
            `${this.baseUrl}/decks/${encodeURIComponent(requireEntity(deckId))}/exercises/${encodeURIComponent(requireEntity(exerciseId))}`,
            { observe: 'response' }
        )).pipe(map(response => {
            requirePrivate(response);
            const detail = parseDetail(response.body);
            requireEtag(response, detail.deckVersion);
            if (detail.deckId !== deckId.toLowerCase() || detail.exerciseId !== exerciseId.toLowerCase()) {
                throw new AuthoringProtocolError('Exercise identity mismatch.');
            }
            return detail;
        }));
    }

    create(deckId: string, deckVersion: string, deckRevisionId: string,
           objective: Record<string, unknown>, exercise: Record<string, unknown>, commandId = newCommandId()): Observable<ExerciseWriteResult> {
        return this.write('POST', deckId, null, deckVersion, {
            commandId: requireCommand(commandId), expectedDeckRevisionId: requireEntity(deckRevisionId), objective, exercise
        }, 201);
    }

    update(deckId: string, exerciseId: string, deckVersion: string, deckRevisionId: string,
           exerciseRevisionId: string, objective: Record<string, unknown>, exercise: Record<string, unknown>,
           commandId = newCommandId()): Observable<ExerciseWriteResult> {
        return this.write('PUT', deckId, exerciseId, deckVersion, {
            commandId: requireCommand(commandId), expectedDeckRevisionId: requireEntity(deckRevisionId),
            expectedExerciseRevisionId: requireEntity(exerciseRevisionId), objective, exercise
        }, 200);
    }

    private write(method: 'POST' | 'PUT', deckId: string, exerciseId: string | null, deckVersion: string,
                  body: Record<string, unknown>, status: number): Observable<ExerciseWriteResult> {
        return defer(() => {
            const deck = requireEntity(deckId);
            const suffix = exerciseId === null ? '' : `/${encodeURIComponent(requireEntity(exerciseId))}`;
            return this.http.request<unknown>(method,
                `${this.baseUrl}/decks/${encodeURIComponent(deck)}/exercises${suffix}`, {
                    body, headers: new HttpHeaders({ 'If-Match': expectedEtag(deckVersion) }), observe: 'response'
                });
        }).pipe(map(response => parseWrite(response, status, body['commandId'] as string, deckId)));
    }
}

function parsePage(value: unknown): ExercisePage {
    const object = requireObject(value, ['deckId', 'deckRevisionId', 'deckVersion', 'total', 'exercises', 'nextCursor']);
    if (!Array.isArray(object['exercises']) || object['exercises'].length > EXERCISE_PAGE_SIZE) {
        throw new AuthoringProtocolError('Invalid exercise page.');
    }
    const exercises = object['exercises'].map(parseSummary);
    if (new Set(exercises.map(entry => entry.exerciseId)).size !== exercises.length) {
        throw new AuthoringProtocolError('Duplicate exercise.');
    }
    return {
        deckId: requireEntity(object['deckId']), deckRevisionId: requireEntity(object['deckRevisionId']),
        deckVersion: requireVersion(object['deckVersion']), total: requireCount(object['total'], 100_000),
        exercises, nextCursor: requireCursor(object['nextCursor'])
    };
}

function parseSummary(value: unknown): ExerciseSummary {
    const object = requireObject(value, [
        'exerciseId', 'exerciseRevisionId', 'exerciseVersion', 'ordinal', 'type', 'enabled', 'schemaVersion',
        'createdAt', 'updatedAt', 'objective'
    ]);
    if (object['schemaVersion'] !== 1) throw new AuthoringProtocolError('Unsupported exercise schema.');
    return {
        exerciseId: requireEntity(object['exerciseId']), exerciseRevisionId: requireEntity(object['exerciseRevisionId']),
        exerciseVersion: requireVersion(object['exerciseVersion']), ordinal: requireCount(object['ordinal'], 99_999),
        type: exerciseType(object['type']), enabled: boolean(object['enabled']), schemaVersion: 1,
        createdAt: requireInstant(object['createdAt']), updatedAt: requireInstant(object['updatedAt']),
        objective: parseObjective(object['objective'])
    };
}

function parseDetail(value: unknown): ExerciseDetail {
    const object = requireObject(value, [
        'exerciseId', 'exerciseRevisionId', 'exerciseVersion', 'ordinal', 'type', 'enabled', 'schemaVersion',
        'createdAt', 'updatedAt', 'deckId', 'deckRevisionId', 'deckVersion', 'prompt', 'evaluatorPolicy',
        'objective', 'bindings'
    ]);
    const summary = parseSummary({
        exerciseId: object['exerciseId'], exerciseRevisionId: object['exerciseRevisionId'],
        exerciseVersion: object['exerciseVersion'], ordinal: object['ordinal'], type: object['type'],
        enabled: object['enabled'], schemaVersion: object['schemaVersion'], createdAt: object['createdAt'],
        updatedAt: object['updatedAt'], objective: object['objective']
    });
    if (!Array.isArray(object['bindings']) || object['bindings'].length === 0 || object['bindings'].length > 8) {
        throw new AuthoringProtocolError('Invalid exercise bindings.');
    }
    const evaluator = requireObject(object['evaluatorPolicy'], ['id', 'version']);
    if (typeof evaluator['id'] !== 'string' || evaluator['version'] !== '1') {
        throw new AuthoringProtocolError('Invalid evaluator policy.');
    }
    return {
        ...summary, deckId: requireEntity(object['deckId']), deckRevisionId: requireEntity(object['deckRevisionId']),
        deckVersion: requireVersion(object['deckVersion']), prompt: parsePrompt(object['prompt']),
        evaluatorPolicy: { id: evaluator['id'], version: '1' }, bindings: object['bindings'].map(parseBinding)
    };
}

function parseObjective(value: unknown): ExerciseObjective {
    const object = requireObject(value, [
        'objectiveId', 'objectiveKey', 'objectiveRevisionId', 'objectiveVersion', 'memberKey', 'answerContract'
    ]);
    return {
        objectiveId: requireEntity(object['objectiveId']), objectiveKey: requireEntity(object['objectiveKey']),
        objectiveRevisionId: requireEntity(object['objectiveRevisionId']),
        objectiveVersion: requireVersion(object['objectiveVersion']), memberKey: requireEntity(object['memberKey']),
        answerContract: parseAnswer(object['answerContract'])
    };
}

function parseAnswer(value: unknown): AnswerContract {
    const object = requireObject(value, ['schemaVersion', 'normalization', 'accepted']);
    if (object['schemaVersion'] !== 1 || !Array.isArray(object['normalization'])
        || !Array.isArray(object['accepted']) || object['accepted'].length === 0 || object['accepted'].length > 20) {
        throw new AuthoringProtocolError('Invalid answer contract.');
    }
    const allowed = new Set(['UNICODE_NFC', 'TRIM', 'CASE_FOLD']);
    if (object['normalization'].some(rule => typeof rule !== 'string' || !allowed.has(rule))
        || object['accepted'].some(answer => typeof answer !== 'string' || answer.trim().length === 0)) {
        throw new AuthoringProtocolError('Invalid answer contract values.');
    }
    return { schemaVersion: 1, normalization: object['normalization'] as AnswerContract['normalization'], accepted: object['accepted'] as string[] };
}

function parsePrompt(value: unknown): ExercisePrompt {
    if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new AuthoringProtocolError('Invalid prompt.');
    const kind = (value as Record<string, unknown>)['kind'];
    if (kind === 'CUSTOM_TEXT') {
        const object = requireObject(value, ['kind', 'text']);
        if (typeof object['text'] !== 'string') throw new AuthoringProtocolError('Invalid custom prompt.');
        return { kind, text: object['text'] };
    }
    const object = requireObject(value, ['kind', 'memberKey', 'itemRevisionId', 'nodeId']);
    if (object['kind'] !== 'NODE_TEXT') throw new AuthoringProtocolError('Unsupported prompt.');
    return { kind: 'NODE_TEXT', memberKey: requireEntity(object['memberKey']),
        itemRevisionId: requireEntity(object['itemRevisionId']), nodeId: requireEntity(object['nodeId']) };
}

function parseBinding(value: unknown): ExerciseBinding {
    const object = requireObject(value, ['bindingId', 'role', 'memberKey', 'itemRevisionId', 'ordinal', 'nodeIds', 'display']);
    if (!Array.isArray(object['nodeIds']) || object['nodeIds'].length > 16) throw new AuthoringProtocolError('Invalid binding nodes.');
    const display = requireObject(object['display'], ['kind']);
    if (typeof display['kind'] !== 'string') throw new AuthoringProtocolError('Invalid display projection.');
    return {
        bindingId: requireEntity(object['bindingId']), role: bindingRole(object['role']),
        memberKey: requireEntity(object['memberKey']), itemRevisionId: requireEntity(object['itemRevisionId']),
        ordinal: requireCount(object['ordinal'], 15), nodeIds: object['nodeIds'].map(requireEntity),
        display: { kind: display['kind'] }
    };
}

function parseWrite(response: HttpResponse<unknown>, status: number, commandId: string, deckId: string): ExerciseWriteResult {
    if (response.status !== status) throw new AuthoringProtocolError('Unexpected exercise status.');
    requirePrivate(response);
    const object = requireObject(response.body, [
        'commandId', 'deckId', 'deckRevisionId', 'deckVersion', 'objectiveId', 'objectiveKey',
        'objectiveRevisionId', 'exerciseId', 'exerciseRevisionId', 'enabled'
    ]);
    const acknowledgement: ExerciseAcknowledgement = {
        commandId: requireCommand(object['commandId']), deckId: requireEntity(object['deckId']),
        deckRevisionId: requireEntity(object['deckRevisionId']), deckVersion: requireVersion(object['deckVersion']),
        objectiveId: requireEntity(object['objectiveId']), objectiveKey: requireEntity(object['objectiveKey']),
        objectiveRevisionId: requireEntity(object['objectiveRevisionId']), exerciseId: requireEntity(object['exerciseId']),
        exerciseRevisionId: requireEntity(object['exerciseRevisionId']), enabled: boolean(object['enabled'])
    };
    if (acknowledgement.commandId !== commandId || acknowledgement.deckId !== deckId.toLowerCase()) {
        throw new AuthoringProtocolError('Exercise acknowledgement mismatch.');
    }
    const replayHeader = response.headers.get('Idempotency-Replayed');
    if (replayHeader !== null && replayHeader !== 'true') {
        throw new AuthoringProtocolError('Invalid exercise replay header.');
    }
    const replayed = replayHeader === 'true';
    if (replayed ? response.headers.has('ETag') : response.headers.get('ETag') !== expectedEtag(acknowledgement.deckVersion)) {
        throw new AuthoringProtocolError('Invalid exercise acknowledgement headers.');
    }
    return { acknowledgement, replayed };
}

function exerciseType(value: unknown): ExerciseType {
    if (value !== 'SELF_CHECK' && value !== 'TYPED' && value !== 'CLOZE_SINGLE' && value !== 'SINGLE_CHOICE') {
        throw new AuthoringProtocolError('Invalid exercise type.');
    }
    return value;
}

function bindingRole(value: unknown): BindingRole {
    if (value !== 'ASSESSED' && value !== 'CUE' && value !== 'OPTION' && value !== 'CONTEXT') {
        throw new AuthoringProtocolError('Invalid binding role.');
    }
    return value;
}

function boolean(value: unknown): boolean {
    if (typeof value !== 'boolean') throw new AuthoringProtocolError('Expected boolean.');
    return value;
}

function requirePrivate(response: HttpResponse<unknown>): void {
    const cache = (response.headers.get('Cache-Control') ?? '').toLowerCase().split(',').map(value => value.trim());
    if (!cache.includes('private') || !cache.includes('no-store')) {
        throw new AuthoringProtocolError('Private exercise response can be cached.');
    }
}

function requireEtag(response: HttpResponse<unknown>, version: string): void {
    if (response.headers.get('ETag') !== expectedEtag(version)) throw new AuthoringProtocolError('Invalid exercise ETag.');
}
