import { ExerciseSpec, Mechanic, ObjectiveCommand, previewExerciseOf } from '../../content/exercise/exercise-content.models';
import { ExerciseContentError, parseExerciseSpec, parseObjectiveCommand } from '../../content/exercise/exercise-content.parse';
import { MaterialContext, learnerContent } from '../authoring/exercise-draft';
import { PreviewPresentation } from '../authoring/exercise-preview.models';
import { ExerciseProjection } from '../authoring/exercise.models';
import { ArtifactDetail } from './generation.models';

/**
 * One proposed exercise of a Workshop batch, read from the artifact detail: the create command the server stored
 * (`{objective, exercise}`) and the display members it adds (decision 14 of `contracts/generation`). Everything here is pure.
 */
export interface ExerciseProposal {
    readonly artifactId: string;
    readonly revisionId: string;
    readonly mechanic: Mechanic;
    readonly objective: ObjectiveCommand;
    readonly objectiveTitle: string;
    readonly exercise: ExerciseSpec;
    /** The plain text of every `MATERIAL` node the exercise quotes, as it is in the pinned revision. */
    readonly quotes: Readonly<Record<string, string>>;
}

/** The proposal of an exercise artifact, or `null` when the revision is missing or is not a command this client can read. */
export function readProposal(detail: ArtifactDetail): ExerciseProposal | null {
    const payload = detail.revision?.payload;
    if (payload === undefined || payload.kind !== 'EXERCISE_COMMAND') return null;
    const command = payload.command;
    if (command === null || typeof command !== 'object' || Array.isArray(command)) return null;
    const object = command as Record<string, unknown>;
    try {
        const objective = parseObjectiveCommand(object['objective']);
        const exercise = parseExerciseSpec(object['exercise']);
        return {
            artifactId: detail.artifactId, revisionId: detail.revision!.revisionId, mechanic: exercise.type, objective, exercise,
            objectiveTitle: detail.display?.objectiveTitle ?? (objective.operation === 'reuse' ? '' : objective.title),
            quotes: detail.display?.quotes ?? {}
        };
    } catch (error) {
        if (error instanceof ExerciseContentError) return null;
        throw error;
    }
}

/** The quotes as the projections the exercise helpers resolve `MATERIAL` blocks with. */
export function quoteContext(quotes: Readonly<Record<string, string>>): MaterialContext {
    const projections: readonly ExerciseProjection[] = Object.entries(quotes).map(([nodeId, text]) => {
        const collapsed = text.replace(/\s+/gu, ' ').trim();
        return { nodeId, text, label: collapsed.length <= 80 ? collapsed : `${collapsed.slice(0, 77)}…` };
    });
    return { projections };
}

/**
 * What the preview host plays for a proposal: the learner view of the exercise (the quoted material resolved from the quotes)
 * and the exercise the author-preview endpoint evaluates. The key changes with the revision, so a new revision starts a new trial.
 */
export function proposalPresentation(proposal: ExerciseProposal): PreviewPresentation {
    const exercise = previewExerciseOf(proposal.exercise);
    const context = quoteContext(proposal.quotes);
    return {
        mode: 'PROPOSAL', key: `proposal:${proposal.artifactId}:${proposal.revisionId}`, exercise, blockedReason: null,
        learner: revealed => learnerContent(exercise, { context, revealed, placeholders: false })
    };
}
