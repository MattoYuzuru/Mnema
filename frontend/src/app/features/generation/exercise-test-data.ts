import { ExerciseSpec } from '../../content/exercise/exercise-content.models';
import { clone, examples, ids, sessionWith } from './generation-test-data';

/** Fixtures of an `EXERCISES` Workshop (AI-13, #291): the shapes the backend implementer returns, per the handoff. */

export const materialIds = {
    first: '44444444-4444-4444-8444-444444444441',
    second: '44444444-4444-4444-8444-444444444442',
    third: '44444444-4444-4444-8444-444444444443'
};
export const materialRevisions = {
    first: '55555555-5555-4555-8555-555555555551',
    second: '55555555-5555-4555-8555-555555555552',
    third: '55555555-5555-4555-8555-555555555553'
};
export const nodeIds = { first: '00000000-0000-4000-8000-000000000004', second: '00000000-0000-4000-8000-000000000005' };
export const exerciseIds = {
    exercise: '88888888-8888-4888-8888-888888888881', exerciseRevision: '88888888-8888-4888-8888-888888888882',
    objective: '77777777-7777-4777-8777-777777777771', objectiveRevision: '77777777-7777-4777-8777-777777777773'
};

export const QUOTE = 'Seq Scan читает всю таблицу, Index Scan идёт по индексу.';

type Pins = { readonly memberKey: string; readonly itemRevisionId: string };

/** A SELF_CHECK exercise whose reference quotes a node of the material. */
export function selfCheck(pins: Pins = { memberKey: materialIds.first, itemRevisionId: materialRevisions.first }, prompt = 'Когда планировщик выберет Seq Scan?'): ExerciseSpec {
    return { type: 'SELF_CHECK', schemaVersion: 2, enabled: true, subject: pins,
        content: { prompt: [{ kind: 'TEXT', text: prompt }],
            reference: [{ kind: 'MATERIAL', memberKey: pins.memberKey, itemRevisionId: pins.itemRevisionId, nodeId: nodeIds.first }] },
        answerKey: { kind: 'SELF_REPORT' }, evaluatorPolicy: { id: 'self-check', version: '1' } };
}

export const optionIds = { right: '99999999-9999-4999-8999-999999999991', wrong: '99999999-9999-4999-8999-999999999992' };

export function choice(pins: Pins = { memberKey: materialIds.first, itemRevisionId: materialRevisions.first }): ExerciseSpec {
    return { type: 'CHOICE', schemaVersion: 2, enabled: true, subject: pins,
        content: { prompt: [{ kind: 'TEXT', text: 'Что читает Seq Scan?' }], selectionMode: 'SINGLE',
            options: [{ optionId: optionIds.right, blocks: [{ kind: 'TEXT', text: 'Всю таблицу' }] },
                { optionId: optionIds.wrong, blocks: [{ kind: 'TEXT', text: 'Только индекс' }] }] },
        answerKey: { kind: 'CHOICE', correctOptionIds: [optionIds.right] }, evaluatorPolicy: { id: 'deterministic-choice', version: '1' } };
}

export function createCommand(exercise: ExerciseSpec, title = 'Выбор между Seq Scan и Index Scan'): Record<string, unknown> {
    return { objective: { operation: 'create', title }, exercise };
}

export const artifactIds = [1, 2, 3, 4, 5, 6].map(index => `a7a70000-0000-4000-8000-00000000000${index}`);
export const revisionIds = [1, 2, 3, 4, 5, 6].map(index => `4e700000-0000-4000-8000-00000000000${index}`);

/** `getArtifact` of an exercise proposal: the contract example with the AI-13 `display` member and the given command. */
export function exerciseDetail(artifactId: string, ordinal: number, command: Record<string, unknown> = createCommand(selfCheck()),
                               overrides: Record<string, unknown> = {}, display: Record<string, unknown> | null | 'absent' = null): Record<string, any> {
    const base = clone(examples['artifactDetailExercise']);
    const revisionId = revisionIds[ordinal]!;
    const shown = display === 'absent' ? undefined : display ?? { mechanic: (command['exercise'] as ExerciseSpec).type,
        objectiveTitle: (command['objective'] as { title?: string }).title ?? 'Выбор между Seq Scan и Index Scan', quotes: { [nodeIds.first]: QUOTE } };
    const detail = { ...base, artifactId, ordinal, rowVersion: '3', title: 'Выбор между Seq Scan и Index Scan', currentRevisionId: revisionId,
        sessionId: ids.sessionId, revision: { ...base.revision, revisionId, payload: { kind: 'EXERCISE_COMMAND', command } },
        revisions: [{ revisionId, cause: 'INITIAL', createdAt: base.revision.createdAt }], ...overrides };
    if (shown !== undefined) detail['display'] = shown; else delete detail['display'];
    return detail;
}

/** An exercise artifact of a session summary. */
export function exerciseArtifact(artifactId: string, ordinal: number, state: string, overrides: Record<string, unknown> = {}): Record<string, any> {
    const withRevision = ['PROPOSED', 'STALE', 'REJECTED', 'PUBLISHED'].includes(state);
    return { ...clone(examples['artifactSummaryProposed']), artifactId, ordinal, targetKind: 'EXERCISE', state, rowVersion: '3',
        title: 'Выбор между Seq Scan и Index Scan', currentRevisionId: withRevision ? revisionIds[ordinal]! : null, ...overrides };
}

/** The echoed spec of an exercise session (`generationSpec.EXERCISES`). */
export function exerciseSpec(targets: readonly Pins[] = [{ memberKey: materialIds.first, itemRevisionId: materialRevisions.first }]): Record<string, unknown> {
    return { kind: 'EXERCISES', outputLanguage: 'ru', targets, settings: { mechanics: 'AUTO', priority: 'UNCOVERED_FIRST',
        quantity: { mode: 'AUTO' }, planFirst: false, budgetPercent: null } };
}

export function exerciseSession(artifacts: readonly Record<string, unknown>[], overrides: Record<string, unknown> = {},
                                targets?: readonly Pins[]): Record<string, any> {
    return sessionWith(artifacts, { kind: 'EXERCISES', state: 'REVIEW', approvableCount: artifacts.filter(artifact => artifact['state'] === 'PROPOSED').length,
        spec: exerciseSpec(targets), ...overrides });
}

export function ack(commandId: string, artifactIdList: readonly string[], deckVersion = '9', deckRevisionId = '33333333-3333-4333-8333-333333333333'): Record<string, unknown> {
    return { commandId, deckId: ids.deckId, deckRevisionId, deckVersion,
        artifacts: artifactIdList.map(artifactId => ({ artifactId, state: 'PUBLISHED', publishedRef: { kind: 'EXERCISE', exerciseId: exerciseIds.exercise,
            exerciseRevisionId: exerciseIds.exerciseRevision, objectiveId: exerciseIds.objective, objectiveRevisionId: exerciseIds.objectiveRevision } })) };
}

/** A native document of one paragraph per text, as an item read answers it. */
export function documentOf(...texts: string[]): import('../../content/native-document').NativeDocument {
    return { formatVersion: 1, root: { id: '00000000-0000-4000-8000-000000000000', type: 'doc', version: 1, attrs: {},
        content: texts.map((text, index) => ({ id: `00000000-0000-4000-8000-${String(index * 2 + 1).padStart(12, '0')}`, type: 'paragraph', version: 1, attrs: {},
            content: [{ id: `00000000-0000-4000-8000-${String(index * 2 + 2).padStart(12, '0')}`, type: 'text', version: 1, attrs: { text, marks: [] }, content: [] }] })) } };
}
