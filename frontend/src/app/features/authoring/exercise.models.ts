import { NativeDocument, NativeNode } from '../../content/native-document';

export const EXERCISE_PAGE_SIZE = 20;

export type ExerciseType = 'SELF_CHECK' | 'TYPED' | 'CLOZE_SINGLE' | 'SINGLE_CHOICE'
    | 'LISTEN_CHOICE' | 'AUDIO_TEXT_MATCH' | 'LISTEN_TYPE';
export type BindingRole = 'ASSESSED' | 'CUE' | 'OPTION' | 'CONTEXT';

export interface TextAnswerContract {
    readonly schemaVersion: 1;
    readonly normalization: readonly ('UNICODE_NFC' | 'TRIM' | 'CASE_FOLD')[];
    readonly accepted: readonly string[];
    readonly matchingMode?: 'STRICT' | 'SOFT';
}
export interface MatchAnswerContract {
    readonly schemaVersion: 2;
    readonly pairs: readonly { readonly cueId: string; readonly optionId: string }[];
}
export type AnswerContract = TextAnswerContract | MatchAnswerContract;

export interface ExerciseObjective {
    readonly objectiveId: string;
    readonly objectiveKey: string;
    readonly objectiveRevisionId: string;
    readonly objectiveVersion: string;
    readonly memberKey: string;
    readonly answerContract: AnswerContract;
}

export interface ExerciseSummary {
    readonly exerciseId: string;
    readonly exerciseRevisionId: string;
    readonly exerciseVersion: string;
    readonly ordinal: number;
    readonly type: ExerciseType;
    readonly enabled: boolean;
    readonly schemaVersion: 1;
    readonly createdAt: string;
    readonly updatedAt: string;
    readonly objective: ExerciseObjective;
}

export interface ExercisePage {
    readonly deckId: string;
    readonly deckRevisionId: string;
    readonly deckVersion: string;
    readonly total: number;
    readonly exercises: readonly ExerciseSummary[];
    readonly nextCursor: string | null;
}

export interface NodePrompt {
    readonly kind: 'NODE_TEXT';
    readonly memberKey: string;
    readonly itemRevisionId: string;
    readonly nodeId: string;
    readonly blank?: ClozeBlank;
}

export type ClozeBlank = { readonly mode: 'FIXED'; readonly length: number } | { readonly mode: 'ANSWER_LENGTH' };
export interface CustomPrompt { readonly kind: 'CUSTOM_TEXT'; readonly text: string; readonly blank?: ClozeBlank; }
export interface AudioAssetPrompt {
    readonly kind: 'AUDIO_ASSET';
    readonly assetId: string;
    readonly title: string;
    readonly instruction: string;
    readonly transcript: string;
}
export interface AudioMatchPrompt {
    readonly kind: 'AUDIO_MATCH';
    readonly instruction: string;
    readonly cues: readonly {
        readonly cueId: string; readonly assetId: string; readonly title: string; readonly transcript: string;
    }[];
}
export type ExercisePrompt = NodePrompt | CustomPrompt | AudioAssetPrompt | AudioMatchPrompt;

export interface ExerciseBinding {
    readonly bindingId: string;
    readonly role: BindingRole;
    readonly memberKey: string;
    readonly itemRevisionId: string;
    readonly nodeIds: readonly string[];
    readonly display: { readonly kind: 'NODE_TEXT' } | { readonly kind: 'CUSTOM_TEXT'; readonly text: string };
    readonly ordinal: number;
}

export interface ExerciseDetail extends ExerciseSummary {
    readonly deckId: string;
    readonly deckRevisionId: string;
    readonly deckVersion: string;
    readonly prompt: ExercisePrompt;
    readonly evaluatorPolicy: { readonly id: string; readonly version: '1' };
    readonly bindings: readonly ExerciseBinding[];
}

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
        const text = nodeText(node).replace(/\s+/gu, ' ').trim();
        if ((node.type === 'paragraph' || node.type === 'heading' || node.type === 'code_block') && text.length > 0) {
            projections.push({ nodeId: node.id, text, label: text.length <= 80 ? text : `${text.slice(0, 77)}…` });
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
    return node.content.map(nodeText).filter(Boolean).join(' ');
}
