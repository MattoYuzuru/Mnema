import { MECHANICS, Mechanic } from '../../content/exercise/exercise-content.models';
import { NativeDocument, NativeNode } from '../../content/native-document';
import { readRetainedNativeDocument } from '../../content/native-document-boundary';
import {
    AuthoringProtocolError, requireCommand, requireCount, requireCursor, requireEntity, requireInstant, requireObject,
    requireVersion
} from '../authoring/authoring.models';

/**
 * Wire and view models of the generation contract (`contracts/generation`, `generation-v1`): sessions, artifacts,
 * polling events and the Materials spec the composer sends. Responses are parsed strictly (exact key sets, enums,
 * decimal-string versions) like the other API services; events of an unknown type are the one deliberate exception
 * (`events.json`: "A client ignores unknown types").
 */

/**
 * A request the client refused to build (a spec or an edited exercise that does not satisfy the contract). Nothing was sent, so the
 * outcome is not unknown: it is reported as a validation problem, never as a command to retry.
 */
export class RequestValidationError extends AuthoringProtocolError {}

export const SESSION_KINDS = ['MATERIALS', 'EXERCISES', 'REVISE_ITEM', 'REVISE_EXERCISE'] as const;
export type SessionKind = (typeof SESSION_KINDS)[number];

export const SESSION_STATES = ['PLANNING', 'PLAN_READY', 'RUNNING', 'REVIEW', 'CLOSED', 'CANCELLED', 'EXPIRED'] as const;
export type SessionState = (typeof SESSION_STATES)[number];

export const ARTIFACT_STATES = ['QUEUED', 'GENERATING', 'PROPOSED', 'REVISING', 'FAILED', 'REJECTED', 'STALE',
    'PUBLISHED', 'HANDED_OFF'] as const;
export type ArtifactState = (typeof ARTIFACT_STATES)[number];

export const ARTIFACT_ERROR_CODES = ['INVALID_OUTPUT', 'REFUSAL', 'SOURCE_UNAVAILABLE', 'PROVIDER_UNAVAILABLE',
    'USAGE_LIMIT', 'ESTIMATE_EXCEEDED', 'DEADLINE_EXCEEDED', 'CANCELLED', 'PLAN_FAILED'] as const;
export type ArtifactErrorCode = (typeof ARTIFACT_ERROR_CODES)[number];

export const SLOT_STATES = ['PENDING', 'GENERATING', 'VERIFYING', 'READY', 'FAILED', 'REMOVED'] as const;
export type SlotState = (typeof SLOT_STATES)[number];
export const SLOT_KINDS = ['AUDIO', 'IMAGE', 'VIDEO'] as const;
export type SlotKind = (typeof SLOT_KINDS)[number];
const SLOT_ERROR_CODES = ['PROVIDER_UNAVAILABLE', 'NO_RESULT', 'VERIFICATION_REJECTED', 'DEADLINE_EXCEEDED', 'USAGE_LIMIT',
    'ESTIMATE_EXCEEDED', 'CANCELLED'] as const;
export type SlotErrorCode = (typeof SLOT_ERROR_CODES)[number];

const REPIN_STATUSES = ['AUTO_REPINNED', 'NEEDS_USER_DECISION'] as const;
export type RepinStatus = (typeof REPIN_STATUSES)[number];
const END_REASONS = ['USER_CANCELLED', 'PLAN_FAILED', 'EXPIRED'] as const;
export type EndReason = (typeof END_REASONS)[number];

/** Session states in which polling stops (`events.json` polling.clientCadence). */
export const TERMINAL_SESSION_STATES: readonly SessionState[] = ['CLOSED', 'CANCELLED', 'EXPIRED'];

export type ArtifactCounts = Readonly<Record<ArtifactState, number>>;

export interface SessionUsage { readonly reservedCredits: number; readonly spentCredits: number; }

/** Fields of `sessionSummary`; the detail adds the echoed spec and the artifact summaries. */
export interface SessionSummary {
    readonly sessionId: string;
    readonly deckId: string;
    readonly kind: SessionKind;
    readonly state: SessionState;
    readonly rowVersion: string;
    readonly endReason: EndReason | null;
    readonly createdAt: string;
    readonly lastActivityAt: string;
    readonly expiresAt: string;
    readonly artifactCounts: ArtifactCounts;
    readonly approvableCount: number;
    readonly usage: SessionUsage;
}

/**
 * What the Workshop needs from the echoed spec. The server clamps and echoes the stored spec "so the client can show it
 * as chips"; only the kind, language and prompt are read, the rest of the (provisional) shape is not interpreted here.
 */
export interface SpecEcho {
    readonly kind: SessionKind;
    readonly outputLanguage: string | null;
    readonly prompt: string | null;
    /** The pinned materials of an `EXERCISES` session, in the order they were requested; empty for every other kind. */
    readonly targets: readonly ExerciseTarget[];
    /** What a `REVISE_*` session was asked to change in the text; `null` for the other kinds and for a voice-only redo. */
    readonly instruction: string | null;
    /** The voice a `REVISE_EXERCISE` session was asked for; `null` when it has no media action. */
    readonly voice: SpeechVoice | null;
}

export interface MediaSlotCounts { readonly total: number; readonly ready: number; readonly failed: number; }

export type PublishedRef =
    | { readonly kind: 'ITEM'; readonly memberKey: string; readonly itemRevisionId: string; readonly ordinal: number }
    | { readonly kind: 'EXERCISE'; readonly exerciseId: string; readonly exerciseRevisionId: string;
        readonly objectiveId: string; readonly objectiveRevisionId: string };

export interface ArtifactSummary {
    readonly artifactId: string;
    readonly ordinal: number;
    readonly targetKind: 'ITEM' | 'EXERCISE';
    readonly state: ArtifactState;
    readonly rowVersion: string;
    readonly title: string;
    readonly currentRevisionId: string | null;
    readonly mediaSlotCounts: MediaSlotCounts;
    readonly errorCode: ArtifactErrorCode | null;
    readonly repinStatus: RepinStatus | null;
    readonly publishedRef: PublishedRef | null;
}

/** Used notes of a session (`sessionDetail.notes`): how many were used and how many «Архивировать использованные» would archive now. */
export interface SessionNotes { readonly used: number; readonly archivable: number; }

export interface SessionDetail extends SessionSummary {
    readonly spec: SpecEcho;
    readonly artifacts: readonly ArtifactSummary[];
    /** Detail only (#288): what the «Архивировать использованные заметки (k)» offer is based on. */
    readonly notes: SessionNotes;
}

export interface SessionPage { readonly items: readonly SessionSummary[]; readonly nextCursor: string | null; }

export interface CreatedSession { readonly session: SessionDetail; readonly replayed: boolean; }

export interface MediaSlot {
    readonly slotKey: string;
    readonly kind: SlotKind;
    readonly nodeId: string;
    readonly assetId: string;
    readonly state: SlotState;
    readonly errorCode: SlotErrorCode | null;
    /** The voice of the last redo of the audio of an exercise (AI-16); `null` before one ran, and always `null` for a material. */
    readonly voice: SpeechVoice | null;
}

export interface ArtifactRevisionRef { readonly revisionId: string; readonly cause: string; readonly createdAt: string; }

/** What an edit does (`editArtifact.action`). The three media redos are refused with `CAPABILITY_UNAVAILABLE` until AI-09 and AI-10. */
export const EDIT_ACTIONS = ['REWRITE', 'IMAGE_SEARCH', 'IMAGE_GENERATE', 'AUDIO_REGENERATE', 'FREE', 'REMOVE_MEDIA'] as const;
export type EditAction = (typeof EDIT_ACTIONS)[number];
export const EDIT_PRESETS = ['SIMPLER', 'SHORTER', 'EXAMPLE', 'LONGER'] as const;
export type EditPreset = (typeof EDIT_PRESETS)[number];
export const TURN_STATES = ['QUEUED', 'RUNNING', 'APPLIED', 'FAILED', 'CANCELLED'] as const;
export type TurnState = (typeof TURN_STATES)[number];
export const MAX_INSTRUCTION_LENGTH = 2000;
export const MAX_EDIT_TARGETS = 50;

/** One user instruction on an artifact (`getArtifact.turns[]`, `editArtifact.turn`). */
export interface ArtifactTurn {
    readonly turnId: string;
    readonly status: TurnState;
    readonly action: EditAction;
    readonly preset: EditPreset | null;
    readonly instruction: string | null;
    readonly targetNodeIds: readonly string[];
    /** The revision the turn made: set once it is `APPLIED`. */
    readonly resultRevisionId: string | null;
    readonly errorCode: ArtifactErrorCode | null;
    readonly createdAt: string;
    /** The voice of an `AUDIO_REGENERATE` turn of an exercise (AI-16); `null` for every other turn. */
    readonly voice: SpeechVoice | null;
}

/** The body of `editArtifact` as the client builds it; {@link serializeEdit} turns it into the exact wire body. */
export interface EditRequest {
    readonly expectedRevisionId: string;
    readonly action: EditAction;
    /** The blocks of a material the edit names; an exercise has none (`exercise: true`). */
    readonly nodeIds: readonly string[];
    readonly preset?: EditPreset | null;
    readonly instruction?: string | null;
    /** The edit is on the exercise of a `REVISE_EXERCISE` session (AI-16): `FREE` rewrites it whole, `AUDIO_REGENERATE` redoes its audio with `voice`. */
    readonly exercise?: boolean;
    readonly voice?: SpeechVoice | null;
}

/** `202` of `editArtifact`: the turn and the artifact (`REVISING` for a rewrite; `PROPOSED` on the new revision for `REMOVE_MEDIA`). */
export interface EditAccepted { readonly turn: ArtifactTurn; readonly artifact: ArtifactSummary; readonly replayed: boolean; }

/** The `edit` form of `estimateGeneration`. `targetNodeCount` is the number of blocks the selection touches. */
export interface EditEstimateRequest {
    readonly sessionId: string;
    readonly artifactId: string;
    readonly action: EditAction;
    readonly targetNodeCount?: number;
}

/** Item payload is a native-v1 document; an exercise payload is kept opaque here and read by `exercise-proposal.ts`. */
export type ArtifactPayload =
    | { readonly kind: 'NATIVE_DOCUMENT'; readonly document: NativeDocument }
    | { readonly kind: 'EXERCISE_COMMAND'; readonly command: unknown };

export interface ArtifactRevision extends ArtifactRevisionRef {
    readonly validationWarnings: readonly unknown[];
    readonly payload: ArtifactPayload;
}

/** How a pinned note compares with the note now (`getArtifact.sourceRefs[].status`); informational, it never changes the artifact. */
export const NOTE_STATUSES = ['CURRENT', 'CHANGED', 'ARCHIVED', 'DELETED'] as const;
export type NoteStatus = (typeof NOTE_STATUSES)[number];

/** A NOTE entry of the artifact's `sourceRefs`; `status` is null while the server does not report it. */
export interface NoteSourceRef { readonly noteId: string; readonly noteRowVersion: string; readonly status: NoteStatus | null; }

/**
 * What the server adds to an `EXERCISE` artifact so the client can show it without more requests (contract decision 14,
 * AI-13): the mechanic, the objective title (offered or new) and the plain text of every `MATERIAL` node the exercise quotes,
 * as it is in the pinned revision.
 */
export interface ExerciseDisplay {
    readonly mechanic: Mechanic;
    readonly objectiveTitle: string;
    readonly quotes: Readonly<Record<string, string>>;
}

export interface ArtifactDetail extends ArtifactSummary {
    readonly sessionId: string;
    readonly noteSources: readonly NoteSourceRef[];
    readonly deckId: string;
    readonly revision: ArtifactRevision | null;
    readonly mediaSlots: readonly MediaSlot[];
    /** Revisions of the current generation of the draft, oldest first: exactly the ones a revert accepts. */
    readonly revisions: readonly ArtifactRevisionRef[];
    /** Turns of the same generation, oldest first (`REMOVE_MEDIA` included). */
    readonly turns: readonly ArtifactTurn[];
    /** Only an `EXERCISE` artifact has it; `null` for an item (or while the server does not send it). */
    readonly display: ExerciseDisplay | null;
}

/** Acknowledgement of one approval (single or bulk): the new Deck pin and the published artifacts. */
export interface ApprovalAck {
    readonly commandId: string;
    readonly deckId: string;
    readonly deckRevisionId: string;
    readonly deckVersion: string;
    readonly artifacts: readonly { readonly artifactId: string; readonly state: 'PUBLISHED'; readonly publishedRef: PublishedRef }[];
    readonly replayed: boolean;
}

export interface HandoffResult {
    readonly artifact: ArtifactSummary;
    readonly draft: { readonly draftId: string; readonly deckId: string; readonly memberKey: string | null;
        readonly baseRevisionId: string | null };
}

export interface ActiveStep {
    readonly stepId: string;
    readonly artifactId: string | null;
    readonly kind: string;
    readonly state: string;
    readonly startedAt: string | null;
}

export interface UsageUpdate {
    readonly reservedCredits: number;
    readonly spentCredits: number;
    readonly balanceRemainingCredits: number;
    readonly deferredUntil: string | null;
}

interface EventBase { readonly seq: string; readonly sessionId: string; readonly artifactId: string | null; readonly occurredAt: string; }
export type GenerationEvent =
    | (EventBase & { readonly type: 'ARTIFACT_STATE'; readonly artifactId: string; readonly state: ArtifactState;
        readonly artifactVersion: string; readonly currentRevisionId: string | null;
        readonly errorCode: ArtifactErrorCode | null; readonly repinStatus: RepinStatus | null })
    | (EventBase & { readonly type: 'BLOCKS_APPENDED'; readonly artifactId: string; readonly generation: number;
        readonly startIndex: number; readonly blocks: readonly NativeNode[] })
    | (EventBase & { readonly type: 'MEDIA_SLOT_STATE'; readonly artifactId: string; readonly slotKey: string;
        readonly kind: SlotKind; readonly state: SlotState; readonly assetId: string; readonly errorCode: SlotErrorCode | null })
    | (EventBase & { readonly type: 'USAGE_UPDATED' } & UsageUpdate)
    | (EventBase & { readonly type: 'SESSION_STATE'; readonly state: SessionState; readonly rowVersion: string;
        readonly artifactCounts: ArtifactCounts });

export interface EventsPage {
    readonly events: readonly GenerationEvent[];
    /** Events of a known type that could not be read: skipped, and the cursor still moves past them (the caller reconciles). */
    readonly unreadable: number;
    readonly cursor: string;
    readonly session: { readonly state: SessionState; readonly rowVersion: string };
    readonly activeSteps: readonly ActiveStep[];
}

// --- Estimate (contracts/usage `estimateResponse`) ---

export interface BlockingBucket {
    readonly bucket: string;
    readonly window: 'DAY' | 'WEEK' | 'MONTH';
    readonly unit: 'CREDITS' | 'MINUTES' | 'COUNT';
    readonly limit: number | null;
    readonly used: number;
    readonly required: number;
    readonly offered: boolean;
    readonly renewsAt: string | null;
    readonly fitsAfterRenewal: boolean;
    readonly plan: 'FREE' | 'PLUS' | 'PRO' | 'MAX';
}

export interface GenerationEstimate {
    readonly credits: { readonly p50: number; readonly p95: number };
    readonly percentOfPeriodAllowance: { readonly p50: number; readonly p95: number };
    readonly balance: { readonly remainingCredits: number; readonly renewsAt: string | null };
    readonly canStart: boolean;
    readonly blockingBuckets: readonly BlockingBucket[];
    readonly shortfallCredits: number | null;
    readonly personalDataWarning: boolean;
}

// --- Spec the composer sends ---

export const EFFORTS = ['AUTO', 'SHORT', 'MEDIUM', 'DETAILED'] as const;
export type Effort = (typeof EFFORTS)[number];
export type NotesMode = 'ONE_PER_NOTE' | 'MERGE_INTO_ONE';
export type AudioVoice = 'female' | 'male' | null;
/** A voice that is chosen (the audio of a material may also be left to the server: {@link AudioVoice}). */
export type SpeechVoice = 'female' | 'male';
export const SPEECH_VOICES: readonly SpeechVoice[] = ['female', 'male'];

/**
 * Per-note overrides of a `SOURCE` note (AI-08, #290): sparse, every member optional, absent = the session default. They are
 * valid only with `notesMode = ONE_PER_NOTE`, and an empty object is never sent.
 */
export interface NoteOverrides {
    readonly effort?: Effort;
    readonly media?: {
        readonly audio?: { readonly enabled: boolean; readonly lang: string; readonly voice: AudioVoice };
        readonly imageSearch?: boolean;
    };
}

/** A pinned source of a spec (`generationSpec.MATERIALS.sources`); the pins are immutable for the session. */
export type SpecSource =
    | { readonly role: 'SOURCE' | 'STYLE_EXAMPLE'; readonly type: 'NOTE'; readonly noteId: string; readonly noteRowVersion: string;
        readonly overrides?: NoteOverrides }
    | { readonly role: 'SOURCE' | 'STYLE_EXAMPLE'; readonly type: 'ITEM'; readonly memberKey: string; readonly itemRevisionId: string };

export interface MaterialsSettings {
    readonly effort: Effort;
    readonly notesMode: NotesMode;
    readonly media: { readonly audio: { readonly enabled: boolean; readonly lang: string; readonly voice: AudioVoice };
        readonly imageSearch: boolean };
    readonly factCheck: boolean;
    readonly similarToDeck: boolean;
    readonly planFirst: boolean;
    readonly budgetPercent: number | null;
}

export interface MaterialsSpec {
    readonly kind: 'MATERIALS';
    readonly outputLanguage?: string;
    readonly prompt: string;
    readonly sources: readonly SpecSource[];
    readonly settings: MaterialsSettings;
}

export const MAX_PROMPT_LENGTH = 2000;
export const MAX_SOURCES = 20;
export const MAX_APPROVALS_PER_COMMAND = 20;

// --- Which operations a state allows (`states.json` session/artifact allowedOperations) ---

export type UiOperation = 'approveArtifact' | 'approveArtifacts' | 'rejectArtifact' | 'undoRejectArtifact' | 'handoffArtifact'
    | 'retryArtifact' | 'editArtifact' | 'revertArtifact' | 'cancelSession' | 'deleteSession';

const SESSION_OPERATIONS: Readonly<Record<SessionState, readonly UiOperation[]>> = {
    PLANNING: ['cancelSession', 'deleteSession'],
    PLAN_READY: ['cancelSession', 'deleteSession'],
    RUNNING: ['approveArtifact', 'approveArtifacts', 'rejectArtifact', 'undoRejectArtifact', 'handoffArtifact', 'editArtifact',
        'revertArtifact', 'retryArtifact', 'cancelSession', 'deleteSession'],
    REVIEW: ['approveArtifact', 'approveArtifacts', 'rejectArtifact', 'undoRejectArtifact', 'handoffArtifact', 'editArtifact',
        'revertArtifact', 'retryArtifact', 'cancelSession', 'deleteSession'],
    // A rejection can still be undone in a CLOSED session (#288): it reopens it (REVIEW, or CANCELLED after a cancellation).
    CLOSED: ['deleteSession', 'undoRejectArtifact'],
    // A cancelled session still takes `REMOVE_MEDIA` (the only free, model-less edit), but no rewrite and no revert.
    CANCELLED: ['approveArtifact', 'approveArtifacts', 'rejectArtifact', 'undoRejectArtifact', 'handoffArtifact', 'editArtifact',
        'deleteSession'],
    EXPIRED: ['deleteSession']
};

const ARTIFACT_OPERATIONS: Readonly<Record<ArtifactState, readonly UiOperation[]>> = {
    QUEUED: [],
    GENERATING: [],
    PROPOSED: ['approveArtifact', 'approveArtifacts', 'rejectArtifact', 'handoffArtifact', 'editArtifact', 'revertArtifact'],
    REVISING: [],
    FAILED: ['retryArtifact'],
    REJECTED: ['undoRejectArtifact'],
    STALE: ['rejectArtifact', 'handoffArtifact', 'retryArtifact'],
    PUBLISHED: [],
    HANDED_OFF: []
};

/** Exposed for the contract spec, which pins both tables to `states.json`. */
export const OPERATION_TABLES = { session: SESSION_OPERATIONS, artifact: ARTIFACT_OPERATIONS } as const;

/** A command on an artifact is offered only when both its state and its session's state allow it. */
export function allows(session: SessionState, artifact: ArtifactState, operation: UiOperation): boolean {
    return SESSION_OPERATIONS[session].includes(operation) && ARTIFACT_OPERATIONS[artifact].includes(operation);
}

export function sessionAllows(session: SessionState, operation: UiOperation): boolean {
    return SESSION_OPERATIONS[session].includes(operation);
}

/** Retry is never offered after a refusal (`REFUSAL` is not retryable). */
export function isRetryable(artifact: ArtifactSummary): boolean {
    return (artifact.state === 'FAILED' && artifact.errorCode !== 'REFUSAL') || artifact.state === 'STALE';
}

/**
 * Approvable now: proposed, with every media slot ready (an exercise has no slots, so it is approvable as soon as it is
 * proposed). An artifact whose media is still being made, or failed, waits; a failed slot is resolved by removing the media
 * (`REMOVE_MEDIA`, AI-11) or by editing the material oneself.
 */
export function isApprovable(artifact: ArtifactSummary): boolean {
    const { total, ready } = artifact.mediaSlotCounts;
    return artifact.state === 'PROPOSED' && artifact.currentRevisionId !== null && ready === total;
}

export function isTerminalSession(state: SessionState): boolean {
    return TERMINAL_SESSION_STATES.includes(state);
}

// --- Parsing ---

function oneOf<T extends string>(value: unknown, allowed: readonly T[], what: string): T {
    if (typeof value !== 'string' || !(allowed as readonly string[]).includes(value)) {
        throw new AuthoringProtocolError(`Invalid ${what}.`);
    }
    return value as T;
}

function nullable<T>(value: unknown, parse: (value: unknown) => T): T | null { return value === null ? null : parse(value); }

function text(value: unknown, maximum: number, allowEmpty = false): string {
    if (typeof value !== 'string' || (!allowEmpty && value.length === 0) || value.length > maximum) {
        throw new AuthoringProtocolError('Invalid text.');
    }
    return value;
}

function flag(value: unknown): boolean {
    if (typeof value !== 'boolean') throw new AuthoringProtocolError('Invalid flag.');
    return value;
}

function list(value: unknown, maximum: number): readonly unknown[] {
    if (!Array.isArray(value) || value.length > maximum) throw new AuthoringProtocolError('Invalid list.');
    return value;
}

function parseCounts(value: unknown): ArtifactCounts {
    const object = requireObject(value, ARTIFACT_STATES);
    return Object.fromEntries(ARTIFACT_STATES.map(state => [state, requireCount(object[state], 100_000)])) as ArtifactCounts;
}

function parseUsage(value: unknown): SessionUsage {
    const object = requireObject(value, ['reservedCredits', 'spentCredits']);
    return { reservedCredits: requireCount(object['reservedCredits']), spentCredits: requireCount(object['spentCredits']) };
}

const SUMMARY_KEYS = ['sessionId', 'deckId', 'kind', 'state', 'rowVersion', 'endReason', 'createdAt', 'lastActivityAt',
    'expiresAt', 'artifactCounts', 'approvableCount', 'usage'];

function parseSummaryFields(object: Record<string, unknown>): SessionSummary {
    return {
        sessionId: requireEntity(object['sessionId']), deckId: requireEntity(object['deckId']),
        kind: oneOf(object['kind'], SESSION_KINDS, 'session kind'), state: oneOf(object['state'], SESSION_STATES, 'session state'),
        rowVersion: requireVersion(object['rowVersion']),
        endReason: nullable(object['endReason'], reason => oneOf(reason, END_REASONS, 'end reason')),
        createdAt: requireInstant(object['createdAt']), lastActivityAt: requireInstant(object['lastActivityAt']),
        expiresAt: requireInstant(object['expiresAt']), artifactCounts: parseCounts(object['artifactCounts']),
        approvableCount: requireCount(object['approvableCount'], 100_000), usage: parseUsage(object['usage'])
    };
}

export function parseSessionSummary(value: unknown): SessionSummary {
    return parseSummaryFields(requireObject(value, SUMMARY_KEYS));
}

/** Targets of the echoed spec; the echo is provisional, so anything that is not a list of well-formed pins reads as «none». */
function parseEchoTargets(value: unknown): readonly ExerciseTarget[] {
    if (!Array.isArray(value) || value.length > MAX_EXERCISE_TARGETS) return [];
    try {
        return value.map(entry => {
            const object = requireObject(entry, ['memberKey', 'itemRevisionId']);
            return { memberKey: requireEntity(object['memberKey']), itemRevisionId: requireEntity(object['itemRevisionId']) };
        });
    } catch {
        return [];
    }
}

function parseSpecEcho(value: unknown): SpecEcho {
    if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new AuthoringProtocolError('Invalid spec.');
    const object = value as Record<string, unknown>;
    const prompt = object['prompt'];
    const language = object['outputLanguage'];
    const instruction = object['instruction'];
    const media = object['media'];
    const voice = media !== null && typeof media === 'object' ? (media as Record<string, unknown>)['voice'] : null;
    return {
        kind: oneOf(object['kind'], SESSION_KINDS, 'spec kind'),
        outputLanguage: typeof language === 'string' ? language : null,
        prompt: typeof prompt === 'string' ? prompt : null,
        targets: parseEchoTargets(object['targets']),
        instruction: typeof instruction === 'string' && instruction.trim().length > 0 ? instruction : null,
        voice: voice === 'female' || voice === 'male' ? voice : null
    };
}

function parseSessionNotes(value: unknown): SessionNotes {
    const object = requireObject(value, ['used', 'archivable']);
    const notes = { used: requireCount(object['used'], 100_000), archivable: requireCount(object['archivable'], 100_000) };
    if (notes.archivable > notes.used) throw new AuthoringProtocolError('Invalid note counts.');
    return notes;
}

export function parseSessionDetail(value: unknown): SessionDetail {
    const object = requireObject(value, [...SUMMARY_KEYS, 'spec', 'artifacts', 'notes']);
    const artifacts = list(object['artifacts'], 200).map(parseArtifactSummary);
    return { ...parseSummaryFields(object), spec: parseSpecEcho(object['spec']), artifacts, notes: parseSessionNotes(object['notes']) };
}

function parseMediaSlotCounts(value: unknown): MediaSlotCounts {
    const object = requireObject(value, ['total', 'ready', 'failed']);
    const counts = { total: requireCount(object['total'], 64), ready: requireCount(object['ready'], 64),
        failed: requireCount(object['failed'], 64) };
    if (counts.ready + counts.failed > counts.total) throw new AuthoringProtocolError('Invalid media slot counts.');
    return counts;
}

export function parsePublishedRef(value: unknown): PublishedRef {
    if (value !== null && typeof value === 'object' && (value as Record<string, unknown>)['kind'] === 'EXERCISE') {
        const object = requireObject(value, ['kind', 'exerciseId', 'exerciseRevisionId', 'objectiveId', 'objectiveRevisionId']);
        return { kind: 'EXERCISE', exerciseId: requireEntity(object['exerciseId']),
            exerciseRevisionId: requireEntity(object['exerciseRevisionId']), objectiveId: requireEntity(object['objectiveId']),
            objectiveRevisionId: requireEntity(object['objectiveRevisionId']) };
    }
    const object = requireObject(value, ['kind', 'memberKey', 'itemRevisionId', 'ordinal']);
    if (object['kind'] !== 'ITEM') throw new AuthoringProtocolError('Invalid published reference.');
    return { kind: 'ITEM', memberKey: requireEntity(object['memberKey']), itemRevisionId: requireEntity(object['itemRevisionId']),
        ordinal: requireCount(object['ordinal'], 99_999) };
}

const ARTIFACT_SUMMARY_KEYS = ['artifactId', 'ordinal', 'targetKind', 'state', 'rowVersion', 'title', 'currentRevisionId',
    'mediaSlotCounts', 'errorCode', 'repinStatus', 'publishedRef'];

function parseSummaryOf(object: Record<string, unknown>): ArtifactSummary {
    return {
        artifactId: requireEntity(object['artifactId']), ordinal: requireCount(object['ordinal'], 1_000),
        targetKind: oneOf(object['targetKind'], ['ITEM', 'EXERCISE'] as const, 'artifact kind'),
        state: oneOf(object['state'], ARTIFACT_STATES, 'artifact state'), rowVersion: requireVersion(object['rowVersion']),
        title: text(object['title'], 960, true), currentRevisionId: nullable(object['currentRevisionId'], requireEntity),
        mediaSlotCounts: parseMediaSlotCounts(object['mediaSlotCounts']),
        errorCode: lenientErrorCode(object['errorCode']),
        repinStatus: nullable(object['repinStatus'], status => oneOf(status, REPIN_STATUSES, 'repin status')),
        publishedRef: nullable(object['publishedRef'], parsePublishedRef)
    };
}

export function parseArtifactSummary(value: unknown): ArtifactSummary {
    return parseSummaryOf(requireObject(value, ARTIFACT_SUMMARY_KEYS));
}

/** The keys of `value` plus `optional` when it carries it: a member the contract adds to some shapes only (`voice`). */
function keysWith(value: unknown, keys: readonly string[], optional: string): readonly string[] {
    return value !== null && typeof value === 'object' && !Array.isArray(value) && optional in value ? [...keys, optional] : keys;
}

function parseVoice(value: unknown): SpeechVoice | null {
    return value === undefined || value === null ? null : oneOf(value, SPEECH_VOICES, 'voice');
}

function parseMediaSlot(value: unknown): MediaSlot {
    const object = requireObject(value, keysWith(value, ['slotKey', 'kind', 'nodeId', 'assetId', 'state', 'errorCode'], 'voice'));
    return {
        voice: parseVoice(object['voice']),
        slotKey: text(object['slotKey'], 64), kind: oneOf(object['kind'], SLOT_KINDS, 'slot kind'),
        nodeId: requireEntity(object['nodeId']), assetId: requireEntity(object['assetId']),
        state: oneOf(object['state'], SLOT_STATES, 'slot state'),
        errorCode: nullable(object['errorCode'], code => oneOf(code, SLOT_ERROR_CODES, 'slot error code'))
    };
}

function parseRevisionRef(value: unknown): ArtifactRevisionRef {
    const object = requireObject(value, ['revisionId', 'cause', 'createdAt']);
    return { revisionId: requireEntity(object['revisionId']), cause: text(object['cause'], 32),
        createdAt: requireInstant(object['createdAt']) };
}

export function parseTurn(value: unknown): ArtifactTurn {
    const object = requireObject(value, keysWith(value, ['turnId', 'status', 'action', 'preset', 'instruction', 'targetNodeIds',
        'resultRevisionId', 'errorCode', 'createdAt'], 'voice'));
    return {
        voice: parseVoice(object['voice']),
        turnId: requireEntity(object['turnId']), status: oneOf(object['status'], TURN_STATES, 'turn status'),
        action: oneOf(object['action'], EDIT_ACTIONS, 'edit action'),
        preset: nullable(object['preset'], preset => oneOf(preset, EDIT_PRESETS, 'edit preset')),
        instruction: nullable(object['instruction'], instruction => text(instruction, MAX_INSTRUCTION_LENGTH * 2)),
        targetNodeIds: list(object['targetNodeIds'], MAX_EDIT_TARGETS).map(requireEntity),
        resultRevisionId: nullable(object['resultRevisionId'], requireEntity), errorCode: lenientErrorCode(object['errorCode']),
        createdAt: requireInstant(object['createdAt'])
    };
}

export function parseEditAccepted(value: unknown, replayed: boolean): EditAccepted {
    const object = requireObject(value, ['turn', 'artifact']);
    return { turn: parseTurn(object['turn']), artifact: parseArtifactSummary(object['artifact']), replayed };
}

function parsePayload(value: unknown): ArtifactPayload {
    if (value !== null && typeof value === 'object' && (value as Record<string, unknown>)['kind'] === 'EXERCISE_COMMAND') {
        const object = requireObject(value, ['kind', 'command']);
        return { kind: 'EXERCISE_COMMAND', command: object['command'] };
    }
    const object = requireObject(value, ['kind', 'document']);
    if (object['kind'] !== 'NATIVE_DOCUMENT') throw new AuthoringProtocolError('Invalid artifact payload.');
    return { kind: 'NATIVE_DOCUMENT', document: readRetainedNativeDocument(object['document']) };
}

function parseRevision(value: unknown): ArtifactRevision {
    const object = requireObject(value, ['revisionId', 'cause', 'createdAt', 'validation', 'payload']);
    const validation = requireObject(object['validation'], ['warnings']);
    return { ...parseRevisionRef(Object.fromEntries(['revisionId', 'cause', 'createdAt'].map(key => [key, object[key]]))),
        validationWarnings: list(validation['warnings'], 100), payload: parsePayload(object['payload']) };
}

/**
 * Reads the NOTE entries of `sourceRefs` and ignores the rest: only the pinned note and its informational `status` matter to
 * the Workshop, and an unknown status (a newer server) is read as «not reported», never as an error.
 */
function parseNoteSourceRef(value: unknown): readonly NoteSourceRef[] {
    if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new AuthoringProtocolError('Invalid source reference.');
    const object = value as Record<string, unknown>;
    if (object['type'] !== 'NOTE') return [];
    const status = object['status'];
    return [{ noteId: requireEntity(object['noteId']), noteRowVersion: requireVersion(object['noteRowVersion']),
        status: typeof status === 'string' && (NOTE_STATUSES as readonly string[]).includes(status) ? status as NoteStatus : null }];
}

const MAX_QUOTES = 100;
const MAX_QUOTE_LENGTH = 20_000;

function parseDisplay(value: unknown): ExerciseDisplay {
    const object = requireObject(value, ['mechanic', 'objectiveTitle', 'quotes']);
    const quotes = object['quotes'];
    if (quotes === null || typeof quotes !== 'object' || Array.isArray(quotes)) throw new AuthoringProtocolError('Invalid quotes.');
    const entries = Object.entries(quotes as Record<string, unknown>);
    if (entries.length > MAX_QUOTES) throw new AuthoringProtocolError('Too many quotes.');
    return {
        mechanic: oneOf(object['mechanic'], MECHANICS, 'mechanic'), objectiveTitle: text(object['objectiveTitle'], 960),
        quotes: Object.fromEntries(entries.map(([nodeId, quote]) => [requireEntity(nodeId), text(quote, MAX_QUOTE_LENGTH, true)]))
    };
}

const ARTIFACT_DETAIL_KEYS = [...ARTIFACT_SUMMARY_KEYS, 'sessionId', 'deckId', 'sourceRefs', 'revision', 'mediaSlots', 'revisions', 'turns'];

/**
 * `shownRevisionId` is set when the answer is a read of one exact revision (`?revisionId=`): the payload is then that revision's, and
 * `currentRevisionId` stays the artifact's pointer. Without it the payload must be the current revision.
 */
export function parseArtifactDetail(value: unknown, shownRevisionId: string | null = null): ArtifactDetail {
    // `display` belongs to an exercise artifact with a revision, and only to it: absent for an item and while there is no revision.
    const hasDisplay = value !== null && typeof value === 'object' && !Array.isArray(value) && 'display' in value;
    const object = requireObject(value, hasDisplay ? [...ARTIFACT_DETAIL_KEYS, 'display'] : ARTIFACT_DETAIL_KEYS);
    const noteSources = list(object['sourceRefs'], 20).flatMap(parseNoteSourceRef);
    const revision = nullable(object['revision'], parseRevision);
    if (revision !== null && revision.revisionId !== (shownRevisionId ?? object['currentRevisionId'])) {
        throw new AuthoringProtocolError('Artifact revision does not match the current one.');
    }
    if (hasDisplay !== (revision?.payload.kind === 'EXERCISE_COMMAND')) throw new AuthoringProtocolError('Artifact display does not match its payload.');
    return {
        ...parseSummaryOf(Object.fromEntries(ARTIFACT_SUMMARY_KEYS.map(key => [key, object[key]]))),
        sessionId: requireEntity(object['sessionId']), deckId: requireEntity(object['deckId']), noteSources, revision,
        mediaSlots: list(object['mediaSlots'], 64).map(parseMediaSlot),
        revisions: list(object['revisions'], 40).map(parseRevisionRef), turns: list(object['turns'], 100).map(parseTurn),
        display: hasDisplay ? parseDisplay(object['display']) : null
    };
}

export function parseSessionPage(value: unknown): SessionPage {
    const object = requireObject(value, ['items', 'nextCursor']);
    return { items: list(object['items'], 100).map(parseSessionSummary), nextCursor: requireCursor(object['nextCursor']) };
}

function parseAckArtifact(value: unknown): ApprovalAck['artifacts'][number] {
    const object = requireObject(value, ['artifactId', 'state', 'publishedRef']);
    if (object['state'] !== 'PUBLISHED') throw new AuthoringProtocolError('Approval did not publish the artifact.');
    return { artifactId: requireEntity(object['artifactId']), state: 'PUBLISHED', publishedRef: parsePublishedRef(object['publishedRef']) };
}

export function parseApprovalAck(value: unknown, replayed: boolean): ApprovalAck {
    const object = requireObject(value, ['commandId', 'deckId', 'deckRevisionId', 'deckVersion', 'artifacts']);
    return {
        commandId: requireCommand(object['commandId']), deckId: requireEntity(object['deckId']),
        deckRevisionId: requireEntity(object['deckRevisionId']), deckVersion: requireVersion(object['deckVersion']),
        artifacts: list(object['artifacts'], MAX_APPROVALS_PER_COMMAND).map(parseAckArtifact), replayed
    };
}

export function parseHandoff(value: unknown): HandoffResult {
    const object = requireObject(value, ['artifact', 'draft']);
    const draft = requireObject(object['draft'], ['draftId', 'deckId', 'memberKey', 'baseRevisionId']);
    return {
        artifact: parseArtifactSummary(object['artifact']),
        draft: { draftId: requireEntity(draft['draftId']), deckId: requireEntity(draft['deckId']),
            memberKey: nullable(draft['memberKey'], requireEntity), baseRevisionId: nullable(draft['baseRevisionId'], requireEntity) }
    };
}

function parseActiveStep(value: unknown): ActiveStep {
    const object = requireObject(value, ['stepId', 'artifactId', 'kind', 'state', 'startedAt']);
    return {
        stepId: requireEntity(object['stepId']), artifactId: nullable(object['artifactId'], requireEntity),
        kind: text(object['kind'], 40), state: text(object['state'], 40), startedAt: nullable(object['startedAt'], requireInstant)
    };
}

/** Compiled draft blocks are validated as a native-v1 document (bounded, unique UUID ids) and returned as top-level nodes. */
const PREVIEW_ROOT_ID = '00000000-0000-4000-8000-000000000000';

export function previewDocument(blocks: readonly NativeNode[]): NativeDocument {
    return { formatVersion: 1, root: { id: PREVIEW_ROOT_ID, type: 'doc', version: 1, attrs: {}, content: blocks } };
}

/** Draft blocks are only a preview: blocks that do not read are skipped (`null`), never a reason to stop polling. */
function parseBlocks(value: unknown): readonly NativeNode[] | null {
    try {
        const blocks = list(value, 64);
        return readRetainedNativeDocument(previewDocument(blocks as readonly NativeNode[])).root.content;
    } catch {
        return null;
    }
}

/** An error code this client does not know is a generic failure (`null`), not a protocol error. */
function lenientErrorCode(code: unknown): ArtifactErrorCode | null {
    return typeof code === 'string' && (ARTIFACT_ERROR_CODES as readonly string[]).includes(code) ? code as ArtifactErrorCode : null;
}

function parseEvent(value: unknown): GenerationEvent | null {
    if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new AuthoringProtocolError('Invalid event.');
    const type = (value as Record<string, unknown>)['type'];
    const known = ['ARTIFACT_STATE', 'BLOCKS_APPENDED', 'MEDIA_SLOT_STATE', 'USAGE_UPDATED', 'SESSION_STATE'];
    if (typeof type !== 'string' || !known.includes(type)) return null;
    const object = requireObject(value, ['seq', 'type', 'sessionId', 'artifactId', 'occurredAt', 'payload']);
    const base = {
        seq: requireVersion(object['seq']), sessionId: requireEntity(object['sessionId']),
        artifactId: nullable(object['artifactId'], requireEntity), occurredAt: requireInstant(object['occurredAt'])
    };
    const artifactId = (): string => {
        if (base.artifactId === null) throw new AuthoringProtocolError('Event needs an artifact.');
        return base.artifactId;
    };
    switch (type) {
        case 'ARTIFACT_STATE': {
            const payload = requireObject(object['payload'],
                ['state', 'artifactVersion', 'currentRevisionId', 'errorCode', 'repinStatus']);
            return { ...base, type, artifactId: artifactId(), state: oneOf(payload['state'], ARTIFACT_STATES, 'artifact state'),
                artifactVersion: requireVersion(payload['artifactVersion']),
                currentRevisionId: nullable(payload['currentRevisionId'], requireEntity),
                errorCode: lenientErrorCode(payload['errorCode']),
                repinStatus: nullable(payload['repinStatus'], status => oneOf(status, REPIN_STATUSES, 'repin status')) };
        }
        case 'BLOCKS_APPENDED': {
            const payload = requireObject(object['payload'], ['generation', 'startIndex', 'blocks']);
            const blocks = parseBlocks(payload['blocks']);
            if (blocks === null) return null;
            return { ...base, type, artifactId: artifactId(), generation: requireCount(payload['generation']),
                startIndex: requireCount(payload['startIndex'], 1_000), blocks };
        }
        case 'MEDIA_SLOT_STATE': {
            const payload = requireObject(object['payload'], ['slotKey', 'kind', 'state', 'assetId', 'errorCode']);
            return { ...base, type, artifactId: artifactId(), slotKey: text(payload['slotKey'], 64),
                kind: oneOf(payload['kind'], SLOT_KINDS, 'slot kind'), state: oneOf(payload['state'], SLOT_STATES, 'slot state'),
                assetId: requireEntity(payload['assetId']),
                errorCode: nullable(payload['errorCode'], code => oneOf(code, SLOT_ERROR_CODES, 'slot error code')) };
        }
        case 'USAGE_UPDATED': {
            const payload = requireObject(object['payload'], ['reservedCredits', 'spentCredits', 'balanceRemainingCredits',
                'deferredUntil']);
            return { ...base, type, reservedCredits: requireCount(payload['reservedCredits']),
                spentCredits: requireCount(payload['spentCredits']),
                balanceRemainingCredits: requireCount(payload['balanceRemainingCredits']),
                deferredUntil: nullable(payload['deferredUntil'], requireInstant) };
        }
        default: {
            const payload = requireObject(object['payload'], ['state', 'rowVersion', 'artifactCounts']);
            return { ...base, type: 'SESSION_STATE', state: oneOf(payload['state'], SESSION_STATES, 'session state'),
                rowVersion: requireVersion(payload['rowVersion']), artifactCounts: parseCounts(payload['artifactCounts']) };
        }
    }
}

export function parseEventsPage(value: unknown): EventsPage {
    const object = requireObject(value, ['events', 'cursor', 'session', 'activeSteps']);
    const session = requireObject(object['session'], ['state', 'rowVersion']);
    const events: GenerationEvent[] = [];
    let unreadable = 0;
    for (const raw of list(object['events'], 100)) {
        try {
            const event = parseEvent(raw);
            if (event !== null) events.push(event);
        } catch {
            unreadable += 1;
        }
    }
    return {
        events, unreadable, cursor: requireVersion(object['cursor']),
        session: { state: oneOf(session['state'], SESSION_STATES, 'session state'), rowVersion: requireVersion(session['rowVersion']) },
        activeSteps: list(object['activeSteps'], 100).map(parseActiveStep)
    };
}

function parseBucket(value: unknown): BlockingBucket {
    const object = requireObject(value, ['bucket', 'window', 'unit', 'limit', 'used', 'required', 'offered', 'renewsAt',
        'fitsAfterRenewal', 'plan']);
    return {
        bucket: text(object['bucket'], 40), window: oneOf(object['window'], ['DAY', 'WEEK', 'MONTH'] as const, 'window'),
        unit: oneOf(object['unit'], ['CREDITS', 'MINUTES', 'COUNT'] as const, 'unit'),
        limit: nullable(object['limit'], limit => requireCount(limit)), used: requireCount(object['used']),
        required: requireCount(object['required']), offered: flag(object['offered']),
        renewsAt: nullable(object['renewsAt'], requireInstant), fitsAfterRenewal: flag(object['fitsAfterRenewal']),
        plan: oneOf(object['plan'], ['FREE', 'PLUS', 'PRO', 'MAX'] as const, 'plan')
    };
}

function parseSplit(value: unknown): { readonly p50: number; readonly p95: number } {
    const object = requireObject(value, ['p50', 'p95']);
    return { p50: requireCount(object['p50']), p95: requireCount(object['p95']) };
}

export function parseEstimate(value: unknown): GenerationEstimate {
    const object = requireObject(value, ['rateCardVersion', 'credits', 'breakdown', 'balance', 'percentOfPeriodAllowance', 'budget',
        'canStart', 'blockingBuckets', 'shortfallCredits', 'warnings']);
    text(object['rateCardVersion'], 32);
    list(object['breakdown'], 32);
    const balance = requireObject(object['balance'], ['remainingCredits', 'renewsAt']);
    const warnings = list(object['warnings'], 64);
    return {
        credits: parseSplit(object['credits']), percentOfPeriodAllowance: parseSplit(object['percentOfPeriodAllowance']),
        balance: { remainingCredits: requireCount(balance['remainingCredits']), renewsAt: nullable(balance['renewsAt'], requireInstant) },
        canStart: flag(object['canStart']), blockingBuckets: list(object['blockingBuckets'], 8).map(parseBucket),
        shortfallCredits: nullable(object['shortfallCredits'], credits => requireCount(credits)),
        personalDataWarning: warnings.some(warning => typeof warning === 'object' && warning !== null
            && (warning as Record<string, unknown>)['code'] === 'PERSONAL_DATA_SUSPECTED')
    };
}

// --- Serialization of the spec ---

/** Sparse overrides as they go on the wire; `null` when nothing is overridden (an empty object is `INVALID_REQUEST`). */
function serializeOverrides(overrides: NoteOverrides): Record<string, unknown> | null {
    const body: Record<string, unknown> = {};
    if (overrides.effort !== undefined) body['effort'] = oneOf(overrides.effort, EFFORTS, 'effort');
    const media: Record<string, unknown> = {};
    const audio = overrides.media?.audio;
    if (audio !== undefined) media['audio'] = { enabled: audio.enabled, lang: audio.lang, voice: audio.voice };
    if (overrides.media?.imageSearch !== undefined) media['imageSearch'] = overrides.media.imageSearch;
    if (Object.keys(media).length > 0) body['media'] = media;
    return Object.keys(body).length > 0 ? body : null;
}

function serializeSource(source: SpecSource, notesMode: NotesMode): Record<string, unknown> {
    if (source.type === 'NOTE') {
        const body: Record<string, unknown> = { role: source.role, type: 'NOTE', noteId: requireEntity(source.noteId),
            noteRowVersion: requireVersion(source.noteRowVersion) };
        const overrides = source.overrides === undefined ? null : serializeOverrides(source.overrides);
        if (overrides !== null) {
            if (source.role !== 'SOURCE' || notesMode !== 'ONE_PER_NOTE') {
                throw new RequestValidationError('Overrides apply to SOURCE notes with ONE_PER_NOTE only.');
            }
            body['overrides'] = overrides;
        }
        return body;
    }
    return { role: source.role, type: 'ITEM', memberKey: requireEntity(source.memberKey), itemRevisionId: requireEntity(source.itemRevisionId) };
}

/**
 * The exact request body of a Materials spec (`generationSpec.MATERIALS`): unknown fields are `INVALID_REQUEST` on the
 * server, so nothing else is sent. `planFirst` is always `false` until the planner exists (AI-14).
 */
export function serializeMaterialsSpec(spec: MaterialsSpec): Record<string, unknown> {
    if (spec.prompt.length > MAX_PROMPT_LENGTH || spec.sources.length > MAX_SOURCES) {
        throw new RequestValidationError('The spec exceeds its limits.');
    }
    const { settings } = spec;
    const body: Record<string, unknown> = { kind: 'MATERIALS' };
    if (spec.outputLanguage !== undefined) body['outputLanguage'] = spec.outputLanguage;
    body['prompt'] = spec.prompt;
    body['sources'] = spec.sources.map(source => serializeSource(source, settings.notesMode));
    body['settings'] = {
        effort: oneOf(settings.effort, EFFORTS, 'effort'), notesMode: settings.notesMode,
        media: { audio: { enabled: settings.media.audio.enabled, lang: settings.media.audio.lang, voice: settings.media.audio.voice },
            imageSearch: settings.media.imageSearch },
        factCheck: settings.factCheck, similarToDeck: settings.similarToDeck, planFirst: false, budgetPercent: settings.budgetPercent
    };
    return body;
}

// --- Exercises spec (`generationSpec.EXERCISES`, AI-13 #291) ---

export const EXERCISE_PRIORITIES = ['UNCOVERED_FIRST', 'BALANCED'] as const;
export type ExercisePriority = (typeof EXERCISE_PRIORITIES)[number];

/** How many exercises per material: the server's choice, an exact number, or a share of the remaining budget (mutually exclusive). */
export type ExerciseQuantity =
    | { readonly mode: 'AUTO' }
    | { readonly mode: 'EXACT'; readonly perTarget: number }
    | { readonly mode: 'BUDGET_PERCENT'; readonly percent: number };

/** A pinned material of an exercise session: the member and the revision that was current when the user chose it. */
export interface ExerciseTarget { readonly memberKey: string; readonly itemRevisionId: string; }

export interface ExercisesSettings {
    /** `AUTO` lets the server use every mechanic that is available; otherwise a non-empty set. */
    readonly mechanics: 'AUTO' | readonly Mechanic[];
    readonly priority: ExercisePriority;
    readonly quantity: ExerciseQuantity;
}

export interface ExercisesSpec {
    readonly kind: 'EXERCISES';
    readonly outputLanguage?: string;
    readonly targets: readonly ExerciseTarget[];
    readonly settings: ExercisesSettings;
}

/**
 * `REVISE_ITEM` (AI-16, #294): one instruction applied to the whole material, which must still be the head of its member. The target is
 * the pin the owner saw; the server builds this spec from «Попросить Мнему…» and the client sends it as it is.
 */
export interface ReviseItemSpec {
    readonly kind: 'REVISE_ITEM';
    readonly outputLanguage?: string;
    readonly target: ExerciseTarget;
    readonly instruction: string;
}

/** What changes in the audio of an exercise: only a new voice (AI-16 delivers the model and a Stub; real synthesis is AI-09). */
export interface ReviseMedia { readonly action: 'AUDIO_REGENERATE'; readonly voice: SpeechVoice; }

/** `REVISE_EXERCISE`: an instruction for the text, a media action, or both (at least one). */
export interface ReviseExerciseSpec {
    readonly kind: 'REVISE_EXERCISE';
    readonly outputLanguage?: string;
    readonly target: { readonly exerciseId: string; readonly exerciseRevisionId: string };
    readonly instruction?: string;
    readonly media?: ReviseMedia;
}

/** Every spec a session is created from. */
export type GenerationSpec = MaterialsSpec | ExercisesSpec | ReviseItemSpec | ReviseExerciseSpec;

/** The server's limits (`limits.maxExerciseTargets` and its siblings): one session holds at most this many materials. */
export const MAX_EXERCISE_TARGETS = 20;
export const MAX_EXERCISES_PER_TARGET = 10;
export const MAX_EXERCISES_PER_SESSION = 60;

/** The exact request body of an Exercises spec; unknown fields are `INVALID_REQUEST`. `planFirst` is always `false` until AI-14. */
export function serializeExercisesSpec(spec: ExercisesSpec): Record<string, unknown> {
    if (spec.targets.length === 0 || spec.targets.length > MAX_EXERCISE_TARGETS) throw new RequestValidationError('The spec exceeds its limits.');
    const { mechanics, quantity } = spec.settings;
    const body: Record<string, unknown> = { kind: 'EXERCISES' };
    if (spec.outputLanguage !== undefined) body['outputLanguage'] = spec.outputLanguage;
    body['targets'] = spec.targets.map(target => ({ memberKey: requireEntity(target.memberKey), itemRevisionId: requireEntity(target.itemRevisionId) }));
    let wireQuantity: Record<string, unknown>;
    switch (quantity.mode) {
        case 'AUTO': wireQuantity = { mode: 'AUTO' }; break;
        case 'EXACT':
            if (!Number.isInteger(quantity.perTarget) || quantity.perTarget < 1 || quantity.perTarget > MAX_EXERCISES_PER_TARGET) {
                throw new RequestValidationError('Invalid quantity.');
            }
            wireQuantity = { mode: 'EXACT', perTarget: quantity.perTarget };
            break;
        case 'BUDGET_PERCENT':
            if (!Number.isInteger(quantity.percent) || quantity.percent < 1 || quantity.percent > 100) throw new RequestValidationError('Invalid quantity.');
            wireQuantity = { mode: 'BUDGET_PERCENT', percent: quantity.percent };
            break;
    }
    if (mechanics !== 'AUTO' && (mechanics.length === 0 || new Set(mechanics).size !== mechanics.length
        || mechanics.some(mechanic => !(MECHANICS as readonly string[]).includes(mechanic)))) throw new RequestValidationError('Invalid mechanics.');
    body['settings'] = {
        // Canonical order, so the same choice always produces the same request (and the same idempotency key).
        mechanics: mechanics === 'AUTO' ? 'AUTO' : MECHANICS.filter(mechanic => mechanics.includes(mechanic)),
        priority: oneOf(spec.settings.priority, EXERCISE_PRIORITIES, 'priority'), quantity: wireQuantity, planFirst: false, budgetPercent: null
    };
    return body;
}

/** The instruction of a revision as the server reads it: trimmed, not blank, at most 2000 code points. */
function reviseInstruction(value: string | undefined, required: boolean): string | null {
    const trimmed = value?.trim() ?? '';
    if (trimmed.length === 0) {
        if (required) throw new RequestValidationError('A revision needs an instruction.');
        return null;
    }
    if (codePoints(trimmed) > MAX_INSTRUCTION_LENGTH) throw new RequestValidationError('The instruction is too long.');
    return trimmed;
}

/** The exact body of a `REVISE_ITEM` spec. */
export function serializeReviseItemSpec(spec: ReviseItemSpec): Record<string, unknown> {
    return {
        kind: 'REVISE_ITEM', ...(spec.outputLanguage === undefined ? {} : { outputLanguage: spec.outputLanguage }),
        target: { memberKey: requireEntity(spec.target.memberKey), itemRevisionId: requireEntity(spec.target.itemRevisionId) },
        instruction: reviseInstruction(spec.instruction, true)
    };
}

/** The exact body of a `REVISE_EXERCISE` spec: an instruction, a media action or both; never neither. */
export function serializeReviseExerciseSpec(spec: ReviseExerciseSpec): Record<string, unknown> {
    const instruction = reviseInstruction(spec.instruction, false);
    if (instruction === null && spec.media === undefined) throw new RequestValidationError('A revision needs an instruction or a media action.');
    return {
        kind: 'REVISE_EXERCISE', ...(spec.outputLanguage === undefined ? {} : { outputLanguage: spec.outputLanguage }),
        target: { exerciseId: requireEntity(spec.target.exerciseId), exerciseRevisionId: requireEntity(spec.target.exerciseRevisionId) },
        ...(instruction === null ? {} : { instruction }),
        ...(spec.media === undefined ? {} : { media: { action: 'AUDIO_REGENERATE', voice: oneOf(spec.media.voice, SPEECH_VOICES, 'voice') } })
    };
}

/** The body of any spec. */
export function serializeSpec(spec: GenerationSpec): Record<string, unknown> {
    try {
        switch (spec.kind) {
            case 'MATERIALS': return serializeMaterialsSpec(spec);
            case 'EXERCISES': return serializeExercisesSpec(spec);
            case 'REVISE_ITEM': return serializeReviseItemSpec(spec);
            case 'REVISE_EXERCISE': return serializeReviseExerciseSpec(spec);
        }
    } catch (error) {
        // A malformed id inside the spec is the same kind of refusal: nothing was sent.
        if (error instanceof AuthoringProtocolError && !(error instanceof RequestValidationError)) throw new RequestValidationError(error.message);
        throw error;
    }
}

// --- Archiving the used notes (`archiveUsedNotes`, #290) ---

export const NOTE_SKIP_REASONS = ['CHANGED', 'ALREADY_ARCHIVED', 'DELETED'] as const;
export type NoteSkipReason = (typeof NOTE_SKIP_REASONS)[number];

export interface NoteArchiveResult {
    readonly archived: readonly string[];
    readonly skipped: readonly { readonly noteId: string; readonly reason: NoteSkipReason }[];
    readonly replayed: boolean;
}

export function parseNoteArchive(value: unknown, replayed: boolean): NoteArchiveResult {
    const object = requireObject(value, ['archived', 'skipped']);
    return {
        archived: list(object['archived'], 20).map(entry => requireEntity(requireObject(entry, ['noteId'])['noteId'])),
        skipped: list(object['skipped'], 20).map(entry => {
            const skipped = requireObject(entry, ['noteId', 'reason']);
            return { noteId: requireEntity(skipped['noteId']), reason: oneOf(skipped['reason'], NOTE_SKIP_REASONS, 'skip reason') };
        }),
        replayed
    };
}

// --- Edits and reverts (`editArtifact`, `revertArtifact`, AI-11 #293) ---

/** Code points, not UTF-16 units: the server counts code points (`instruction` 1..2000, not blank). */
function codePoints(value: string): number {
    let count = 0;
    for (const _ of value) count += 1;
    return count;
}

/**
 * The exact body of `editArtifact` (unknown fields are `INVALID_REQUEST`): `preset` only with `REWRITE`, `instruction` required for
 * `FREE` and ignored for `REMOVE_MEDIA`, 1 to 50 distinct node ids. A request that breaks a rule is refused here and never sent.
 */
export function serializeEdit(request: EditRequest, commandId: string): Record<string, unknown> {
    const action = oneOf(request.action, EDIT_ACTIONS, 'edit action');
    const preset = request.preset ?? null;
    if (request.exercise === true) return serializeExerciseEdit(request, action, commandId);
    if (request.voice !== undefined && request.voice !== null) throw new RequestValidationError('A voice belongs to the audio of an exercise.');
    const nodeIds = request.nodeIds.map(requireEntity);
    if (nodeIds.length === 0 || nodeIds.length > MAX_EDIT_TARGETS || new Set(nodeIds).size !== nodeIds.length) {
        throw new RequestValidationError('An edit targets 1 to 50 distinct blocks.');
    }
    if (preset !== null && (action !== 'REWRITE' || !(EDIT_PRESETS as readonly string[]).includes(preset))) {
        throw new RequestValidationError('A preset belongs to a rewrite.');
    }
    const instruction = action === 'REMOVE_MEDIA' ? null : request.instruction?.trim() ?? null;
    if (instruction !== null && (instruction.length === 0 || codePoints(instruction) > MAX_INSTRUCTION_LENGTH)) {
        throw new RequestValidationError('The instruction is empty or too long.');
    }
    if (action === 'FREE' && instruction === null) throw new RequestValidationError('A free edit needs an instruction.');
    return {
        commandId: requireCommand(commandId), expectedRevisionId: requireEntity(request.expectedRevisionId), action,
        target: { nodeIds }, ...(preset === null ? {} : { preset }), ...(instruction === null ? {} : { instruction })
    };
}

/**
 * An edit of the exercise of a `REVISE_EXERCISE` session: `FREE` rewrites it whole (an instruction, no target) and `AUDIO_REGENERATE`
 * redoes its audio (a voice, nothing else). No other action, no target, no preset.
 */
function serializeExerciseEdit(request: EditRequest, action: EditAction, commandId: string): Record<string, unknown> {
    if (request.nodeIds.length > 0 || (request.preset ?? null) !== null) throw new RequestValidationError('An exercise is edited whole.');
    const base = { commandId: requireCommand(commandId), expectedRevisionId: requireEntity(request.expectedRevisionId), action };
    if (action === 'FREE') {
        if (request.voice !== undefined && request.voice !== null) throw new RequestValidationError('A voice belongs to the redo of the audio.');
        return { ...base, instruction: reviseInstruction(request.instruction ?? undefined, true) };
    }
    if (action === 'AUDIO_REGENERATE') {
        if (request.voice === undefined || request.voice === null) throw new RequestValidationError('The redo of the audio needs a voice.');
        return { ...base, voice: oneOf(request.voice, SPEECH_VOICES, 'voice') };
    }
    throw new RequestValidationError('An exercise takes a free rewrite or a redo of its audio.');
}

/** The `edit` form of the estimate request body. */
export function serializeEditEstimate(request: EditEstimateRequest): Record<string, unknown> {
    const count = request.targetNodeCount;
    if (count !== undefined && (!Number.isInteger(count) || count < 1 || count > MAX_EDIT_TARGETS)) {
        throw new RequestValidationError('An edit targets 1 to 50 blocks.');
    }
    return { edit: { sessionId: requireEntity(request.sessionId), artifactId: requireEntity(request.artifactId),
        action: oneOf(request.action, EDIT_ACTIONS, 'edit action'), ...(count === undefined ? {} : { targetNodeCount: count }) } };
}
