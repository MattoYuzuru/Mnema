import { LearnerBlock, LearnerContent, Mechanic } from '../../content/exercise/exercise-content.models';

export type StudyMode = 'SCHEDULED' | 'REPLAY' | 'PRACTICE';
export type StudyStatus = 'ACTIVE' | 'EMPTY' | 'COMPLETE';
export type StudyExerciseType = Mechanic;
export type SelfRating = 'NOT_RECALLED' | 'HINTED' | 'PARTIAL' | 'FULL';
export type PracticeOrder = 'SEEDED' | 'WEAKEST_FIRST';
export type ScheduledStudyPreset = 'QUICK' | 'STANDARD';

export type StudyStartIntent =
    | { readonly mode: 'SCHEDULED'; readonly preset: ScheduledStudyPreset }
    | { readonly mode: 'REPLAY'; readonly sourceSessionId: string }
    | { readonly mode: 'PRACTICE'; readonly includeNew: boolean; readonly order: PracticeOrder };

/** Server-recorded first-letter hint of one cloze blank. */
export interface StudyHint { readonly blankId: string; readonly firstLetter: string; }

interface PresentationBase {
    readonly presentationId: string;
    readonly nonce: string;
    readonly ordinal: number;
    readonly exerciseRevisionId: string;
    readonly objectiveId: string;
    readonly objectiveRevisionId: string;
    readonly learningEpoch: string;
    readonly transcriptRevealed: boolean;
    readonly hints: readonly StudyHint[];
    readonly evaluator: { readonly id: string; readonly version: string };
}

/**
 * One server-issued presentation. `type` discriminates the learner `content`; no answer key, correct id,
 * media label or unrevealed transcript ever appears here.
 */
export type StudyPresentation = PresentationBase & LearnerContent;

export interface PreparingStudySession {
    readonly sessionId: string;
    readonly mode: StudyMode;
    readonly status: 'PREPARING';
    readonly statusUrl: string;
}

export interface ReadyStudySession {
    readonly sessionId: string;
    readonly deckId: string;
    readonly mode: StudyMode;
    readonly status: StudyStatus;
    readonly timezone: string;
    readonly localStudyDate: string;
    readonly deckRevisionId: string;
    readonly exerciseGenerationId: string;
    readonly selectionPolicyVersion: string;
    readonly budget: { readonly maxPresentations: number; readonly maxNewObjectives: number };
    readonly issuedCount: number;
    readonly reducer: {
        readonly id: string;
        readonly version: string;
        readonly configId: string;
        readonly configHash: string;
    };
    readonly seed: string;
    readonly nextCursor: string | null;
    readonly expiresAt: string;
    readonly presentations: readonly StudyPresentation[];
}

export type StudySession = PreparingStudySession | ReadyStudySession;

export type StudyResponse =
    | { readonly kind: 'TEXT'; readonly text: string }
    | { readonly kind: 'SELF_CHECK'; readonly rating: SelfRating }
    | { readonly kind: 'CLOZE'; readonly blanks: readonly { readonly blankId: string; readonly text: string }[] }
    | { readonly kind: 'CHOICE'; readonly optionIds: readonly string[] }
    | { readonly kind: 'MATCH'; readonly pairs: readonly { readonly leftId: string; readonly rightId: string }[] }
    | { readonly kind: 'CANCEL' };

/** Hints are not part of the command: the server-recorded reveals are the only authority. */
export interface AttemptCommand {
    readonly attemptId: string;
    readonly presentationId: string;
    readonly nonce: string;
    readonly response: StudyResponse;
    readonly confidence: 'KNEW' | 'UNSURE' | 'GUESSED' | null;
    readonly durationMs: number;
}

export type AssessedResult = 'CORRECT' | 'PARTIAL' | 'UNSURE' | 'INCORRECT';

export interface SelfCheckFeedback { readonly result: AssessedResult; readonly appliedRules: readonly string[]; }
export interface FreeResponseFeedback {
    readonly result: AssessedResult;
    readonly appliedRules: readonly string[];
    readonly reference: string;
    readonly referenceContent: readonly LearnerBlock[];
}
export interface ClozeFeedback {
    readonly result: AssessedResult;
    readonly appliedRules: readonly string[];
    readonly blanks: readonly {
        readonly blankId: string; readonly correct: boolean; readonly hinted: boolean; readonly reference: string;
    }[];
}
export interface ChoiceFeedback {
    readonly result: AssessedResult;
    readonly appliedRules: readonly string[];
    readonly correctOptionIds: readonly string[];
}
export interface MatchFeedback {
    readonly result: AssessedResult;
    readonly appliedRules: readonly string[];
    readonly pairs: readonly {
        readonly leftId: string; readonly selectedRightId: string; readonly correctRightId: string; readonly correct: boolean;
    }[];
}
/** NOT_ASSESSED / UNAVAILABLE: no learner verdict and no canonical effect. */
export interface UnassessedFeedback {
    readonly result: 'NOT_ASSESSED' | 'UNAVAILABLE';
    readonly reasonCodes: readonly string[];
}
export type AttemptFeedback = SelfCheckFeedback | FreeResponseFeedback | ClozeFeedback | ChoiceFeedback
    | MatchFeedback | UnassessedFeedback;

export function isClozeFeedback(value: AttemptFeedback): value is ClozeFeedback { return 'blanks' in value; }
export function isChoiceFeedback(value: AttemptFeedback): value is ChoiceFeedback { return 'correctOptionIds' in value; }
export function isMatchFeedback(value: AttemptFeedback): value is MatchFeedback { return 'pairs' in value; }
export function isFreeResponseFeedback(value: AttemptFeedback): value is FreeResponseFeedback { return 'referenceContent' in value; }
export function isUnassessed(value: AttemptFeedback): value is UnassessedFeedback { return 'reasonCodes' in value; }

export interface AttemptOutcome {
    readonly attemptId: string;
    readonly presentationId: string;
    readonly mode: StudyMode;
    readonly status: 'ASSESSED' | 'NOT_ASSESSED' | 'UNAVAILABLE';
    readonly feedback: AttemptFeedback;
    readonly canonicalEffects: boolean;
    readonly transition: {
        readonly beforeLevel: number;
        readonly afterLevel: number;
        readonly nextDue: string;
    } | null;
}

export interface HintResult {
    readonly presentationId: string;
    readonly blankId: string;
    readonly firstLetter: string;
}

export interface TranscriptReveal {
    readonly presentationId: string;
    readonly content: LearnerContent;
}

export interface StudyWriteResult<T> { readonly value: T; readonly replayed: boolean; }

export type MaterialProgressState = 'NOT_STARTED' | 'LEARNING' | 'DUE' | 'ON_TRACK';

export interface MaterialProgress {
    readonly title: string;
    readonly memberKey: string;
    readonly itemRevisionId: string;
    readonly state: MaterialProgressState;
    readonly objectiveCoverage: { readonly enabled: number; readonly introduced: number; readonly assessed: number };
    readonly lastAssessedAt: string | null;
    readonly nextDue: string | null;
}

export interface StudyProgressPage {
    readonly asOf: string;
    readonly items: readonly MaterialProgress[];
    readonly nextCursor: string | null;
}

export interface ReplaySource {
    readonly sessionId: string;
    readonly completedAt: string;
    readonly presentationCount: number;
}

export interface ReplaySources {
    readonly asOf: string;
    readonly localStudyDate: string;
    readonly items: readonly ReplaySource[];
}

export interface RestartAcknowledgement {
    readonly commandId: string;
    readonly restartedAt: string;
    readonly objectiveCount: number;
    readonly learningEpochs: readonly { readonly objectiveId: string; readonly learningEpoch: string }[];
}

export interface StudyRecoverySnapshot {
    readonly deckId: string;
    readonly sessionId: string;
    readonly pending: AttemptCommand | null;
}

export class StudyProtocolError extends Error {
    constructor(message: string) {
        super(message);
        this.name = 'StudyProtocolError';
    }
}
