/** Wire and view models of `GET/PUT /api/learning-profile` (the answer to «Для чего вам Mnema?»). */

export const LEARNING_GOALS = ['EXAMS', 'INTERVIEW', 'LANGUAGE', 'WORK', 'SELF'] as const;
export type LearningGoal = (typeof LEARNING_GOALS)[number];

/**
 * Unanswered: `goal` and `answeredAt` are null. A skip is `skipped: true`, a null goal and an `answeredAt`: the question
 * is never asked again.
 */
export interface LearningProfile {
    readonly goal: LearningGoal | null;
    readonly skipped: boolean;
    readonly answeredAt: string | null;
}
