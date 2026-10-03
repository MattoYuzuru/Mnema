import { LearnerBlock, LearnerContent, Mechanic } from '../../content/exercise/exercise-content.models';

export type StudyMode = 'SCHEDULED' | 'REPLAY' | 'PRACTICE';
export type StudyStatus = 'ACTIVE' | 'EMPTY' | 'COMPLETE';
export type StudyExerciseType = Mechanic;
export type SelfRating = 'NOT_RECALLED' | 'HINTED' | 'PARTIAL' | 'FULL';
export const SELF_RATINGS: readonly SelfRating[] = ['NOT_RECALLED', 'HINTED', 'PARTIAL', 'FULL'];
export const SELF_RATING_LABELS: Readonly<Record<SelfRating, string>> = {
    NOT_RECALLED: 'Не вспомнил', HINTED: 'Вспомнил с подсказкой', PARTIAL: 'Вспомнил частично', FULL: 'Вспомнил полностью'
};
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
    /**
     * The exercise carries the server mark «Новое» (AI-13, #291), evaluated when the presentation was issued; a replayed
     * presentation is never new.
     */
    readonly isNew: boolean;
    /**
     * Present only while an answer to this presentation is being graded (AI-20, #292): a reload resumes it instead of
     * asking again. It never carries the rubric, criteria or reference.
     */
    readonly assessment?: { readonly attemptId: string; readonly status: 'ASSESSING' | 'SELF_CHECK' };
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
    | { readonly kind: 'ORDER'; readonly sequence: readonly string[] }
    | { readonly kind: 'CATEGORIZE'; readonly assignments: readonly { readonly itemId: string; readonly categoryId: string }[] }
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
export type AssessmentStrictness = 'S1' | 'S2' | 'S3';
export type AssessmentJudgement = 'COMPLETE' | 'PARTIAL' | 'INSUFFICIENT';
export interface CoveredPoint {
    readonly criterionId: string;
    readonly description: string;
    /** The learner's own words, verbatim. */
    readonly quote: string;
    readonly partial: boolean;
}
export interface MissingPoint { readonly criterionId: string; readonly description: string; readonly partial: boolean; }
export interface ContradictedPoint { readonly criterionId: string; readonly description: string; readonly note: string; }
/** What the grader found in an `ai-semantic` answer; the server already aggregated it into `judgement`. */
export interface AssessmentFeedback {
    readonly strictness: AssessmentStrictness;
    readonly judgement: AssessmentJudgement;
    readonly covered: readonly CoveredPoint[];
    readonly missing: readonly MissingPoint[];
    readonly contradicted: readonly ContradictedPoint[];
    readonly nextStricter: boolean;
}
export interface FreeResponseFeedback {
    readonly result: AssessedResult;
    readonly appliedRules: readonly string[];
    readonly reference: string;
    readonly referenceContent: readonly LearnerBlock[];
    /** Only for an answer graded by the model; a self-rated one carries the reference alone. */
    readonly assessment?: AssessmentFeedback;
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
/** Binary: the right sequence, or not. `positions[].correct` already treats identical items as interchangeable. */
export interface OrderFeedback {
    readonly result: AssessedResult;
    readonly appliedRules: readonly string[];
    readonly correctSequence: readonly string[];
    readonly positions: readonly { readonly position: number; readonly selectedItemId: string; readonly correct: boolean }[];
}
export interface CategorizeFeedback {
    readonly result: AssessedResult;
    readonly appliedRules: readonly string[];
    readonly assignments: readonly {
        readonly itemId: string; readonly selectedCategoryId: string; readonly correctCategoryId: string; readonly correct: boolean;
    }[];
}
/**
 * NOT_ASSESSED / UNAVAILABLE: no learner verdict and no canonical effect. A disputed grade keeps the reference so the
 * learner can still compare.
 */
export interface UnassessedFeedback {
    readonly result: 'NOT_ASSESSED' | 'UNAVAILABLE';
    readonly reasonCodes: readonly string[];
    readonly reference?: string;
    readonly referenceContent?: readonly LearnerBlock[];
}
export type AttemptFeedback = SelfCheckFeedback | FreeResponseFeedback | ClozeFeedback | ChoiceFeedback
    | MatchFeedback | OrderFeedback | CategorizeFeedback | UnassessedFeedback;

export function isClozeFeedback(value: AttemptFeedback): value is ClozeFeedback { return 'blanks' in value; }
export function isChoiceFeedback(value: AttemptFeedback): value is ChoiceFeedback { return 'correctOptionIds' in value; }
export function isMatchFeedback(value: AttemptFeedback): value is MatchFeedback { return 'pairs' in value; }
export function isOrderFeedback(value: AttemptFeedback): value is OrderFeedback { return 'correctSequence' in value; }
export function isCategorizeFeedback(value: AttemptFeedback): value is CategorizeFeedback { return 'assignments' in value; }
export function isFreeResponseFeedback(value: AttemptFeedback): value is FreeResponseFeedback {
    return 'referenceContent' in value && !('reasonCodes' in value);
}
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
    /** The learner disputed the AI grade; the progress it caused was taken back. */
    readonly disputed: boolean;
}

/** Why the learner rates themselves instead of receiving a model grade. Never a provider detail. */
export type SelfCheckReason = 'LEARNER_CHOICE' | 'PROVIDER_UNCERTAIN' | 'PROVIDER_UNAVAILABLE' | 'USAGE_LIMIT'
    | 'CAPABILITY_UNAVAILABLE' | 'DEADLINE' | 'BUSY';

/** An answer to an `ai-semantic` presentation that is still being graded. */
export interface AssessingAttempt {
    readonly attemptId: string;
    readonly presentationId: string;
    readonly mode: StudyMode;
    readonly status: 'ASSESSING';
    readonly retryAfterMs: number;
}

/** The learner compares and rates themselves: the reference and the key points appear only now, after the answer. */
export interface SelfCheckAttempt {
    readonly attemptId: string;
    readonly presentationId: string;
    readonly mode: StudyMode;
    readonly status: 'SELF_CHECK';
    readonly reason: SelfCheckReason;
    readonly selfCheck: {
        readonly reference: string;
        readonly referenceContent: readonly LearnerBlock[];
        readonly criteria: readonly { readonly criterionId: string; readonly description: string }[];
    };
}

/** What a submit or a poll of one attempt returns: still grading, self-check, or the stored outcome. */
export type AttemptState = AssessingAttempt | SelfCheckAttempt | AttemptOutcome;

export function isAssessing(state: AttemptState): state is AssessingAttempt { return state.status === 'ASSESSING'; }
export function isSelfCheckAttempt(state: AttemptState): state is SelfCheckAttempt { return state.status === 'SELF_CHECK'; }
export function isAttemptOutcome(state: AttemptState): state is AttemptOutcome { return 'feedback' in state; }

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
