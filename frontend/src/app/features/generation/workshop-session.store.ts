import { DOCUMENT } from '@angular/common';
import { DestroyRef, Injectable, Injector, computed, inject, signal } from '@angular/core';
import { Observable, Subscription, firstValueFrom } from 'rxjs';

import { ToastService } from '../../core/notifications/toast.service';
import { newCommandId } from '../authoring/authoring.models';
import { NativeDocument } from '../../content/native-document';
import { OwnDecksApiService } from '../own-decks/own-decks-api.service';
import { UsageApiService } from '../usage/usage-api.service';
import { DeckPin, GenerationApiService } from './generation-api.service';
import { GenerationProblem, readProblem } from './generation-problem';
import { REVERTED_NOTE, describeEditCost, describeNoteArchive, editOutcomeNote, editProblemMessage, problemMessage } from './generation-view';
import {
    ApprovalAck, ArtifactDetail, ArtifactSummary, ArtifactTurn, EditAction, EditPreset, GenerationEvent, HandoffResult, MAX_APPROVALS_PER_COMMAND,
    NoteArchiveResult, SessionDetail, UsageUpdate, ActiveStep,
    allows, isApprovable, isRetryable, isTerminalSession, sessionAllows
} from './generation.models';
import { AppliedEvents, Arrival, DraftBlocks, WorkshopModel, applyEvents, isNewer, mergeSession } from './workshop-events';

/** Polling cadence (`events.json` polling.clientCadence): 1 s while visible and working, 5-15 s in the background. */
export const POLL_ACTIVE_MS = 1_000;
export const POLL_IDLE_MS = 5_000;
export const POLL_BACKGROUND_MIN_MS = 5_000;
export const POLL_BACKGROUND_MAX_MS = 15_000;
export const POLL_FAILURE_MAX_MS = 15_000;
const EVENTS_PAGE = 100;

// Polling stops in CANCELLED as in CLOSED and EXPIRED: `events.json` polling.clientCadence says "stop when session.state is CLOSED,
// CANCELLED or EXPIRED". A cancelled session still lets the user approve, reject or hand off what was proposed; those commands
// answer with the new state and the store re-reads the session after each. A rewrite that is running when the session is cancelled
// ends with the cancellation itself (its turn is `CANCELLED`, the artifact is `PROPOSED` again, the hold is released): the answer of
// the cancellation shows the artifact proposed, and the artifact's detail, which still lists the turn as running, is read again
// (`needsDetail`). Caveat for AI-09: media of a proposal that is still being made would not be followed after a cancellation;
// revisit if cancellation can leave them running.
export type StorePhase = 'loading' | 'ready' | 'missing' | 'error';
/** `recovered` is shown for one cycle after the connection returns: «Связь восстановлена». */
export type Connection = 'online' | 'offline' | 'degraded' | 'recovered';

export interface DetailEntry {
    readonly phase: 'loading' | 'ready' | 'error';
    readonly detail: ArtifactDetail | null;
    /** The revision the summary named when this load was requested: a detail is reloaded only when that moves on. */
    readonly forRevision: string | null;
    /** The loaded detail is out of date (a media slot moved, a revision changed): load it again. */
    readonly stale: boolean;
}

export interface StoreNotice { readonly tone: 'error' | 'info'; readonly text: string; }

/** How long the sentence about the end of an edit stays in the summary line (the live region announces it once). */
export const EDIT_NOTE_MS = 8_000;

/** One edit as the Workshop sends it. `nodeIds` are consecutive top-level blocks of the revision on screen; the anchors bound them. */
export interface EditAsk {
    readonly action: EditAction;
    readonly nodeIds: readonly string[];
    /** The blocks just before and just after the run: they find the run again in the revision the edit makes. */
    readonly anchorBefore: string | null;
    readonly anchorAfter: string | null;
    readonly preset?: EditPreset | null;
    readonly instruction?: string | null;
    /** «Ещё раз»: the same request as a new command, which takes its own reservation. */
    readonly again?: boolean;
}

/** What this page remembers of the last rewrite it made on an artifact: the revision it started from, and whether its strip is closed. */
export interface EditMemo {
    readonly turnId: string;
    readonly ask: Omit<EditAsk, 'again'>;
    readonly baseRevisionId: string;
    readonly dismissed: boolean;
    /** The end of the turn was put into the summary line. */
    readonly announced: boolean;
}

export type EditOutcome =
    | { readonly ok: true; readonly turn: ArtifactTurn }
    | { readonly ok: false; readonly message: string; readonly aborted: boolean };

/** The cost line of a rewrite and whether the budget lets it start. */
export interface EditCost { readonly text: string; readonly canStart: boolean; }

/** Thrown into a command that the user took back before the server answered. */
class CommandAborted extends Error {}

/**
 * State and commands of one open Workshop. It is provided by the page, so it lives and dies with it: closing the page
 * stops the single polling loop and nothing else keeps running. Leaving and coming back loses nothing, because the
 * session, its artifacts and its events are all on the server (the loop replays the events from the start).
 *
 * Polling: one loop per session; 1 s while the tab is visible and a step is active, 5 s while it is visible and idle,
 * 5-15 s in a background tab, immediately on `visibilitychange` and `online`, stopped in a terminal session state
 * (`CLOSED`, `CANCELLED`, `EXPIRED`). The cursor only moves forward and events at or below it are skipped, so a replay never
 * duplicates a block. A failed poll backs off (1, 2, 4, 8, 15 s) and the page shows the connection state in words.
 */
@Injectable()
export class WorkshopSessionStore {
    private readonly api = inject(GenerationApiService);
    private readonly decks = inject(OwnDecksApiService);
    private readonly toast = inject(ToastService);
    private readonly destroyRef = inject(DestroyRef);
    private readonly document = inject(DOCUMENT);
    private readonly injector = inject(Injector);

    readonly phase = signal<StorePhase>('loading');
    readonly session = signal<SessionDetail | null>(null);
    readonly drafts = signal<Readonly<Record<string, DraftBlocks>>>({});
    readonly details = signal<Readonly<Record<string, DetailEntry>>>({});
    readonly arrival = signal<Arrival | null>(null);
    readonly usage = signal<UsageUpdate | null>(null);
    readonly activeSteps = signal<readonly ActiveStep[]>([]);
    readonly connection = signal<Connection>('online');
    readonly notice = signal<StoreNotice | null>(null);
    readonly deckTitle = signal<string | null>(null);
    /** Ids with a command in flight (an artifact id, or `session`). */
    readonly busy = signal<ReadonlySet<string>>(new Set());
    /** Drafts made by hand-off in this page, by artifact id: the link back to the editor names its draft. */
    readonly handoffs = signal<Readonly<Record<string, string>>>({});
    /** What the last «Архивировать использованные заметки» did (AI-08, #290); `null` until it ran in this page. */
    readonly noteArchive = signal<NoteArchiveResult | null>(null);
    /** The last rewrite made in this page, by artifact id (AI-11, #293): drives the strip, the diff and «Ещё раз». */
    readonly edits = signal<Readonly<Record<string, EditMemo>>>({});
    /** A sentence about how an edit ended; the page adds it to the summary line for a few seconds. */
    readonly editNote = signal<string | null>(null);

    readonly artifacts = computed(() => this.session()?.artifacts ?? []);
    readonly approvable = computed(() => this.artifacts().filter(isApprovable));
    /** Used notes that «Архивировать использованные» would archive now; 0 while the server does not report it. */
    readonly archivableNotes = computed(() => this.session()?.notes.archivable ?? 0);
    readonly terminal = computed(() => { const session = this.session(); return session !== null && isTerminalSession(session.state); });

    private deckId = '';
    private sessionId = '';
    private deckPin: DeckPin | null = null;
    private cursor = '0';
    private lastSeq = 0n;
    private token = 0;
    private timer: ReturnType<typeof setTimeout> | null = null;
    private poll: Subscription | null = null;
    private reading: Promise<void> | null = null;
    private readAgain = false;
    private failures = 0;
    private backgroundDelay = POLL_BACKGROUND_MIN_MS;
    private started = false;
    private disposed = false;
    /** Bumps on every `open` and on dispose: answers of an earlier open are dropped. */
    private epoch = 0;
    private readonly commandIds = new Map<string, string>();
    private readonly revisions = new Map<string, Promise<NativeDocument | null>>();
    private allowance: Promise<number | null> | null = null;
    private noteTimer: ReturnType<typeof setTimeout> | null = null;
    private editNonce = 0;
    private readonly staleWhileLoading = new Set<string>();
    private readonly onVisibility = (): void => this.visibilityChanged();
    private readonly onOnline = (): void => this.wake();
    private readonly onOffline = (): void => this.connection.set('offline');

    constructor() {
        this.destroyRef.onDestroy(() => this.dispose());
    }

    /** Opens the session: reads it and the deck, then starts the polling loop unless the session is already over. */
    open(deckId: string, sessionId: string): void {
        this.dispose(false);
        const epoch = ++this.epoch;
        this.deckPin = null;
        this.deckId = deckId.toLowerCase();
        this.sessionId = sessionId.toLowerCase();
        this.disposed = false;
        this.phase.set('loading');
        this.session.set(null);
        this.drafts.set({});
        this.details.set({});
        this.usage.set(null);
        this.notice.set(null);
        this.cursor = '0';
        this.lastSeq = 0n;
        this.failures = 0;
        this.commandIds.clear();
        this.staleWhileLoading.clear();
        this.handoffs.set({});
        this.noteArchive.set(null);
        this.edits.set({});
        this.setNote(null);
        this.revisions.clear();
        this.document.addEventListener('visibilitychange', this.onVisibility);
        const view = this.document.defaultView;
        view?.addEventListener('online', this.onOnline);
        view?.addEventListener('offline', this.onOffline);
        this.started = true;
        void this.refreshDeck();
        this.api.getSession(this.deckId, this.sessionId).subscribe({
            next: session => {
                if (epoch !== this.epoch) return;
                this.session.set(session);
                this.phase.set('ready');
                if (!isTerminalSession(session.state)) this.schedule(0);
            },
            error: (error: unknown) => {
                if (epoch !== this.epoch) return;
                this.phase.set(readProblem(error).status === 404 ? 'missing' : 'error');
            }
        });
    }

    /** Reads the session again after a failed first load. */
    retryLoad(): void {
        this.open(this.deckId, this.sessionId);
    }

    // --- Reads ---

    /** One read of the session; concurrent calls share it, and a call during a read asks for one more. */
    refresh(): Promise<void> {
        if (this.reading !== null) { this.readAgain = true; return this.reading; }
        const epoch = this.epoch;
        this.reading = firstValueFrom(this.api.getSession(this.deckId, this.sessionId)).then(
            fresh => {
                if (epoch !== this.epoch) return;
                this.session.set(mergeSession(this.session(), fresh));
                this.phase.set('ready');
                // An undo reopened a CLOSED session (#288): the loop stopped at CLOSED and must run again.
                if (!isTerminalSession(fresh.state) && this.timer === null && this.poll === null && this.started && !this.disposed) this.schedule(POLL_IDLE_MS);
            },
            (error: unknown) => {
                if (epoch === this.epoch && readProblem(error).status === 404) this.gone();
            }
        ).finally(() => {
            this.reading = null;
            if (this.readAgain) { this.readAgain = false; void this.refresh(); }
        });
        return this.reading;
    }

    /** True when the page should load the detail of `artifact`: it has a revision to show and none (or an old one) is held. */
    needsDetail(artifact: ArtifactSummary): boolean {
        if (artifact.currentRevisionId === null || artifact.state === 'PUBLISHED' || artifact.state === 'HANDED_OFF') return false;
        const entry = this.details()[artifact.artifactId];
        if (entry === undefined) return true;
        if (entry.phase === 'loading' || entry.phase === 'error') return false;
        // A turn still listed as running while the artifact is proposed again has ended (a failed or stopped rewrite leaves the
        // revision where it was, so nothing else would tell): read it again.
        const unfinished = entry.detail?.turns.some(turn => turn.status === 'QUEUED' || turn.status === 'RUNNING') ?? false;
        return entry.stale || entry.forRevision !== artifact.currentRevisionId || (unfinished && artifact.state === 'PROPOSED');
    }

    loadDetail(artifactId: string): void {
        const entry = this.details()[artifactId];
        const epoch = this.epoch;
        const forRevision = this.find(artifactId)?.currentRevisionId ?? null;
        const held = entry?.detail ?? null;
        this.staleWhileLoading.delete(artifactId);
        this.setDetail(artifactId, { phase: 'loading', detail: held, forRevision, stale: false });
        this.api.getArtifact(this.deckId, this.sessionId, artifactId).subscribe({
            next: detail => {
                // A media slot that moved while this was on its way makes the answer old on arrival: read again.
                if (epoch !== this.epoch) return;
                this.setDetail(artifactId, { phase: 'ready', detail, forRevision, stale: this.staleWhileLoading.delete(artifactId) });
                this.observeEdit(artifactId, detail);
            },
            error: (error: unknown) => {
                if (epoch !== this.epoch) return;
                this.setDetail(artifactId, { phase: 'error', detail: held, forRevision, stale: false });
                if (readProblem(error).status === 404) this.gone();
            }
        });
    }

    // --- Commands ---

    /** Approves one artifact exactly as shown. Resolves `true` when it is in the deck. */
    async approve(artifactId: string): Promise<boolean> {
        const artifact = this.find(artifactId);
        const session = this.session();
        if (artifact === null || session === null || this.isBusy(artifactId)) return false;
        if (!allows(session.state, artifact.state, 'approveArtifact') || !isApprovable(artifact)) return false;
        this.begin(artifactId);
        try {
            for (let attempt = 0; attempt < 2; attempt++) {
                const current = this.find(artifactId)!;
                const shown = this.details()[artifactId]?.detail;
                if (shown?.currentRevisionId !== current.currentRevisionId || current.currentRevisionId === null) {
                    this.notice.set({ tone: 'info', text: 'Материал обновился. Проверьте новую версию и одобрите её.' });
                    return false;
                }
                const pin = await this.pin();
                if (pin === null) return false;
                const outcome = await this.send('approve', `${artifactId}:${current.rowVersion}:${current.currentRevisionId}:${pin.rowVersion}`,
                    id => this.api.approveArtifact(this.deckId, this.sessionId, { artifactId, expectedArtifactVersion: current.rowVersion,
                        expectedRevisionId: current.currentRevisionId! }, pin, id));
                if (outcome.ok) {
                    this.published(outcome.value);
                    this.toast.echo('Материал одобрен');
                    return true;
                }
                if (outcome.problem.status === 412 && attempt === 0) {
                    // The deck or the artifact moved. When the proposal is the same one, the user's decision still holds.
                    await Promise.all([this.refreshDeck(), this.refresh()]);
                    const refreshed = this.find(artifactId);
                    if (refreshed !== null && refreshed.currentRevisionId === current.currentRevisionId && isApprovable(refreshed)) continue;
                }
                this.failed(outcome.problem);
                return false;
            }
            return false;
        } finally {
            this.end(artifactId);
        }
    }

    /** Approves every approvable artifact, 20 per atomic command. Resolves with the number published. */
    async approveAll(): Promise<number> {
        const session = this.session();
        if (session === null || this.isBusy('session') || !sessionAllows(session.state, 'approveArtifacts')) return 0;
        const targets = this.approvable();
        if (targets.length === 0) return 0;
        this.begin('session');
        let published = 0;
        try {
            for (let start = 0; start < targets.length; start += MAX_APPROVALS_PER_COMMAND) {
                const chunk = targets.slice(start, start + MAX_APPROVALS_PER_COMMAND);
                const pin = await this.pin();
                if (pin === null) break;
                const key = chunk.map(artifact => `${artifact.artifactId}:${artifact.rowVersion}`).join(',') + `:${pin.rowVersion}`;
                const outcome = await this.send('approve-all', key, id => this.api.approveArtifacts(this.deckId, this.sessionId,
                    chunk.map(artifact => ({ artifactId: artifact.artifactId, expectedArtifactVersion: artifact.rowVersion,
                        expectedRevisionId: artifact.currentRevisionId! })), pin, id));
                if (!outcome.ok) { this.failed(outcome.problem, published); break; }
                this.published(outcome.value);
                published += outcome.value.artifacts.length;
            }
        } finally {
            this.end('session');
        }
        if (published > 0) this.toast.echo(published === 1 ? 'Материал одобрен' : `Одобрено материалов: ${published}`);
        return published;
    }

    /**
     * Saves the chosen proposals of an exercise batch (AI-13), 20 per atomic command, each chunk its own `commandId`. The deck pin
     * is chained: the next chunk goes out with the version and revision the previous acknowledgement returned. A `412` (the deck
     * or an artifact moved) refreshes both and tries a chunk once more when its proposals are unchanged. Resolves with the number
     * of exercises that are now in the deck; a refusal is reported through `notice` and stops the rest.
     */
    async approveSelected(artifactIds: readonly string[]): Promise<number> {
        const session = this.session();
        if (session === null || this.isBusy('session') || !sessionAllows(session.state, 'approveArtifacts')) return 0;
        const wanted = new Set(artifactIds);
        // Only what the user was shown as it is now, like a single approval: a loaded revision that is not the current one, or a load
        // marked out of date, is never published unseen.
        const targets = this.approvable().filter(artifact => wanted.has(artifact.artifactId) && this.shownAsCurrent(artifact))
            .map(artifact => artifact.artifactId);
        if (targets.length === 0) return 0;
        this.begin('session');
        let published = 0;
        try {
            for (let start = 0; start < targets.length; start += MAX_APPROVALS_PER_COMMAND) {
                const result = await this.approveChunk(targets.slice(start, start + MAX_APPROVALS_PER_COMMAND));
                if (!result.ok) {
                    // `null`: the chunk was never sent and the notice already says why.
                    if (result.problem !== null) this.failed(result.problem, published);
                    break;
                }
                published += result.count;
            }
        } finally {
            this.end('session');
        }
        if (published > 0) this.toast.echo(`Новые упражнения: ${published} — уже в колоде`);
        return published;
    }

    /** Rejects proposals one by one (each is its own command); resolves with how many were rejected. */
    async rejectMany(artifactIds: readonly string[]): Promise<number> {
        let rejected = 0;
        for (const artifactId of artifactIds) {
            if (await this.reject(artifactId)) rejected += 1;
        }
        return rejected;
    }

    async reject(artifactId: string): Promise<boolean> {
        const artifact = this.find(artifactId);
        const session = this.session();
        if (artifact === null || session === null || this.isBusy(artifactId) || !allows(session.state, artifact.state, 'rejectArtifact')) return false;
        return this.summaryCommand(artifactId, 'reject', artifact.rowVersion,
            id => this.api.rejectArtifact(this.deckId, this.sessionId, artifactId, artifact.rowVersion, id));
    }

    async undoReject(artifactId: string): Promise<boolean> {
        const artifact = this.find(artifactId);
        const session = this.session();
        if (artifact === null || session === null || this.isBusy(artifactId) || !allows(session.state, artifact.state, 'undoRejectArtifact')) return false;
        return this.summaryCommand(artifactId, 'undo', artifact.rowVersion,
            () => this.api.undoRejectArtifact(this.deckId, this.sessionId, artifactId, artifact.rowVersion));
    }

    async retry(artifactId: string): Promise<boolean> {
        const artifact = this.find(artifactId);
        const session = this.session();
        if (artifact === null || session === null || this.isBusy(artifactId) || !isRetryable(artifact)
            || !allows(session.state, artifact.state, 'retryArtifact')) return false;
        const ok = await this.summaryCommand(artifactId, 'retry', artifact.rowVersion,
            id => this.api.retryArtifact(this.deckId, this.sessionId, artifactId, artifact.rowVersion, id));
        // A retry puts the session back to work: make sure the loop runs again.
        if (ok) this.wake();
        return ok;
    }

    /** Opens the shown revision in the editor: resolves with the new draft, or `null` when the command failed. */
    async handoff(artifactId: string): Promise<HandoffResult | null> {
        const artifact = this.find(artifactId);
        const session = this.session();
        const shown = this.details()[artifactId]?.detail;
        if (artifact === null || session === null || this.isBusy(artifactId) || shown?.currentRevisionId == null
            || shown.currentRevisionId !== artifact.currentRevisionId
            || !allows(session.state, artifact.state, 'handoffArtifact')) return null;
        this.begin(artifactId);
        try {
            const revisionId = artifact.currentRevisionId!;
            const outcome = await this.send('handoff', `${artifactId}:${artifact.rowVersion}:${revisionId}`,
                id => this.api.handoffArtifact(this.deckId, this.sessionId, { artifactId, expectedArtifactVersion: artifact.rowVersion,
                    expectedRevisionId: revisionId }, id));
            if (!outcome.ok) { this.failed(outcome.problem); return null; }
            this.patch(outcome.value.artifact);
            this.handoffs.update(known => ({ ...known, [artifactId]: outcome.value.draft.draftId }));
            void this.refresh();
            return outcome.value;
        } finally {
            this.end(artifactId);
        }
    }

    /**
     * Archives the notes the approved and handed-off materials were written from. The server skips a note that changed since
     * the pin; the answer says what was archived and what was skipped and why. An unknown outcome is retried with the same
     * command (the server replays its stored answer), so pressing again never archives twice.
     */
    async archiveNotes(): Promise<boolean> {
        if (this.session() === null || this.archivableNotes() === 0 || this.isBusy('notes')) return false;
        this.begin('notes');
        try {
            const outcome = await this.send('archive-notes', this.sessionId, id => this.api.archiveUsedNotes(this.deckId, this.sessionId, id));
            if (!outcome.ok) { this.failed(outcome.problem); return false; }
            this.noteArchive.set(outcome.value);
            this.toast.echo(describeNoteArchive(outcome.value));
            await this.refresh();
            return true;
        } finally {
            this.end('notes');
        }
    }

    /** Stops the session: waiting work is cancelled, finished proposals stay approvable. */
    async cancel(): Promise<boolean> {
        const session = this.session();
        if (session === null || this.isBusy('session') || !sessionAllows(session.state, 'cancelSession')) return false;
        this.begin('session');
        try {
            const outcome = await this.send('cancel', session.rowVersion, id => this.api.cancelSession(this.deckId, this.sessionId, id));
            if (!outcome.ok) { this.failed(outcome.problem); return false; }
            this.session.set(mergeSession(this.session(), outcome.value));
            this.stopPolling();
            this.notice.set(null);
            return true;
        } finally {
            this.end('session');
        }
    }

    /** Hold-to-delete of the whole Workshop; published content stays in the deck. */
    async deleteSession(): Promise<boolean> {
        if (this.isBusy('session')) return false;
        this.begin('session');
        try {
            await firstValueFrom(this.api.deleteSession(this.deckId, this.sessionId));
            this.stopPolling();
            return true;
        } catch (error) {
            const problem = readProblem(error);
            // The first call answers 204 and later ones 404: a repeat after a lost answer is a success.
            if (problem.status === 404) { this.stopPolling(); return true; }
            this.failed(problem);
            return false;
        } finally {
            this.end('session');
        }
    }

    // --- Selection edits (AI-11, #293) ---

    /**
     * Asks Мнема to change the blocks `ask` names, on the revision the user has on screen. Resolves when the server has answered:
     * a rewrite is only accepted (the artifact is `REVISING` and the turn `QUEUED`; the end comes through the events and the next
     * read of the artifact), `REMOVE_MEDIA` is applied at once. A refusal is returned in words for the window, never put in the page
     * notice. `signal` takes an unsent request back: the command id is kept, so asking again replays the answer.
     */
    async edit(artifactId: string, ask: EditAsk, signal?: AbortSignal): Promise<EditOutcome> {
        const artifact = this.find(artifactId);
        const session = this.session();
        const shown = this.details()[artifactId]?.detail;
        const refuse = (message: string): EditOutcome => ({ ok: false, message, aborted: false });
        if (artifact === null || session === null) return refuse('Материал больше недоступен.');
        if (this.isBusy(artifactId)) return refuse('Подождите: предыдущее действие ещё выполняется.');
        const rewrite = ask.action === 'REWRITE' || ask.action === 'FREE';
        if (!allows(session.state, artifact.state, 'editArtifact') || (rewrite && session.state === 'CANCELLED')) {
            return refuse('Сейчас этот материал нельзя править: состояние изменилось.');
        }
        const expected = shown?.currentRevisionId ?? null;
        if (expected === null || expected !== artifact.currentRevisionId || this.details()[artifactId]?.stale === true) {
            return refuse('Материал обновился. Подождите новую версию и выделите фрагмент заново.');
        }
        this.begin(artifactId);
        try {
            const key = ask.again === true ? `again:${++this.editNonce}`
                : [expected, ask.action, ask.preset ?? '', ask.instruction ?? '', ask.nodeIds.join(',')].join('|');
            const outcome = await this.send('edit', `${artifactId}:${key}`, id => this.api.editArtifact(this.deckId, this.sessionId, artifactId,
                { expectedRevisionId: expected, action: ask.action, nodeIds: ask.nodeIds, preset: ask.preset ?? null,
                    instruction: ask.instruction ?? null }, id), signal);
            if (!outcome.ok) {
                if (outcome.aborted) {
                    // The server may have taken it: read the truth, and the turn it made, if any.
                    void this.refresh();
                    this.markStale(artifactId);
                    return { ok: false, message: '', aborted: true };
                }
                const problem = outcome.problem;
                if (problem.status === 404) this.gone();
                else if (!problem.uncertain && problem.status !== 400) { void this.refresh(); this.markStale(artifactId); }
                return refuse(editProblemMessage(problem));
            }
            const { turn, artifact: accepted } = outcome.value;
            this.patch(accepted);
            this.addTurn(artifactId, turn);
            if (turn.status === 'APPLIED') {
                this.setNote(editOutcomeNote('APPLIED', turn.action));
            } else {
                const { again: _again, ...stored } = ask;
                this.edits.update(memos => ({ ...memos, [artifactId]: { turnId: turn.turnId, ask: stored, baseRevisionId: expected,
                    dismissed: false, announced: false } }));
                // The turn may already have ended and been read (see `addTurn`): then this is the only moment to say so.
                const read = this.details()[artifactId]?.detail;
                if (read != null) this.observeEdit(artifactId, read);
                this.wake();
            }
            void this.refresh();
            return { ok: true, turn };
        } finally {
            this.end(artifactId);
        }
    }

    /**
     * Moves the artifact back (or forward) to `toRevisionId`, one of the revisions the artifact lists: nothing is deleted, so the
     * move can be undone. Resolves `true` when the server did it; a refusal goes to the page notice.
     */
    async revert(artifactId: string, toRevisionId: string): Promise<boolean> {
        const artifact = this.find(artifactId);
        const session = this.session();
        if (artifact === null || session === null || this.isBusy(artifactId) || !allows(session.state, artifact.state, 'revertArtifact')) return false;
        this.begin(artifactId);
        try {
            const outcome = await this.send('revert', `${artifactId}:${artifact.rowVersion}:${toRevisionId}`,
                id => this.api.revertArtifact(this.deckId, this.sessionId, artifactId, artifact.rowVersion, toRevisionId, id));
            if (!outcome.ok) { this.failed(outcome.problem); return false; }
            this.patch(outcome.value);
            this.dismissEdit(artifactId);
            this.setNote(REVERTED_NOTE);
            void this.refresh();
            return true;
        } finally {
            this.end(artifactId);
        }
    }

    /** Closes the strip under a rewritten range («Оставить», or after «Вернуть»). */
    dismissEdit(artifactId: string): void {
        const memo = this.edits()[artifactId];
        if (memo !== undefined && !memo.dismissed) this.edits.update(memos => ({ ...memos, [artifactId]: { ...memo, dismissed: true } }));
    }

    /** The document of one revision of an artifact (a revision never changes, so each is read once). `null` when it cannot be read. */
    loadRevision(artifactId: string, revisionId: string): Promise<NativeDocument | null> {
        const key = `${artifactId}:${revisionId}`;
        let held = this.revisions.get(key);
        if (held === undefined) {
            const epoch = this.epoch;
            held = firstValueFrom(this.api.getArtifact(this.deckId, this.sessionId, artifactId, revisionId))
                .then(detail => detail.revision?.payload.kind === 'NATIVE_DOCUMENT' ? detail.revision.payload.document : null)
                .catch(() => null);
            this.revisions.set(key, held);
            void held.then(document => { if (document === null && epoch === this.epoch) this.revisions.delete(key); });
        }
        return held;
    }

    /** The cost line of a rewrite of `blocks` blocks: «≈ 0,3 % лимита». `null` when the estimate cannot be read (the window then says nothing). */
    async editCost(artifactId: string, blocks: number): Promise<EditCost | null> {
        const epoch = this.epoch;
        try {
            const estimate = await firstValueFrom(this.api.estimateEdit(this.deckId, { sessionId: this.sessionId, artifactId, action: 'REWRITE',
                targetNodeCount: Math.min(blocks, 50) }));
            const allowance = await this.budgetAllowance();
            if (epoch !== this.epoch) return null;
            return { text: describeEditCost(estimate, allowance), canStart: estimate.canStart };
        } catch {
            return null;
        }
    }

    clearNotice(): void {
        this.notice.set(null);
    }

    /** A refusal the window cannot show (the strip or the media actions have no field of their own) goes to the page notice. */
    notify(text: string): void {
        this.notice.set({ tone: 'error', text });
    }

    isBusy(key: string): boolean {
        return this.busy().has(key);
    }

    // --- Polling ---

    /** Polls now instead of at the scheduled time. */
    wake(): void {
        if (!this.started || this.disposed) return;
        const session = this.session();
        if (session !== null && !isTerminalSession(session.state)) this.schedule(0);
    }

    private schedule(delay: number): void {
        if (this.disposed || !this.started) return;
        this.clearTimer();
        this.timer = setTimeout(() => { this.timer = null; this.pollOnce(); }, delay);
    }

    private pollOnce(): void {
        if (this.disposed || this.poll !== null || this.session() === null) return;
        const subscription = this.api.listEvents(this.deckId, this.sessionId, this.cursor, EVENTS_PAGE).subscribe({
            next: page => {
                this.poll = null;
                if (this.disposed) return;
                this.failures = 0;
                this.connection.update(state => state === 'recovered' ? 'online' : state === 'online' ? 'online' : 'recovered');
                this.activeSteps.set(page.activeSteps);
                const applied = this.fold(page.events);
                if (isNewer(page.cursor, this.cursor)) this.cursor = page.cursor;
                const session = this.session()!;
                const behind = isNewer(page.session.rowVersion, session.rowVersion) || page.session.state !== session.state;
                if (applied.reconcile || behind || page.unreadable > 0) void this.refresh();
                // A full page means more events are waiting: read them without pausing.
                if (page.events.length >= EVENTS_PAGE) { this.schedule(0); return; }
                this.scheduleNext(page.events.length > 0 || applied.reconcile, page.session.state);
            },
            error: (error: unknown) => {
                this.poll = null;
                if (this.disposed) return;
                const problem = readProblem(error);
                if (problem.status === 404) { this.gone(); return; }
                this.failures += 1;
                this.connection.set(problem.status === 0 ? 'offline' : 'degraded');
                this.schedule(Math.min(1_000 * 2 ** (this.failures - 1), POLL_FAILURE_MAX_MS));
            }
        });
        // `next` may already have run synchronously.
        if (!subscription.closed) this.poll = subscription;
    }

    private fold(events: readonly GenerationEvent[]): AppliedEvents {
        const session = this.session()!;
        const applied = applyEvents({ session, drafts: this.drafts(), usage: this.usage(), arrival: this.arrival() }, events,
            this.lastSeq, () => ++this.token);
        this.lastSeq = applied.lastSeq;
        this.commit(applied.model);
        for (const id of applied.staleArtifacts) {
            const entry = this.details()[id];
            if (entry?.phase === 'loading') this.staleWhileLoading.add(id);
            else if (entry !== undefined && !entry.stale) this.setDetail(id, { ...entry, stale: true });
        }
        return applied;
    }

    private commit(model: WorkshopModel): void {
        this.session.set(model.session);
        this.drafts.set(model.drafts);
        this.usage.set(model.usage);
        this.arrival.set(model.arrival);
    }

    private scheduleNext(changed: boolean, state: SessionDetail['state']): void {
        if (isTerminalSession(state)) { this.stopPolling(); return; }
        if (this.document.visibilityState === 'hidden') {
            if (changed) this.backgroundDelay = POLL_BACKGROUND_MIN_MS;
            this.schedule(this.backgroundDelay);
            // Nothing new: the next wait is longer, up to the ceiling.
            if (!changed) this.backgroundDelay = Math.min(Math.round(this.backgroundDelay * 1.5), POLL_BACKGROUND_MAX_MS);
            return;
        }
        this.backgroundDelay = POLL_BACKGROUND_MIN_MS;
        // A rewrite in flight (an EDIT step is queued before a worker claims it) is followed at the busy cadence too.
        const working = state === 'PLANNING' || state === 'RUNNING' || this.activeSteps().length > 0
            || this.artifacts().some(artifact => artifact.state === 'REVISING');
        this.schedule(working ? POLL_ACTIVE_MS : POLL_IDLE_MS);
    }

    private visibilityChanged(): void {
        if (!this.started || this.disposed || this.terminal()) return;
        if (this.document.visibilityState === 'visible') {
            this.backgroundDelay = POLL_BACKGROUND_MIN_MS;
            if (this.poll === null) this.schedule(0);
        } else if (this.poll === null && this.timer !== null) {
            this.schedule(this.backgroundDelay);
        }
    }

    private stopPolling(): void {
        this.clearTimer();
        this.poll?.unsubscribe();
        this.poll = null;
    }

    private clearTimer(): void {
        if (this.timer !== null) clearTimeout(this.timer);
        this.timer = null;
    }

    private dispose(final = true): void {
        if (final) { this.disposed = true; this.epoch++; }
        this.stopPolling();
        if (this.noteTimer !== null) { clearTimeout(this.noteTimer); this.noteTimer = null; }
        if (!this.started) return;
        this.document.removeEventListener('visibilitychange', this.onVisibility);
        const view = this.document.defaultView;
        view?.removeEventListener('online', this.onOnline);
        view?.removeEventListener('offline', this.onOffline);
        this.started = false;
    }

    // --- Internals ---

    private gone(): void {
        this.phase.set('missing');
        this.stopPolling();
    }

    private find(artifactId: string): ArtifactSummary | null {
        return this.artifacts().find(artifact => artifact.artifactId === artifactId) ?? null;
    }

    private begin(key: string): void {
        this.notice.set(null);
        this.busy.update(set => new Set(set).add(key));
    }

    private end(key: string): void {
        this.busy.update(set => { const next = new Set(set); next.delete(key); return next; });
    }

    private setDetail(artifactId: string, entry: DetailEntry): void {
        this.details.update(details => ({ ...details, [artifactId]: entry }));
    }

    /** The deck version approvals are pinned to; read on first use and after every approval it is the acknowledged one. */
    private async pin(): Promise<DeckPin | null> {
        if (this.deckPin === null) await this.refreshDeck();
        if (this.deckPin === null) this.notice.set({ tone: 'error', text: 'Не удалось прочитать колоду. Попробуйте ещё раз.' });
        return this.deckPin;
    }

    private async refreshDeck(): Promise<void> {
        const epoch = this.epoch;
        try {
            const deck = await firstValueFrom(this.decks.detail(this.deckId));
            if (epoch !== this.epoch) return;
            this.deckTitle.set(deck.metadata.title);
            this.deckPin = { rowVersion: deck.rowVersion, revisionId: deck.revisionId };
        } catch {
            // The title is decoration; a missing pin is reported when an approval needs it.
        }
    }

    /**
     * Sends one command. An exact retry after an unknown outcome reuses its `commandId` (the server replays the stored
     * answer); a definitive answer, success or refusal, ends that command.
     */
    private async send<T>(kind: string, key: string, request: (commandId: string) => Observable<T>, signal?: AbortSignal):
        Promise<{ readonly ok: true; readonly value: T } | { readonly ok: false; readonly problem: GenerationProblem; readonly aborted: boolean }> {
        const id = `${kind}:${key}`;
        const commandId = this.commandIds.get(id) ?? newCommandId();
        this.commandIds.set(id, commandId);
        try {
            const value = await (signal === undefined ? firstValueFrom(request(commandId)) : abortable(request(commandId), signal));
            this.commandIds.delete(id);
            return { ok: true, value };
        } catch (error) {
            const problem = readProblem(error);
            if (!problem.uncertain) this.commandIds.delete(id);
            return { ok: false, problem, aborted: error instanceof CommandAborted };
        }
    }

    private markStale(artifactId: string): void {
        const entry = this.details()[artifactId];
        if (entry === undefined) return;
        if (entry.phase === 'loading') this.staleWhileLoading.add(artifactId);
        else this.setDetail(artifactId, { ...entry, stale: true });
    }

    /** Puts the turn the server just accepted into the detail that is on screen, so its blocks show as being rewritten at once. */
    private addTurn(artifactId: string, turn: ArtifactTurn): void {
        const entry = this.details()[artifactId];
        if (entry?.detail == null) return;
        // A turn that fails at once can be reported by the events, and read, before this answer arrives: never put it back to «queued».
        if (entry.detail.turns.some(held => held.turnId === turn.turnId)) return;
        const turns = [...entry.detail.turns.filter(held => held.turnId !== turn.turnId), turn];
        this.setDetail(artifactId, { ...entry, detail: { ...entry.detail, turns } });
    }

    /** When a read shows the turn this page started has ended, says so once in the summary line. */
    private observeEdit(artifactId: string, detail: ArtifactDetail): void {
        const memo = this.edits()[artifactId];
        if (memo === undefined || memo.announced) return;
        const turn = detail.turns.find(held => held.turnId === memo.turnId);
        if (turn === undefined || turn.status === 'QUEUED' || turn.status === 'RUNNING') return;
        this.edits.update(memos => ({ ...memos, [artifactId]: { ...memo, announced: true } }));
        this.setNote(editOutcomeNote(turn.status, turn.action));
    }

    private setNote(note: string | null): void {
        if (this.noteTimer !== null) { clearTimeout(this.noteTimer); this.noteTimer = null; }
        this.editNote.set(note);
        if (note !== null) this.noteTimer = setTimeout(() => { this.noteTimer = null; this.editNote.set(null); }, EDIT_NOTE_MS);
    }

    /** The whole period allowance of the account, the figure a percent of the limit is measured against; read once, `null` when unreadable. */
    private budgetAllowance(): Promise<number | null> {
        this.allowance ??= firstValueFrom(this.injector.get(UsageApiService).load()).then(usage => usage.credits.total, () => null);
        return this.allowance;
    }

    private shownAsCurrent(artifact: ArtifactSummary): boolean {
        const entry = this.details()[artifact.artifactId];
        return entry?.detail != null && !entry.stale && entry.detail.currentRevisionId === artifact.currentRevisionId;
    }

    /** One bulk command for `ids`; a `412` is retried once when every proposal in it is still the one that was sent. */
    private async approveChunk(ids: readonly string[]):
        Promise<{ readonly ok: true; readonly count: number } | { readonly ok: false; readonly problem: GenerationProblem | null }> {
        let sent = ids.map(id => this.find(id)).filter((artifact): artifact is ArtifactSummary => artifact !== null && isApprovable(artifact));
        for (let attempt = 0; attempt < 2; attempt++) {
            const pin = await this.pin();
            if (pin === null) return { ok: false, problem: null };
            if (sent.length === 0) {
                this.notice.set({ tone: 'info', text: 'Эти упражнения уже недоступны: состояние изменилось. Мы обновили список.' });
                return { ok: false, problem: null };
            }
            const key = sent.map(artifact => `${artifact.artifactId}:${artifact.rowVersion}`).join(',') + `:${pin.rowVersion}`;
            const outcome = await this.send('approve-selected', key, id => this.api.approveArtifacts(this.deckId, this.sessionId,
                sent.map(artifact => ({ artifactId: artifact.artifactId, expectedArtifactVersion: artifact.rowVersion,
                    expectedRevisionId: artifact.currentRevisionId! })), pin, id));
            if (outcome.ok) { this.published(outcome.value); return { ok: true, count: outcome.value.artifacts.length }; }
            if (outcome.problem.status === 412 && attempt === 0) {
                await Promise.all([this.refreshDeck(), this.refresh()]);
                const unchanged = sent.every(artifact => {
                    const now = this.find(artifact.artifactId);
                    return now !== null && isApprovable(now) && now.currentRevisionId === artifact.currentRevisionId;
                });
                if (unchanged) { sent = sent.map(artifact => this.find(artifact.artifactId)!); continue; }
            }
            return outcome;
        }
        return { ok: false, problem: null };
    }

    private async summaryCommand(artifactId: string, kind: string, version: string,
                                 request: (commandId: string) => Observable<ArtifactSummary>): Promise<boolean> {
        this.begin(artifactId);
        try {
            const outcome = await this.send(kind, `${artifactId}:${version}`, request);
            if (!outcome.ok) { this.failed(outcome.problem); return false; }
            this.patch(outcome.value);
            void this.refresh();
            return true;
        } finally {
            this.end(artifactId);
        }
    }

    private patch(artifact: ArtifactSummary): void {
        const session = this.session();
        if (session === null) return;
        this.session.set({ ...session, artifacts: session.artifacts.map(held =>
            held.artifactId === artifact.artifactId && !isNewer(held.rowVersion, artifact.rowVersion) ? artifact : held) });
    }

    private published(ack: ApprovalAck): void {
        this.deckPin = { rowVersion: ack.deckVersion, revisionId: ack.deckRevisionId };
        const session = this.session();
        if (session !== null) {
            this.session.set({ ...session, artifacts: session.artifacts.map(artifact => {
                const done = ack.artifacts.find(candidate => candidate.artifactId === artifact.artifactId);
                return done === undefined ? artifact : { ...artifact, state: 'PUBLISHED', publishedRef: done.publishedRef };
            }) });
        }
        void this.refresh();
    }

    private failed(problem: GenerationProblem, published = 0): void {
        const kind = this.session()?.kind ?? 'MATERIALS';
        const prefix = published > 0 ? (kind === 'EXERCISES' ? `Сохранено упражнений: ${published}. ` : `Одобрено материалов: ${published}. `) : '';
        this.notice.set({ tone: 'error', text: prefix + problemMessage(problem, kind) });
        if (problem.status === 404) { this.gone(); return; }
        // The state moved under the user's hands: show the server's truth.
        if (!problem.uncertain && problem.status !== 400) { void this.refresh(); void this.refreshDeck(); }
        for (const id of problem.artifactIds) {
            const entry = this.details()[id];
            if (entry !== undefined) this.setDetail(id, { ...entry, stale: true });
        }
    }
}

/** Awaits the first value of `source`; `signal` takes the request back, which cancels the subscription and rejects. */
function abortable<T>(source: Observable<T>, signal: AbortSignal): Promise<T> {
    return new Promise<T>((resolve, reject) => {
        if (signal.aborted) { reject(new CommandAborted()); return; }
        const subscription = source.subscribe({ next: resolve, error: reject, complete: () => reject(new Error('The request ended without an answer.')) });
        signal.addEventListener('abort', () => { subscription.unsubscribe(); reject(new CommandAborted()); }, { once: true });
    });
}
