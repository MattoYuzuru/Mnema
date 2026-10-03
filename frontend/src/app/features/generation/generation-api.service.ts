import { HttpClient, HttpHeaders, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, defer, map } from 'rxjs';

import { appConfig } from '../../app.config';
import { ObjectiveCommand, ExerciseSpec } from '../../content/exercise/exercise-content.models';
import { ExerciseContentError, parseExerciseSpec, parseObjectiveCommand } from '../../content/exercise/exercise-content.parse';
import { AuthoringProtocolError, requireCommand, requireCursor, requireEntity, requireVersion } from '../authoring/authoring.models';
import { expectedEtag } from '../own-decks/own-deck.models';
import {
    ApprovalAck, ArtifactDetail, ArtifactSummary, CreatedSession, EditAccepted, EditEstimateRequest, EditRequest, EventsPage, GenerationEstimate,
    HandoffResult, GenerationSpec, MAX_APPROVALS_PER_COMMAND, RequestValidationError, NoteArchiveResult, SessionDetail, SessionPage,
    parseApprovalAck, parseArtifactDetail, parseArtifactSummary, parseEditAccepted, parseEstimate, parseEventsPage, parseHandoff,
    parseNoteArchive, parseSessionDetail, parseSessionPage, serializeEdit, serializeEditEstimate, serializeSpec
} from './generation.models';

/** The Deck version an approval is pinned to (`If-Match` and `expectedDeckRevisionId`). */
export interface DeckPin { readonly rowVersion: string; readonly revisionId: string; }

/** What an approval states about the artifact it publishes: exactly the version and revision the user saw. */
export interface ApprovalTarget {
    readonly artifactId: string;
    readonly expectedArtifactVersion: string;
    readonly expectedRevisionId: string;
}

/**
 * The exercise a user edited before saving it («Изменить», decision D7): published instead of the proposed revision. The server
 * checks it with the exercise command reader only (a human edit is not linted) and keeps the material it is about.
 */
export interface ApprovalReplacement { readonly objective: ObjectiveCommand; readonly exercise: ExerciseSpec; }

/**
 * HTTP boundary of AI generation (`contracts/generation/http.json`): the preflight estimate, Workshop sessions, polling
 * events and the artifact commands. Every answer is parsed strictly; a command answer that does not parse is reported as
 * an {@link AuthoringProtocolError}, which the caller treats as an unknown outcome (retry the same command).
 */
@Injectable({ providedIn: 'root' })
export class GenerationApiService {
    private readonly http = inject(HttpClient);
    private readonly baseUrl = appConfig.learningApiBaseUrl.replace(/\/$/, '');

    estimate(deckId: string, spec: GenerationSpec): Observable<GenerationEstimate> {
        return defer(() => this.http.post<unknown>(`${this.deckPath(deckId)}/generation-estimates`,
            { spec: serializeSpec(spec) }, { observe: 'response' }))
            .pipe(map(response => {
                requireStatus(response, 200);
                return parseEstimate(response.body);
            }));
    }

    /** The preflight cost of one edit (`estimateGeneration`, `edit` form): what a rewrite of `targetNodeCount` blocks would hold. */
    estimateEdit(deckId: string, request: EditEstimateRequest): Observable<GenerationEstimate> {
        return defer(() => this.http.post<unknown>(`${this.deckPath(deckId)}/generation-estimates`, serializeEditEstimate(request),
            { observe: 'response' })).pipe(map(response => {
            requireStatus(response, 200);
            return parseEstimate(response.body);
        }));
    }

    createSession(deckId: string, spec: GenerationSpec, commandId: string): Observable<CreatedSession> {
        return defer(() => this.http.post<unknown>(this.sessions(deckId),
            { commandId: requireCommand(commandId), spec: serializeSpec(spec) }, { observe: 'response' }))
            .pipe(map(response => {
                requireStatus(response, 201);
                const replayed = replayHeader(response);
                const session = parseSessionDetail(response.body);
                requireSessionEtag(response, session, replayed);
                if (session.deckId !== deckId.toLowerCase()) throw new AuthoringProtocolError('Session belongs to another deck.');
                return { session, replayed };
            }));
    }

    listSessions(deckId: string, options: { readonly active?: boolean; readonly cursor?: string | null; readonly limit?: number } = {}):
        Observable<SessionPage> {
        return defer(() => {
            let params = new HttpParams().set('limit', String(options.limit ?? 20));
            if (options.active === true) params = params.set('active', 'true');
            if (options.cursor) params = params.set('cursor', requireCursor(options.cursor)!);
            return this.http.get<unknown>(this.sessions(deckId), { params, observe: 'response' });
        }).pipe(map(response => this.page(response)));
    }

    /** Active sessions of the whole account (the "active workshops" entry point). */
    listActiveSessions(options: { readonly cursor?: string | null; readonly limit?: number } = {}): Observable<SessionPage> {
        return defer(() => {
            let params = new HttpParams().set('state', 'active').set('limit', String(options.limit ?? 20));
            if (options.cursor) params = params.set('cursor', requireCursor(options.cursor)!);
            return this.http.get<unknown>(`${this.baseUrl}/generation-sessions`, { params, observe: 'response' });
        }).pipe(map(response => this.page(response)));
    }

    getSession(deckId: string, sessionId: string): Observable<SessionDetail> {
        return defer(() => this.http.get<unknown>(this.session(deckId, sessionId), { observe: 'response' }))
            .pipe(map(response => this.sessionDetail(response, sessionId)));
    }

    cancelSession(deckId: string, sessionId: string, commandId: string): Observable<SessionDetail> {
        return defer(() => this.http.post<unknown>(`${this.session(deckId, sessionId)}/cancellation`,
            { commandId: requireCommand(commandId) }, { observe: 'response' }))
            .pipe(map(response => this.sessionDetail(response, sessionId)));
    }

    /** Hold-to-delete: removes unpublished artifacts; the first call answers 204, later calls 404. */
    deleteSession(deckId: string, sessionId: string): Observable<void> {
        return defer(() => this.http.delete(this.session(deckId, sessionId), { observe: 'response', responseType: 'text' }))
            .pipe(map(response => {
                requireStatus(response, 204);
                requirePrivate(response);
            }));
    }

    listEvents(deckId: string, sessionId: string, after: string, limit = 100): Observable<EventsPage> {
        return defer(() => {
            const params = new HttpParams().set('after', requireVersion(after)).set('limit', String(limit));
            return this.http.get<unknown>(`${this.session(deckId, sessionId)}/events`, { params, observe: 'response' });
        }).pipe(map(response => {
            requireStatus(response, 200);
            return parseEventsPage(response.body);
        }));
    }

    /**
     * The artifact with its current revision, or with the exact `revisionId` asked for (the pointer is unchanged): the client builds
     * the diff of an edit from two such reads.
     */
    getArtifact(deckId: string, sessionId: string, artifactId: string, revisionId: string | null = null): Observable<ArtifactDetail> {
        return defer(() => this.http.get<unknown>(this.artifact(deckId, sessionId, artifactId),
            { params: revisionId === null ? undefined : new HttpParams().set('revisionId', requireEntity(revisionId)), observe: 'response' }))
            .pipe(map(response => {
                requireStatus(response, 200);
                const artifact = parseArtifactDetail(response.body, revisionId === null ? null : revisionId.toLowerCase());
                if (artifact.artifactId !== artifactId.toLowerCase() || artifact.sessionId !== sessionId.toLowerCase()) {
                    throw new AuthoringProtocolError('Artifact identity does not match the request.');
                }
                requireEtag(response, artifact.rowVersion);
                return artifact;
            }));
    }

    approveArtifact(deckId: string, sessionId: string, target: ApprovalTarget, deck: DeckPin, commandId: string,
                    replacement: ApprovalReplacement | null = null): Observable<ApprovalAck> {
        return defer(() => this.http.post<unknown>(`${this.artifact(deckId, sessionId, target.artifactId)}/approval`, {
            commandId: requireCommand(commandId), expectedArtifactVersion: requireVersion(target.expectedArtifactVersion),
            expectedRevisionId: requireEntity(target.expectedRevisionId), expectedDeckRevisionId: requireEntity(deck.revisionId),
            ...(replacement === null ? {} : { replacement: serializeReplacement(replacement) })
        }, { headers: ifMatch(deck.rowVersion), observe: 'response' })).pipe(map(response => {
            const ack = this.approval(response, deckId, commandId);
            if (ack.artifacts.length !== 1 || ack.artifacts[0]!.artifactId !== target.artifactId.toLowerCase()) {
                throw new AuthoringProtocolError('Approval acknowledgement mismatch.');
            }
            return ack;
        }));
    }

    /** Up to 20 artifacts as one atomic publication; a larger selection is split by the caller. */
    approveArtifacts(deckId: string, sessionId: string, targets: readonly ApprovalTarget[], deck: DeckPin, commandId: string):
        Observable<ApprovalAck> {
        return defer(() => {
            if (targets.length === 0 || targets.length > MAX_APPROVALS_PER_COMMAND) {
                throw new AuthoringProtocolError('A bulk approval lists 1 to 20 artifacts.');
            }
            return this.http.post<unknown>(`${this.session(deckId, sessionId)}/approvals`, {
                commandId: requireCommand(commandId), expectedDeckRevisionId: requireEntity(deck.revisionId),
                artifacts: targets.map(target => ({ artifactId: requireEntity(target.artifactId),
                    expectedArtifactVersion: requireVersion(target.expectedArtifactVersion),
                    expectedRevisionId: requireEntity(target.expectedRevisionId) }))
            }, { headers: ifMatch(deck.rowVersion), observe: 'response' });
        }).pipe(map(response => {
            const ack = this.approval(response, deckId, commandId);
            const wanted = targets.map(target => target.artifactId.toLowerCase()).sort();
            const got = ack.artifacts.map(artifact => artifact.artifactId).sort();
            if (wanted.length !== got.length || wanted.some((id, index) => id !== got[index])) {
                throw new AuthoringProtocolError('Bulk approval acknowledgement mismatch.');
            }
            return ack;
        }));
    }

    rejectArtifact(deckId: string, sessionId: string, artifactId: string, expectedArtifactVersion: string, commandId: string):
        Observable<ArtifactSummary> {
        return defer(() => this.http.post<unknown>(`${this.artifact(deckId, sessionId, artifactId)}/rejection`,
            { commandId: requireCommand(commandId), expectedArtifactVersion: requireVersion(expectedArtifactVersion) },
            { observe: 'response' })).pipe(map(response => this.summary(response, artifactId, 'REJECTED')));
    }

    undoRejectArtifact(deckId: string, sessionId: string, artifactId: string, artifactVersion: string):
        Observable<ArtifactSummary> {
        return defer(() => this.http.delete<unknown>(`${this.artifact(deckId, sessionId, artifactId)}/rejection`,
            { headers: ifMatch(artifactVersion), observe: 'response' }))
            .pipe(map(response => this.summary(response, artifactId, 'PROPOSED')));
    }

    handoffArtifact(deckId: string, sessionId: string, target: ApprovalTarget, commandId: string): Observable<HandoffResult> {
        return defer(() => this.http.post<unknown>(`${this.artifact(deckId, sessionId, target.artifactId)}/handoff`, {
            commandId: requireCommand(commandId), expectedArtifactVersion: requireVersion(target.expectedArtifactVersion),
            expectedRevisionId: requireEntity(target.expectedRevisionId)
        }, { observe: 'response' })).pipe(map(response => {
            requireStatus(response, 201);
            const result = parseHandoff(response.body);
            if (result.artifact.artifactId !== target.artifactId.toLowerCase() || result.artifact.state !== 'HANDED_OFF'
                || result.draft.deckId !== deckId.toLowerCase()) throw new AuthoringProtocolError('Hand-off acknowledgement mismatch.');
            return result;
        }));
    }

    retryArtifact(deckId: string, sessionId: string, artifactId: string, expectedArtifactVersion: string, commandId: string):
        Observable<ArtifactSummary> {
        return defer(() => this.http.post<unknown>(`${this.artifact(deckId, sessionId, artifactId)}/retry`,
            { commandId: requireCommand(commandId), expectedArtifactVersion: requireVersion(expectedArtifactVersion) },
            { observe: 'response' })).pipe(map(response => this.summary(response, artifactId, 'QUEUED')));
    }

    /**
     * Edits the selection of a proposal (`editArtifact`, no `If-Match`). A rewrite is accepted with the turn `QUEUED` and the artifact
     * `REVISING`; `REMOVE_MEDIA` is applied before the answer. An exact retry answers `202` with `Idempotency-Replayed: true`.
     */
    editArtifact(deckId: string, sessionId: string, artifactId: string, request: EditRequest, commandId: string): Observable<EditAccepted> {
        return defer(() => this.http.post<unknown>(`${this.artifact(deckId, sessionId, artifactId)}/edits`, serializeEdit(request, commandId),
            { observe: 'response' })).pipe(map(response => {
            requireStatus(response, 202);
            const accepted = parseEditAccepted(response.body, replayHeader(response));
            if (accepted.artifact.artifactId !== artifactId.toLowerCase()) throw new AuthoringProtocolError('Edit acknowledgement mismatch.');
            if (accepted.turn.action !== request.action) throw new AuthoringProtocolError('Edit acknowledgement mismatch.');
            return accepted;
        }));
    }

    /**
     * Moves the current-revision pointer to another revision of the artifact (`revertArtifact`): no revision is created or deleted.
     * Moving to the revision already current is a `200` no-op, so a missing `ETag` is not an error there.
     */
    revertArtifact(deckId: string, sessionId: string, artifactId: string, expectedArtifactVersion: string, toRevisionId: string,
                   commandId: string): Observable<ArtifactSummary> {
        return defer(() => this.http.post<unknown>(`${this.artifact(deckId, sessionId, artifactId)}/revert`, {
            commandId: requireCommand(commandId), expectedArtifactVersion: requireVersion(expectedArtifactVersion),
            toRevisionId: requireEntity(toRevisionId)
        }, { observe: 'response' })).pipe(map(response => {
            requireStatus(response, 200);
            const artifact = parseArtifactSummary(response.body);
            if (artifact.artifactId !== artifactId.toLowerCase() || artifact.state !== 'PROPOSED'
                || artifact.currentRevisionId !== toRevisionId.toLowerCase()) throw new AuthoringProtocolError('Revert acknowledgement mismatch.');
            if (!replayHeader(response) && response.headers.has('ETag')) requireEtag(response, artifact.rowVersion);
            return artifact;
        }));
    }

    /**
     * Archives the notes the approved or handed-off materials were written from (`archiveUsedNotes`, no `If-Match`). A note that
     * changed since the pin is skipped by the server, never archived silently. An exact retry replays the stored answer.
     */
    archiveUsedNotes(deckId: string, sessionId: string, commandId: string): Observable<NoteArchiveResult> {
        return defer(() => this.http.post<unknown>(`${this.session(deckId, sessionId)}/note-archival`,
            { commandId: requireCommand(commandId) }, { observe: 'response' })).pipe(map(response => {
            requireStatus(response, 200);
            return parseNoteArchive(response.body, replayHeader(response));
        }));
    }

    private deckPath(deckId: string): string {
        return `${this.baseUrl}/decks/${encodeURIComponent(requireEntity(deckId))}`;
    }

    private sessions(deckId: string): string {
        return `${this.deckPath(deckId)}/generation-sessions`;
    }

    private session(deckId: string, sessionId: string): string {
        return `${this.sessions(deckId)}/${encodeURIComponent(requireEntity(sessionId))}`;
    }

    private artifact(deckId: string, sessionId: string, artifactId: string): string {
        return `${this.session(deckId, sessionId)}/artifacts/${encodeURIComponent(requireEntity(artifactId))}`;
    }

    private page(response: HttpResponse<unknown>): SessionPage {
        requireStatus(response, 200);
        return parseSessionPage(response.body);
    }

    private sessionDetail(response: HttpResponse<unknown>, sessionId: string): SessionDetail {
        requireStatus(response, 200);
        const session = parseSessionDetail(response.body);
        if (session.sessionId !== sessionId.toLowerCase()) throw new AuthoringProtocolError('Session identity does not match.');
        requireSessionEtag(response, session, replayHeader(response));
        return session;
    }

    private approval(response: HttpResponse<unknown>, deckId: string, commandId: string): ApprovalAck {
        requireStatus(response, 200);
        const replayed = replayHeader(response);
        const ack = parseApprovalAck(response.body, replayed);
        if (ack.commandId !== commandId || ack.deckId !== deckId.toLowerCase()) throw new AuthoringProtocolError('Approval mismatch.');
        if (replayed ? response.headers.has('ETag') : response.headers.get('ETag') !== expectedEtag(ack.deckVersion)) {
            throw new AuthoringProtocolError('Invalid approval ETag.');
        }
        return ack;
    }

    private summary(response: HttpResponse<unknown>, artifactId: string, state: ArtifactSummary['state']): ArtifactSummary {
        requireStatus(response, 200);
        const artifact = parseArtifactSummary(response.body);
        if (artifact.artifactId !== artifactId.toLowerCase() || artifact.state !== state) {
            throw new AuthoringProtocolError('Unexpected artifact state.');
        }
        if (replayHeader(response)) {
            if (response.headers.has('ETag')) throw new AuthoringProtocolError('A replay carries no ETag.');
        } else {
            requireEtag(response, artifact.rowVersion);
        }
        return artifact;
    }
}

/** The edited exercise as the server reads it; a command that does not read is a protocol error, never sent. */
function serializeReplacement(replacement: ApprovalReplacement): Record<string, unknown> {
    try {
        return { objective: parseObjectiveCommand(replacement.objective), exercise: parseExerciseSpec(replacement.exercise) };
    } catch (error) {
        if (error instanceof ExerciseContentError) throw new RequestValidationError(error.message);
        throw error;
    }
}

function requirePrivate(response: HttpResponse<unknown>): void {
    const cache = (response.headers.get('Cache-Control') ?? '').toLowerCase().split(',').map(value => value.trim());
    if (!cache.includes('private') || !cache.includes('no-store')) throw new AuthoringProtocolError('Generation response can be cached.');
}

function requireStatus(response: HttpResponse<unknown>, status: number): void {
    if (response.status !== status) throw new AuthoringProtocolError('Unexpected generation status.');
    requirePrivate(response);
}

function replayHeader(response: HttpResponse<unknown>): boolean {
    const value = response.headers.get('Idempotency-Replayed');
    if (value !== null && value !== 'true') throw new AuthoringProtocolError('Invalid replay header.');
    return value === 'true';
}

function requireEtag(response: HttpResponse<unknown>, version: string): void {
    if (response.headers.get('ETag') !== expectedEtag(version)) throw new AuthoringProtocolError('Invalid generation ETag.');
}

/** A stored replay carries no ETag; anything else must be the quoted session rowVersion. */
function requireSessionEtag(response: HttpResponse<unknown>, session: SessionDetail, replayed: boolean): void {
    if (replayed) {
        if (response.headers.has('ETag')) throw new AuthoringProtocolError('A replay carries no ETag.');
        return;
    }
    requireEtag(response, session.rowVersion);
}

function ifMatch(version: string): HttpHeaders {
    return new HttpHeaders({ 'If-Match': expectedEtag(requireVersion(version)) });
}
