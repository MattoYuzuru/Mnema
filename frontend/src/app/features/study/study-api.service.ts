import { HttpClient, HttpHeaders, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, defer, map } from 'rxjs';

import { appConfig } from '../../app.config';
import { LearnerContent, MECHANICS, Mechanic, allLearnerBlocks } from '../../content/exercise/exercise-content.models';
import { parseLearnerBlock, parseLearnerContent } from '../../content/exercise/exercise-content.parse';
import { parseAttemptFeedback } from './attempt-feedback.parse';
import {
    AssessingAttempt,
    AttemptCommand,
    AttemptOutcome,
    AttemptState,
    SELF_RATINGS,
    SelfCheckAttempt,
    SelfCheckReason,
    SelfRating,
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
    StudySession,
    StudyStartIntent,
    StudyProgressPage,
    StudyWriteResult,
    TranscriptReveal
} from './study.models';
import { entity, exact, guard, hintLetter, isRecord, protocol, text, validateResponse } from './study-wire';

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

    /**
     * Sends one answer. A deterministic answer returns its stored outcome (200); an `ai-semantic` answer returns 202 with
     * the grading state (still grading, or already self-check), which the caller follows with `attempt`.
     */
    submit(deckId: string, sessionId: string, command: AttemptCommand): Observable<StudyWriteResult<AttemptState>> {
        return defer(() => {
            validateCommand(command);
            return this.http.post<unknown>(
                `${this.baseUrl}/decks/${entity(deckId)}/study-sessions/${entity(sessionId)}/attempts`,
                command, { observe: 'response' }
            );
        }).pipe(map(response => {
            if (response.status !== 200 && response.status !== 202) throw protocol('Unexpected attempt status.');
            privateResponse(response);
            const value = response.status === 202 ? parseAssessmentState(response.body) : parseOutcome(response.body);
            if (value.attemptId !== command.attemptId || value.presentationId !== command.presentationId) {
                throw protocol('Attempt acknowledgement mismatch.');
            }
            return { value, replayed: replayHeader(response.headers) };
        }));
    }

    /** Current state of an answer: still grading, self-check, or the stored outcome. Never returns an error for a slow grader. */
    attempt(deckId: string, sessionId: string, attemptId: string): Observable<AttemptState> {
        return defer(() => this.http.get<unknown>(this.attemptUrl(deckId, sessionId, attemptId), { observe: 'response' }))
            .pipe(map(response => this.attemptState(response, attemptId)));
    }

    /** «Оценить себя»: the learner stops waiting for the model. Idempotent; the grade may already have won the race. */
    selfCheck(deckId: string, sessionId: string, attemptId: string): Observable<AttemptState> {
        return defer(() => this.http.post<unknown>(`${this.attemptUrl(deckId, sessionId, attemptId)}/self-check`, {},
            { observe: 'response' })).pipe(map(response => this.attemptState(response, attemptId)));
    }

    /** The learner's own rating completes the same attempt from the self-check view. */
    selfRate(deckId: string, sessionId: string, attemptId: string, rating: SelfRating): Observable<StudyWriteResult<AttemptOutcome>> {
        return defer(() => {
            if (!SELF_RATINGS.includes(rating)) throw protocol('Invalid rating.');
            return this.http.post<unknown>(`${this.attemptUrl(deckId, sessionId, attemptId)}/self-rating`, { rating },
                { observe: 'response' });
        }).pipe(map(response => this.outcomeOf(response, attemptId, 'Unexpected self-rating status.')));
    }

    /** «Оспорить оценку»: takes the AI grade back. Replaying the same command id returns the same result. */
    dispute(deckId: string, sessionId: string, attemptId: string, commandId: string): Observable<StudyWriteResult<AttemptOutcome>> {
        return defer(() => this.http.post<unknown>(`${this.attemptUrl(deckId, sessionId, attemptId)}/dispute`,
            { commandId: commandIdValue(commandId) }, { observe: 'response' }))
            .pipe(map(response => {
                const result = this.outcomeOf(response, attemptId, 'Unexpected dispute status.');
                if (!result.value.disputed) throw protocol('The grade was not disputed.');
                return result;
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
                firstLetter: hintLetter(object['firstLetter']) };
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

    private attemptUrl(deckId: string, sessionId: string, attemptId: string): string {
        return `${this.baseUrl}/decks/${entity(deckId)}/study-sessions/${entity(sessionId)}/attempts/${entity(attemptId)}`;
    }

    private attemptState(response: HttpResponse<unknown>, attemptId: string): AttemptState {
        if (response.status !== 200) throw protocol('Unexpected attempt status.');
        privateResponse(response);
        const body = response.body;
        const value = isRecord(body) && (body['status'] === 'ASSESSING' || body['status'] === 'SELF_CHECK')
            ? parseAssessmentState(body) : parseOutcome(body);
        if (value.attemptId !== entity(attemptId)) throw protocol('Attempt acknowledgement mismatch.');
        return value;
    }

    private outcomeOf(response: HttpResponse<unknown>, attemptId: string, message: string): StudyWriteResult<AttemptOutcome> {
        if (response.status !== 200) throw protocol(message);
        privateResponse(response);
        const value = parseOutcome(response.body);
        if (value.attemptId !== entity(attemptId)) throw protocol('Attempt acknowledgement mismatch.');
        return { value, replayed: replayHeader(response.headers) };
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
    CHOICE: ['deterministic-choice'], MATCH: ['deterministic-match'], ORDER: ['deterministic-order'],
    CATEGORIZE: ['deterministic-categorize']
};

function parsePresentation(value: unknown): StudyPresentation {
    const graded = isRecord(value) && 'assessment' in value;
    const object = exact(value, [
        'presentationId', 'nonce', 'ordinal', 'exerciseRevisionId', 'type', 'objectiveId', 'objectiveRevisionId',
        'learningEpoch', 'isNew', 'content', 'transcriptRevealed', 'hints', 'evaluator', ...(graded ? ['assessment'] : [])
    ]);
    if (typeof object['isNew'] !== 'boolean') throw protocol('Invalid new mark.');
    const type = exerciseType(object['type']);
    if (typeof object['transcriptRevealed'] !== 'boolean') throw protocol('Invalid transcript state.');
    const learner = guard(() => parseLearnerContent(type, object['content'], object['transcriptRevealed'] as boolean));
    const evaluator = exact(object['evaluator'], ['id', 'version']);
    if (!EVALUATORS[type].includes(text(evaluator['id'], 100))) throw protocol('Evaluator does not match the exercise.');
    const hints = parseHints(object['hints'], learner);
    let assessment: StudyPresentation['assessment'];
    if (graded) {
        if (type !== 'FREE_RESPONSE') throw protocol('Only a free response can be in assessment.');
        const state = exact(object['assessment'], ['attemptId', 'status']);
        if (state['status'] !== 'ASSESSING' && state['status'] !== 'SELF_CHECK') throw protocol('Invalid assessment state.');
        assessment = { attemptId: entity(state['attemptId']), status: state['status'] };
    }
    return {
        presentationId: entity(object['presentationId']), nonce: text(object['nonce'], 100, 16),
        ordinal: count(object['ordinal'], 99), exerciseRevisionId: entity(object['exerciseRevisionId']),
        objectiveId: entity(object['objectiveId']), objectiveRevisionId: entity(object['objectiveRevisionId']),
        learningEpoch: unsigned(object['learningEpoch']), transcriptRevealed: object['transcriptRevealed'],
        hints, evaluator: { id: text(evaluator['id'], 100), version: text(evaluator['version'], 100) },
        isNew: object['isNew'], ...(assessment === undefined ? {} : { assessment }), ...learner
    };
}

function parseHints(value: unknown, learner: LearnerContent): readonly StudyHint[] {
    if (!Array.isArray(value) || value.length > 12) throw protocol('Invalid hints.');
    const hintable = new Set(learner.type === 'CLOZE'
        ? learner.content.passage.flatMap(segment => segment.kind === 'BLANK' && segment.firstLetterHint ? [segment.blankId] : [])
        : []);
    const hints = value.map(entry => {
        const hint = exact(entry, ['blankId', 'firstLetter']);
        return { blankId: entity(hint['blankId']), firstLetter: hintLetter(hint['firstLetter']) };
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
    const disputed = 'disputed' in value;
    const object = exact(value, ['attemptId', 'presentationId', 'mode', 'status', 'evidence', 'feedback', 'transition',
        ...(practice ? ['canonicalEffects'] : []), ...(disputed ? ['disputed'] : [])]);
    const status = object['status'];
    if (status !== 'ASSESSED' && status !== 'NOT_ASSESSED' && status !== 'UNAVAILABLE') throw protocol('Invalid outcome status.');
    if (disputed && object['disputed'] !== true) throw protocol('Invalid dispute mark.');
    const parsedMode = mode(object['mode']);
    if (practice && (object['canonicalEffects'] !== false || parsedMode === 'SCHEDULED')) throw protocol('Invalid practice effect.');
    const transition = object['transition'] === null ? null : parseTransition(object['transition']);
    if (status === 'ASSESSED' && parsedMode === 'SCHEDULED' && transition === null) throw protocol('Missing transition.');
    parseEvidence(object['evidence'], status, parsedMode);
    return { attemptId: commandIdValue(object['attemptId']), presentationId: entity(object['presentationId']),
        mode: parsedMode, status, feedback: parseAttemptFeedback(object['feedback']),
        canonicalEffects: practice ? false : parsedMode === 'SCHEDULED', transition, disputed };
}

const SELF_CHECK_REASONS: readonly SelfCheckReason[] = ['LEARNER_CHOICE', 'PROVIDER_UNCERTAIN', 'PROVIDER_UNAVAILABLE',
    'USAGE_LIMIT', 'CAPABILITY_UNAVAILABLE', 'DEADLINE', 'BUSY'];

/** The 202 body and the polled state of an answer that is not terminal yet. */
function parseAssessmentState(value: unknown): AssessingAttempt | SelfCheckAttempt {
    if (!isRecord(value)) throw protocol('Invalid assessment state.');
    if (value['status'] === 'ASSESSING') {
        const object = exact(value, ['attemptId', 'presentationId', 'mode', 'status', 'retryAfterMs']);
        const retryAfterMs = object['retryAfterMs'];
        if (typeof retryAfterMs !== 'number' || !Number.isSafeInteger(retryAfterMs) || retryAfterMs < 100 || retryAfterMs > 60_000) {
            throw protocol('Invalid retry delay.');
        }
        return { attemptId: commandIdValue(object['attemptId']), presentationId: entity(object['presentationId']),
            mode: mode(object['mode']), status: 'ASSESSING', retryAfterMs };
    }
    const object = exact(value, ['attemptId', 'presentationId', 'mode', 'status', 'reason', 'selfCheck']);
    if (object['status'] !== 'SELF_CHECK') throw protocol('Invalid assessment status.');
    const reason = object['reason'];
    if (!SELF_CHECK_REASONS.includes(reason as SelfCheckReason)) throw protocol('Invalid self-check reason.');
    const view = exact(object['selfCheck'], ['reference', 'referenceContent', 'criteria']);
    if (!Array.isArray(view['referenceContent']) || view['referenceContent'].length > 8
        || !Array.isArray(view['criteria']) || view['criteria'].length < 3 || view['criteria'].length > 10) {
        throw protocol('Invalid self-check view.');
    }
    const criteria = view['criteria'].map(item => {
        const entry = exact(item, ['criterionId', 'description']);
        return { criterionId: entity(entry['criterionId']), description: text(entry['description'], 2048, 1) };
    });
    if (new Set(criteria.map(item => item.criterionId)).size !== criteria.length) throw protocol('Duplicate key point.');
    return { attemptId: commandIdValue(object['attemptId']), presentationId: entity(object['presentationId']),
        mode: mode(object['mode']), status: 'SELF_CHECK', reason: reason as SelfCheckReason,
        selfCheck: { reference: text(view['reference'], 16384, 0),
            referenceContent: view['referenceContent'].map(block => guard(() => parseLearnerBlock(block, 'REFERENCE', null))),
            criteria } };
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
    validateResponse(command.response);
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

function commandIdValue(value: unknown): string { return entity(value); }
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
