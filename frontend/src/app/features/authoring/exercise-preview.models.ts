import { LearnerContent, PreviewExercise } from '../../content/exercise/exercise-content.models';
import { StudyResponse } from '../study/study.models';

export type PreviewResponse = Exclude<StudyResponse, { readonly kind: 'CANCEL' }>;

/** What the browser tracked during one trial attempt; the server evaluates it with the Study evaluator. */
export interface PreviewSubmission {
    readonly response: PreviewResponse;
    readonly hintedBlankIds: readonly string[];
    readonly pairMistakes: boolean;
    readonly transcriptRevealed: boolean;
}

export type PreviewMode = 'DEMO' | 'AUTHOR_DRAFT' | 'AUTHOR_READY';

/**
 * What the preview host shows and evaluates right now. It is derived from the page state and never written
 * back: a trial attempt can change nothing in the draft.
 */
export interface PreviewPresentation {
    readonly mode: PreviewMode;
    /** Changes exactly when what the learner sees or how it is evaluated changes; a new key restarts the trial. */
    readonly key: string;
    readonly learner: (transcriptsRevealed: boolean) => LearnerContent;
    /** The exercise the server evaluates; null while the draft is incomplete (and never a demo mixed with a draft). */
    readonly exercise: PreviewExercise | null;
    /** Why checking is not possible yet: the first message of the draft validation. */
    readonly blockedReason: string | null;
}
