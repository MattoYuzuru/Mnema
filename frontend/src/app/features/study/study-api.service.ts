import { HttpClient, HttpHeaders, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, defer, map } from 'rxjs';

import { appConfig } from '../../app.config';
import { LearnerContent, MECHANICS, Mechanic, allLearnerBlocks } from '../../content/exercise/exercise-content.models';
import { ExerciseContentError, parseLearnerBlock, parseLearnerContent } from '../../content/exercise/exercise-content.parse';
import {
    AttemptCommand,
    AttemptFeedback,
    AttemptOutcome,
    HintResult,
    MaterialProgress,
    PreparingStudySession,
    ReadyStudySession,
    ReplaySources,
    RestartAcknowledgement,
    StudyExerciseType,
    StudyHint,
    StudyMode,
    StudyPresentation,
    StudyProtocolError,
    StudySession,
    StudyStartIntent,
    StudyProgressPage,
    StudyWriteResult,
    TranscriptReveal
} from './study.models';

@Injectable({ providedIn: 'root' })
export class StudyApiService {
    private readonly http = inject(HttpClient);
    private readonly baseUrl = appConfig.learningApiBaseUrl.replace(/\/$/, '');

    start(deckId: string, commandId: string,
          intent: StudyStartIntent = { mode: 'SCHEDULED', preset: 'STANDARD' }): Observable<StudyWriteResult<StudySession>> {
        return defer(() => {
            const deck = entity(deckId);
            const command = commandIdValue(commandId);
            const body = startCommand(command, intent);
            return this.http.post<unknown>(`${this.baseUrl}/decks/${deck}/study-sessions`, body,
                { observe: 'response' });
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

    refill(deckId: string, sessionId: string): Observable<StudySession> {
        return defer(() => this.http.post<unknown>(
            `${this.baseUrl}/decks/${entity(deckId)}/study-sessions/${entity(sessionId)}/presentations`, null,
            { observe: 'response' }
        )).pipe(map(response => {
            if (response.status !== 200) throw protocol('Unexpected session refill status.');
            privateResponse(response);
            return parseSession(response.body, deckId, sessionId);
        }));
    }

    progress(deckId: string, limit = 100, cursorValue: string | null = null): Observable<StudyProgressPage> {
        return defer(() => {
            const deck = entity(deckId);
            if (!Number.isSafeInteger(limit) || limit < 1 || limit > 100) throw protocol('Invalid progress limit.');
            const cursorQuery = cursorValue === null ? '' : `&cursor=${encodeURIComponent(text(cursorValue, 4096))}`;
            return this.http.get<unknown>(`${this.baseUrl}/decks/${deck}/study-progress?limit=${limit}${cursorQuery}`,
                { observe: 'response' });
        }).pipe(map(response => {
            if (response.status !== 200) throw protocol('Unexpected progress status.');
            privateResponse(response);
            return parseProgress(response.body);
        }));
    }

    replaySources(deckId: string): Observable<ReplaySources> {
        return defer(() => this.http.get<unknown>(
            `${this.baseUrl}/decks/${entity(deckId)}/study-sessions/replay-sources`, { observe: 'response' }
        )).pipe(map(response => {
            if (response.status !== 200) throw protocol('Unexpected replay source status.');
            privateResponse(response);
            return parseReplaySources(response.body);
        }));
    }

    restart(deckId: string, commandId: string,
            memberKeys: readonly string[]): Observable<StudyWriteResult<RestartAcknowledgement>> {
        return defer(() => {
            if (memberKeys.length < 1 || memberKeys.length > 100) throw protocol('Invalid restart scope.');
            const command = commandIdValue(commandId);
            const members = memberKeys.map(entity);
            if (new Set(members).size !== members.length) throw protocol('Duplicate restart material.');
            return this.http.post<unknown>(`${this.baseUrl}/decks/${entity(deckId)}/study-restarts`,
                { commandId: command, memberKeys: members }, { observe: 'response' });
        }).pipe(map(response => {
            if (response.status !== 200) throw protocol('Unexpected restart status.');
            privateResponse(response);
            const value = parseRestart(response.body);
            if (value.commandId !== commandId.toLowerCase()) throw protocol('Restart acknowledgement mismatch.');
            return { value, replayed: replayHeader(response.headers) };
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

    checkPair(deckId: string, sessionId: string, presentationId: string, nonce: string,
              leftId: string, rightId: string): Observable<{ readonly correct: boolean }> {
        return defer(() => this.http.post<unknown>(
            `${this.baseUrl}/decks/${entity(deckId)}/study-sessions/${entity(sessionId)}/pair-checks`,
            { presentationId: entity(presentationId), nonce: text(nonce, 100, 16),
                leftId: entity(leftId), rightId: entity(rightId) }, { observe: 'response' }
        )).pipe(map(response => {
            if (response.status !== 200) throw protocol('Unexpected pair check status.');
            privateResponse(response);
            const object = exact(response.body, ['correct']);
            if (typeof object['correct'] !== 'boolean') throw protocol('Invalid pair check result.');
            return { correct: object['correct'] };
        }));
    }

    /** Records and returns the first letter of one blank; the server is the only source of the letter. */
    hint(deckId: string, sessionId: string, presentationId: string, nonce: string,
         blankId: string): Observable<HintResult> {
        return defer(() => this.http.post<unknown>(
            `${this.baseUrl}/decks/${entity(deckId)}/study-sessions/${entity(sessionId)}`
                + `/presentations/${entity(presentationId)}/hints`,
            { nonce: text(nonce, 100, 16), blankId: entity(blankId) }, { observe: 'response' }
        )).pipe(map(response => {
            if (response.status !== 200) throw protocol('Unexpected hint status.');
            privateResponse(response);
            const object = exact(response.body, ['presentationId', 'blankId', 'firstLetter']);
            const result = { presentationId: entity(object['presentationId']), blankId: entity(object['blankId']),
                firstLetter: letter(object['firstLetter']) };
            if (result.presentationId !== presentationId.toLowerCase() || result.blankId !== blankId.toLowerCase()) {
                throw protocol('Hint scope mismatch.');
            }
            return result;
        }));
    }

    revealTranscript(deckId: string, sessionId: string, presentationId: string, nonce: string,
                     type: Mechanic): Observable<TranscriptReveal> {
        return defer(() => this.http.post<unknown>(
            `${this.baseUrl}/decks/${entity(deckId)}/study-sessions/${entity(sessionId)}`
                + `/presentations/${entity(presentationId)}/transcript`,
            { nonce: text(nonce, 100, 16) }, { observe: 'response' }
        )).pipe(map(response => {
            if (response.status !== 200) throw protocol('Unexpected transcript status.');
            privateResponse(response);
            const object = exact(response.body, ['presentationId', 'transcriptRevealed', 'content']);
            if (entity(object['presentationId']) !== presentationId.toLowerCase()) throw protocol('Transcript scope mismatch.');
            if (object['transcriptRevealed'] !== true) throw protocol('Transcript was not revealed.');
            const content = guard(() => parseLearnerContent(type, object['content'], true));
            if (!hasTranscript(content)) throw protocol('Transcript was not revealed.');
            return { presentationId: presentationId.toLowerCase(), content };
        }));
    }
}

function startCommand(commandId: string, intent: StudyStartIntent): Record<string, unknown> {
    if (intent.mode === 'SCHEDULED') {
        if (intent.preset !== 'QUICK' && intent.preset !== 'STANDARD') throw protocol('Invalid scheduled preset.');
        const budget = intent.preset === 'QUICK'
            ? { maxPresentations: 10, maxNewObjectives: 2 }
            : { maxPresentations: 20, maxNewObjectives: 5 };
        return { commandId, mode: intent.mode, budget };
    }
    if (intent.mode === 'REPLAY') return { commandId, mode: intent.mode,
        sourceSessionId: entity(intent.sourceSessionId), budget: { maxPresentations: 20 } };
    if (intent.order !== 'SEEDED' && intent.order !== 'WEAKEST_FIRST') throw protocol('Invalid practice order.');
    if (typeof intent.includeNew !== 'boolean') throw protocol('Invalid practice scope.');
    return { commandId, mode: intent.mode, includeNew: intent.includeNew, order: intent.order,
        budget: { maxPresentations: 20 } };
}

function parseProgress(value: unknown): StudyProgressPage {
    const object = exact(value, ['asOf', 'items', 'nextCursor']);
    if (!Array.isArray(object['items']) || object['items'].length > 100) throw protocol('Invalid progress page.');
    return { asOf: instant(object['asOf']), items: object['items'].map(parseMaterialProgress),
        nextCursor: cursor(object['nextCursor']) };
}

function parseMaterialProgress(value: unknown): MaterialProgress {
    const object = exact(value, ['memberKey', 'itemRevisionId', 'state', 'objectiveCoverage',
        'lastAssessedAt', 'nextDue', 'title']);
    const state = object['state'];
    if (state !== 'NOT_STARTED' && state !== 'LEARNING' && state !== 'DUE' && state !== 'ON_TRACK') {
        throw protocol('Invalid material progress state.');
    }
    const title = object['title'];
    if (typeof title !== 'string' || Array.from(title).length > 240) {
        throw protocol('Invalid material preview.');
    }
    const coverage = exact(object['objectiveCoverage'], ['enabled', 'introduced', 'assessed']);
    const enabled = count(coverage['enabled'], 1_000_000);
    const introduced = count(coverage['introduced'], enabled);
    const assessed = count(coverage['assessed'], introduced);
    const nullableInstant = (item: unknown): string | null => item === null ? null : instant(item);
    return { title: title as string, memberKey: entity(object['memberKey']), itemRevisionId: entity(object['itemRevisionId']), state,
        objectiveCoverage: { enabled, introduced, assessed },
        lastAssessedAt: nullableInstant(object['lastAssessedAt']), nextDue: nullableInstant(object['nextDue']) };
}

function parseReplaySources(value: unknown): ReplaySources {
    const object = exact(value, ['asOf', 'localStudyDate', 'items']);
    if (!Array.isArray(object['items']) || object['items'].length > 20) throw protocol('Invalid replay sources.');
    return { asOf: instant(object['asOf']), localStudyDate: localDate(object['localStudyDate']),
        items: object['items'].map(item => {
            const source = exact(item, ['sessionId', 'completedAt', 'presentationCount']);
            return { sessionId: entity(source['sessionId']), completedAt: instant(source['completedAt']),
                presentationCount: count(source['presentationCount'], 100) };
        }) };
}

function parseRestart(value: unknown): RestartAcknowledgement {
    const object = exact(value, ['commandId', 'restartedAt', 'objectiveCount', 'learningEpochs']);
    if (!Array.isArray(object['learningEpochs']) || object['learningEpochs'].length > 10_000) {
        throw protocol('Invalid restart acknowledgement.');
    }
    const learningEpochs = object['learningEpochs'].map(item => {
        const epoch = exact(item, ['objectiveId', 'learningEpoch']);
        return { objectiveId: entity(epoch['objectiveId']), learningEpoch: unsigned(epoch['learningEpoch']) };
    });
    const objectiveCount = count(object['objectiveCount'], 10_000);
    if (objectiveCount !== learningEpochs.length) throw protocol('Restart count mismatch.');
    return { commandId: commandIdValue(object['commandId']), restartedAt: instant(object['restartedAt']),
        objectiveCount, learningEpochs };
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
        'exerciseGenerationId', 'selectionPolicyVersion', 'budget', 'issuedCount', 'seed', 'expiresAt', 'reducer',
        'nextCursor', 'presentations'
    ]);
    const status = object['status'];
    if (status !== 'ACTIVE' && status !== 'EMPTY' && status !== 'COMPLETE') throw protocol('Invalid session status.');
    const reducer = exact(object['reducer'], ['id', 'version', 'configId', 'configHash']);
    const budget = exact(object['budget'], ['maxPresentations', 'maxNewObjectives']);
    const maxPresentations = count(budget['maxPresentations'], 100);
    if (maxPresentations < 1) throw protocol('Invalid session budget.');
    const maxNewObjectives = count(budget['maxNewObjectives'], maxPresentations);
    if (!Array.isArray(object['presentations']) || object['presentations'].length > 20) {
        throw protocol('Invalid presentation page.');
    }
    const session: ReadyStudySession = {
        sessionId: entity(object['sessionId']), deckId: entity(object['deckId']), mode: mode(object['mode']), status,
        timezone: text(object['timezone'], 80), localStudyDate: localDate(object['localStudyDate']),
        deckRevisionId: entity(object['deckRevisionId']), exerciseGenerationId: entity(object['exerciseGenerationId']),
        selectionPolicyVersion: text(object['selectionPolicyVersion'], 100),
        budget: { maxPresentations, maxNewObjectives }, issuedCount: count(object['issuedCount'], maxPresentations),
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

const EVALUATORS: Readonly<Record<Mechanic, readonly string[]>> = {
    SELF_CHECK: ['self-check'], FREE_RESPONSE: ['deterministic-text', 'ai-semantic'], CLOZE: ['deterministic-cloze'],
    CHOICE: ['deterministic-choice'], MATCH: ['deterministic-match']
};

function parsePresentation(value: unknown): StudyPresentation {
    const object = exact(value, [
        'presentationId', 'nonce', 'ordinal', 'exerciseRevisionId', 'type', 'objectiveId', 'objectiveRevisionId',
        'learningEpoch', 'content', 'transcriptRevealed', 'hints', 'evaluator'
    ]);
    const type = exerciseType(object['type']);
    if (typeof object['transcriptRevealed'] !== 'boolean') throw protocol('Invalid transcript state.');
    const learner = guard(() => parseLearnerContent(type, object['content'], object['transcriptRevealed'] as boolean));
    const evaluator = exact(object['evaluator'], ['id', 'version']);
    if (!EVALUATORS[type].includes(text(evaluator['id'], 100))) throw protocol('Evaluator does not match the exercise.');
    const hints = parseHints(object['hints'], learner);
    return {
        presentationId: entity(object['presentationId']), nonce: text(object['nonce'], 100, 16),
        ordinal: count(object['ordinal'], 99), exerciseRevisionId: entity(object['exerciseRevisionId']),
        objectiveId: entity(object['objectiveId']), objectiveRevisionId: entity(object['objectiveRevisionId']),
        learningEpoch: unsigned(object['learningEpoch']), transcriptRevealed: object['transcriptRevealed'],
        hints, evaluator: { id: text(evaluator['id'], 100), version: text(evaluator['version'], 100) }, ...learner
    };
}

function parseHints(value: unknown, learner: LearnerContent): readonly StudyHint[] {
    if (!Array.isArray(value) || value.length > 12) throw protocol('Invalid hints.');
    const hintable = new Set(learner.type === 'CLOZE'
        ? learner.content.passage.flatMap(segment => segment.kind === 'BLANK' && segment.firstLetterHint ? [segment.blankId] : [])
        : []);
    const hints = value.map(entry => {
        const hint = exact(entry, ['blankId', 'firstLetter']);
        return { blankId: entity(hint['blankId']), firstLetter: letter(hint['firstLetter']) };
    });
    if (new Set(hints.map(hint => hint.blankId)).size !== hints.length || hints.some(hint => !hintable.has(hint.blankId))) {
        throw protocol('Invalid hint scope.');
    }
    return hints;
}

function hasTranscript(content: LearnerContent): boolean {
    return allLearnerBlocks(content).some(block => (block.kind === 'AUDIO' || block.kind === 'VIDEO') && block.transcript !== undefined);
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

const RESULTS = ['CORRECT', 'PARTIAL', 'UNSURE', 'INCORRECT'] as const;

function parseFeedback(value: unknown): AttemptFeedback {
    if (!isRecord(value) || typeof value['result'] !== 'string') throw protocol('Invalid feedback.');
    const result = value['result'];
    if (result === 'NOT_ASSESSED' || result === 'UNAVAILABLE') {
        const object = exact(value, ['result', 'reasonCodes']);
        return { result, reasonCodes: strings(object['reasonCodes']) };
    }
    if (!(RESULTS as readonly string[]).includes(result)) throw protocol('Invalid feedback result.');
    const verdict = result as (typeof RESULTS)[number];
    const rules = (object: Record<string, unknown>) => strings(object['appliedRules']);
    if ('blanks' in value) {
        const object = exact(value, ['result', 'appliedRules', 'blanks']);
        if (!Array.isArray(object['blanks']) || object['blanks'].length < 1 || object['blanks'].length > 12) {
            throw protocol('Invalid blank feedback.');
        }
        const blanks = object['blanks'].map(entry => {
            const blank = exact(entry, ['blankId', 'correct', 'hinted', 'reference']);
            if (typeof blank['correct'] !== 'boolean' || typeof blank['hinted'] !== 'boolean') throw protocol('Invalid blank result.');
            return { blankId: entity(blank['blankId']), correct: blank['correct'], hinted: blank['hinted'],
                reference: text(blank['reference'], 4096, 0) };
        });
        if (new Set(blanks.map(blank => blank.blankId)).size !== blanks.length) throw protocol('Duplicate blank feedback.');
        return { result: verdict, appliedRules: rules(object), blanks };
    }
    if ('correctOptionIds' in value) {
        const object = exact(value, ['result', 'appliedRules', 'correctOptionIds']);
        if (!Array.isArray(object['correctOptionIds']) || object['correctOptionIds'].length < 1
            || object['correctOptionIds'].length > 12) throw protocol('Invalid choice feedback.');
        const correctOptionIds = object['correctOptionIds'].map(entity);
        if (new Set(correctOptionIds).size !== correctOptionIds.length) throw protocol('Duplicate correct option.');
        return { result: verdict, appliedRules: rules(object), correctOptionIds };
    }
    if ('pairs' in value) {
        const object = exact(value, ['result', 'appliedRules', 'pairs']);
        if (!Array.isArray(object['pairs']) || object['pairs'].length < 2 || object['pairs'].length > 6) {
            throw protocol('Invalid pair feedback.');
        }
        const pairs = object['pairs'].map(entry => {
            const pair = exact(entry, ['leftId', 'selectedRightId', 'correctRightId', 'correct']);
            if (typeof pair['correct'] !== 'boolean') throw protocol('Invalid pair result.');
            return { leftId: entity(pair['leftId']), selectedRightId: entity(pair['selectedRightId']),
                correctRightId: entity(pair['correctRightId']), correct: pair['correct'] };
        });
        if (new Set(pairs.map(pair => pair.leftId)).size !== pairs.length) throw protocol('Duplicate pair feedback.');
        return { result: verdict, appliedRules: rules(object), pairs };
    }
    if ('referenceContent' in value) {
        const object = exact(value, ['result', 'appliedRules', 'reference', 'referenceContent']);
        if (!Array.isArray(object['referenceContent']) || object['referenceContent'].length > 8) {
            throw protocol('Invalid reference content.');
        }
        return { result: verdict, appliedRules: rules(object), reference: text(object['reference'], 4096, 0),
            referenceContent: object['referenceContent'].map(block => guard(() => parseLearnerBlock(block, 'REFERENCE', null))) };
    }
    return { result: verdict, appliedRules: rules(exact(value, ['result', 'appliedRules'])) };
}

function strings(value: unknown): readonly string[] {
    if (!Array.isArray(value) || value.length > 20 || value.some(item => typeof item !== 'string')) {
        throw protocol('Invalid feedback details.');
    }
    return value as string[];
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
    exact(command, ['attemptId', 'presentationId', 'nonce', 'response', 'confidence', 'durationMs']);
    commandIdValue(command.attemptId); entity(command.presentationId); text(command.nonce, 100, 16);
    if (!Number.isSafeInteger(command.durationMs) || command.durationMs < 0 || command.durationMs > 3_600_000) {
        throw protocol('Invalid attempt command.');
    }
    const response = command.response;
    if (response.kind === 'TEXT') { exact(response, ['kind', 'text']); text(response.text, 4096, 0); }
    else if (response.kind === 'SELF_CHECK') {
        exact(response, ['kind', 'rating']);
        if (!['NOT_RECALLED', 'HINTED', 'PARTIAL', 'FULL'].includes(response.rating)) throw protocol('Invalid rating.');
    } else if (response.kind === 'CLOZE') {
        exact(response, ['kind', 'blanks']);
        if (!Array.isArray(response.blanks) || response.blanks.length < 1 || response.blanks.length > 12
            || new Set(response.blanks.map(blank => entity(blank.blankId))).size !== response.blanks.length) {
            throw protocol('Invalid cloze response.');
        }
        response.blanks.forEach(blank => { exact(blank, ['blankId', 'text']); text(blank.text, 4096, 0); });
    } else if (response.kind === 'CHOICE') {
        exact(response, ['kind', 'optionIds']);
        if (!Array.isArray(response.optionIds) || response.optionIds.length === 0 || response.optionIds.length > 12
            || new Set(response.optionIds.map(entity)).size !== response.optionIds.length) {
            throw protocol('Invalid choice selection.');
        }
    } else if (response.kind === 'MATCH') {
        exact(response, ['kind', 'pairs']);
        if (!Array.isArray(response.pairs) || response.pairs.length < 2 || response.pairs.length > 6
            || new Set(response.pairs.map(pair => entity(pair.leftId))).size !== response.pairs.length
            || new Set(response.pairs.map(pair => entity(pair.rightId))).size !== response.pairs.length) {
            throw protocol('Invalid match response.');
        }
        response.pairs.forEach(pair => exact(pair, ['leftId', 'rightId']));
    } else if (response.kind === 'CANCEL') exact(response, ['kind']);
    else throw protocol('Invalid response kind.');
    if (command.confidence !== null && !['KNEW', 'UNSURE', 'GUESSED'].includes(command.confidence)) {
        throw protocol('Invalid confidence.');
    }
}

/** Content parsers throw their own error type; Study callers only see Study protocol errors. */
function guard<T>(parse: () => T): T {
    try {
        return parse();
    } catch (error) {
        if (error instanceof ExerciseContentError) throw protocol(error.message);
        throw error;
    }
}

function letter(value: unknown): string {
    if (typeof value !== 'string' || value.length < 1 || value.length > 16) throw protocol('Invalid hint letter.');
    return value;
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
function exerciseType(value: unknown): StudyExerciseType {
    if (typeof value !== 'string' || !(MECHANICS as readonly string[]).includes(value)) throw protocol('Invalid exercise type.');
    return value as StudyExerciseType;
}
function identity(actual: string, expected?: string): void { if (expected !== undefined && actual !== expected.toLowerCase()) throw protocol('Session identity mismatch.'); }
