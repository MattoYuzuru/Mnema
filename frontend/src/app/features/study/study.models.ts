export type StudyMode = 'SCHEDULED' | 'REPLAY' | 'PRACTICE';
export type StudyStatus = 'ACTIVE' | 'EMPTY' | 'COMPLETE';
export type StudyExerciseType = 'SELF_CHECK' | 'TYPED' | 'CLOZE_SINGLE' | 'SINGLE_CHOICE';
export type SelfRating = 'NOT_RECALLED' | 'HINTED' | 'PARTIAL' | 'FULL';

export interface StudyPresentation {
    readonly presentationId: string;
    readonly nonce: string;
    readonly ordinal: number;
    readonly exerciseRevisionId: string;
    readonly type: StudyExerciseType;
    readonly objectiveId: string;
    readonly objectiveRevisionId: string;
    readonly learningEpoch: string;
    readonly reference: string;
    readonly prompt: { readonly kind: 'TEXT'; readonly text: string };
    readonly options: readonly { readonly optionId: string; readonly text: string }[];
    readonly bindings: readonly StudyBinding[];
    readonly evaluator: { readonly id: string; readonly version: string };
}

export interface StudyBinding {
    readonly bindingId: string;
    readonly role: 'ASSESSED' | 'CUE' | 'OPTION' | 'CONTEXT';
    readonly memberKey: string;
    readonly itemRevisionId: string;
    readonly ordinal: number;
    readonly nodeIds: readonly string[];
    readonly display: { readonly kind: string };
}

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
    | { readonly kind: 'CHOICE'; readonly optionId: string }
    | { readonly kind: 'CANCEL' };

export interface AttemptCommand {
    readonly attemptId: string;
    readonly presentationId: string;
    readonly nonce: string;
    readonly response: StudyResponse;
    readonly hintsUsed: readonly string[];
    readonly confidence: 'KNEW' | 'UNSURE' | 'GUESSED' | null;
    readonly durationMs: number;
}

export interface AttemptFeedback {
    readonly result: 'CORRECT' | 'PARTIAL' | 'UNSURE' | 'INCORRECT' | 'NOT_ASSESSED' | 'UNAVAILABLE';
    readonly reference: string | null;
    readonly appliedRules: readonly string[];
    readonly reasonCodes: readonly string[];
}

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

export interface StudyWriteResult<T> { readonly value: T; readonly replayed: boolean; }

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
