import { NativeDocument, NativeNode } from '../../content/native-document';
import { readNativeCodeBlock } from '../../content/rendering/native-render-state';
import { ExerciseSpec, Mechanic } from '../../content/exercise/exercise-content.models';

export const EXERCISE_PAGE_SIZE = 20;

export interface ExerciseObjective {
    readonly objectiveId: string;
    readonly objectiveKey: string;
    readonly objectiveRevisionId: string;
    readonly objectiveVersion: string;
    readonly memberKey: string;
    /** Author-facing name of the single assessed objective. */
    readonly title: string;
}

export interface ExerciseSummary {
    readonly exerciseId: string;
    readonly exerciseRevisionId: string;
    readonly exerciseVersion: string;
    readonly ordinal: number;
    readonly type: Mechanic;
    readonly enabled: boolean;
    readonly schemaVersion: 2;
    readonly createdAt: string;
    readonly updatedAt: string;
    readonly objective: ExerciseObjective;
    /**
     * The server mark «Новое» (AI-13, #291): a generated exercise that was saved less than 7 days ago and has not been opened
     * or answered yet. Absent from the wire means «not new».
     */
    readonly isNew: boolean;
}

export interface ExercisePage {
    readonly deckId: string;
    readonly deckRevisionId: string;
    readonly deckVersion: string;
    readonly total: number;
    readonly exercises: readonly ExerciseSummary[];
    readonly nextCursor: string | null;
}

export interface ExerciseEnvelope {
    readonly exerciseId: string;
    readonly exerciseRevisionId: string;
    readonly exerciseVersion: string;
    readonly ordinal: number;
    readonly createdAt: string;
    readonly updatedAt: string;
    readonly objective: ExerciseObjective;
    readonly deckId: string;
    readonly deckRevisionId: string;
    readonly deckVersion: string;
}

/** The persisted exercise: identity and deck pins plus the typed mechanic-specific specification. */
export type ExerciseDetail = ExerciseEnvelope & ExerciseSpec;

export interface ExerciseAcknowledgement {
    readonly commandId: string;
    readonly deckId: string;
    readonly deckRevisionId: string;
    readonly deckVersion: string;
    readonly objectiveId: string;
    readonly objectiveKey: string;
    readonly objectiveRevisionId: string;
    readonly exerciseId: string;
    readonly exerciseRevisionId: string;
    readonly enabled: boolean;
}

export interface ExerciseWriteResult {
    readonly acknowledgement: ExerciseAcknowledgement;
    readonly replayed: boolean;
}

export interface ExerciseProjection {
    readonly nodeId: string;
    readonly label: string;
    readonly text: string;
}

export function textProjections(document: NativeDocument): readonly ExerciseProjection[] {
    const projections: ExerciseProjection[] = [];
    const visit = (node: NativeNode): void => {
        // Code keeps its line breaks and indentation; prose is collapsed for compact labels.
        const raw = nodeText(node);
        const text = node.type === 'code_block' ? raw.replace(/^\s*\n|\s+$/gu, '') : raw.replace(/\s+/gu, ' ').trim();
        if ((node.type === 'paragraph' || node.type === 'heading' || node.type === 'code_block') && text.length > 0) {
            projections.push({ nodeId: node.id, text, label: oneLine(text) });
        }
        node.content.forEach(visit);
    };
    visit(document.root);
    const unique = new Map<string, ExerciseProjection>();
    projections.forEach(value => unique.set(value.nodeId, value));
    return [...unique.values()];
}

export function nodeText(node: NativeNode): string {
    if (node.type === 'text' && typeof node.attrs['text'] === 'string') return node.attrs['text'];
    // The same text the server projects: the source of a valid code block, nothing for a retained opaque one.
    if (node.type === 'code_block') return readNativeCodeBlock(node)?.source ?? '';
    return node.content.map(nodeText).filter(Boolean).join(' ');
}

function oneLine(text: string): string {
    const collapsed = text.replace(/\s+/gu, ' ').trim();
    return collapsed.length <= 80 ? collapsed : `${collapsed.slice(0, 77)}…`;
}

/** The publication-shaped specification of a persisted exercise: its envelope and deck pins removed. */
export function specOf(detail: ExerciseDetail): ExerciseSpec {
    const { type, schemaVersion, enabled, subject, content, answerKey, evaluatorPolicy } = detail;
    return { type, schemaVersion, enabled, subject, content, answerKey, evaluatorPolicy } as ExerciseSpec;
}
