import { HttpClient, HttpHeaders, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, defer, map } from 'rxjs';

import { appConfig } from '../../app.config';
import { expectedEtag } from '../own-decks/own-deck.models';
import {
    AuthoringProtocolError, newCommandId, requireCommand, requireCount, requireCursor, requireEntity, requireInstant,
    requireObject, requireVersion
} from './authoring.models';
import { ExerciseSpec, MECHANICS, Mechanic, ObjectiveCommand } from '../../content/exercise/exercise-content.models';
import {
    ExerciseContentError, parseExerciseSpec, parseObjectiveCommand
} from '../../content/exercise/exercise-content.parse';
import {
    EXERCISE_PAGE_SIZE, ExerciseAcknowledgement, ExerciseDetail, ExerciseObjective, ExercisePage, ExerciseSummary,
    ExerciseWriteResult
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
            const detail = contract(() => parseDetail(response.body));
            requireEtag(response, detail.deckVersion);
            if (detail.deckId !== deckId.toLowerCase() || detail.exerciseId !== exerciseId.toLowerCase()) {
                throw new AuthoringProtocolError('Exercise identity mismatch.');
            }
            return detail;
        }));
    }

    create(deckId: string, deckVersion: string, deckRevisionId: string,
           objective: ObjectiveCommand, exercise: ExerciseSpec, commandId = newCommandId()): Observable<ExerciseWriteResult> {
        return this.write('POST', deckId, null, deckVersion, () => ({
            commandId: requireCommand(commandId), expectedDeckRevisionId: requireEntity(deckRevisionId),
            objective: contract(() => parseObjectiveCommand(objective)), exercise: contract(() => parseExerciseSpec(exercise))
        }), 201);
    }

    update(deckId: string, exerciseId: string, deckVersion: string, deckRevisionId: string,
           exerciseRevisionId: string, objective: ObjectiveCommand, exercise: ExerciseSpec,
           commandId = newCommandId()): Observable<ExerciseWriteResult> {
        return this.write('PUT', deckId, exerciseId, deckVersion, () => ({
            commandId: requireCommand(commandId), expectedDeckRevisionId: requireEntity(deckRevisionId),
            expectedExerciseRevisionId: requireEntity(exerciseRevisionId),
            objective: contract(() => parseObjectiveCommand(objective)), exercise: contract(() => parseExerciseSpec(exercise))
        }), 200);
    }

    delete(deckId: string, exerciseId: string, deckVersion: string): Observable<void> {
        return defer(() => this.http.delete<unknown>(
            `${this.baseUrl}/decks/${encodeURIComponent(requireEntity(deckId))}/exercises/${encodeURIComponent(requireEntity(exerciseId))}`,
            { headers: new HttpHeaders({ 'If-Match': expectedEtag(deckVersion) }), observe: 'response' }
        )).pipe(map(response => {
            if (response.status !== 204 || response.body !== null) throw new AuthoringProtocolError('Invalid delete response.');
            requirePrivate(response);
        }));
    }

    /**
     * Clears the «Новое» mark of an exercise (the editor calls it when the exercise is opened). Idempotent; a foreign or absent
     * exercise is an opaque 404. The mark is decoration, so a caller ignores every failure.
     */
    clearNewMark(deckId: string, exerciseId: string): Observable<void> {
        return defer(() => this.http.delete(
            `${this.baseUrl}/decks/${encodeURIComponent(requireEntity(deckId))}/exercises/${encodeURIComponent(requireEntity(exerciseId))}/new-mark`,
            { observe: 'response', responseType: 'text' }
        )).pipe(map(response => {
            if (response.status !== 204) throw new AuthoringProtocolError('Invalid new-mark response.');
        }));
    }

    private write(method: 'POST' | 'PUT', deckId: string, exerciseId: string | null, deckVersion: string,
                  command: () => Record<string, unknown>, status: number): Observable<ExerciseWriteResult> {
        let body: Record<string, unknown> = {};
        return defer(() => {
            body = command();
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
        'createdAt', 'updatedAt', 'objective', 'isNew'
    ]);
    if (object['schemaVersion'] !== 2) throw new AuthoringProtocolError('Unsupported exercise schema.');
    return {
        exerciseId: requireEntity(object['exerciseId']), exerciseRevisionId: requireEntity(object['exerciseRevisionId']),
        exerciseVersion: requireVersion(object['exerciseVersion']), ordinal: requireCount(object['ordinal'], 99_999),
        type: exerciseType(object['type']), enabled: boolean(object['enabled']), schemaVersion: 2,
        createdAt: requireInstant(object['createdAt']), updatedAt: requireInstant(object['updatedAt']),
        objective: parseObjective(object['objective']), isNew: boolean(object['isNew'])
    };
}

function parseDetail(value: unknown): ExerciseDetail {
    const object = requireObject(value, [
        'exerciseId', 'exerciseRevisionId', 'exerciseVersion', 'ordinal', 'type', 'enabled', 'schemaVersion',
        'createdAt', 'updatedAt', 'deckId', 'deckRevisionId', 'deckVersion', 'subject', 'content', 'answerKey',
        'evaluatorPolicy', 'objective'
    ]);
    const spec = parseExerciseSpec({
        type: object['type'], schemaVersion: object['schemaVersion'], enabled: object['enabled'],
        subject: object['subject'], content: object['content'], answerKey: object['answerKey'],
        evaluatorPolicy: object['evaluatorPolicy']
    });
    const objective = parseObjective(object['objective']);
    if (objective.memberKey !== spec.subject.memberKey) throw new AuthoringProtocolError('Objective member mismatch.');
    return {
        exerciseId: requireEntity(object['exerciseId']), exerciseRevisionId: requireEntity(object['exerciseRevisionId']),
        exerciseVersion: requireVersion(object['exerciseVersion']), ordinal: requireCount(object['ordinal'], 99_999),
        createdAt: requireInstant(object['createdAt']), updatedAt: requireInstant(object['updatedAt']),
        deckId: requireEntity(object['deckId']), deckRevisionId: requireEntity(object['deckRevisionId']),
        deckVersion: requireVersion(object['deckVersion']), objective, ...spec
    };
}

function parseObjective(value: unknown): ExerciseObjective {
    const object = requireObject(value, [
        'objectiveId', 'objectiveKey', 'objectiveRevisionId', 'objectiveVersion', 'memberKey', 'title'
    ]);
    const title = object['title'];
    if (typeof title !== 'string' || title.trim().length === 0 || title.length > 160) {
        throw new AuthoringProtocolError('Invalid objective title.');
    }
    return {
        objectiveId: requireEntity(object['objectiveId']), objectiveKey: requireEntity(object['objectiveKey']),
        objectiveRevisionId: requireEntity(object['objectiveRevisionId']),
        objectiveVersion: requireVersion(object['objectiveVersion']), memberKey: requireEntity(object['memberKey']), title
    };
}

/** Content parsers throw their own error type; callers of this service only see protocol errors. */
function contract<T>(parse: () => T): T {
    try {
        return parse();
    } catch (error) {
        if (error instanceof ExerciseContentError) throw new AuthoringProtocolError(error.message);
        throw error;
    }
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

function exerciseType(value: unknown): Mechanic {
    if (typeof value !== 'string' || !(MECHANICS as readonly string[]).includes(value)) {
        throw new AuthoringProtocolError('Invalid exercise type.');
    }
    return value as Mechanic;
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
