import type { NativeDocument } from '../../../content/native-document';
import { documentOf, nativeNode } from '../../../content/rendering/native-renderer.fixtures';
import { WIRE_DEFAULT_CHOICES, PlanSummary, PlanUnit, UnitSides, UpdatePlanPage, WireClass, WireKind } from './update-plan.models';

/** Fixtures of the update review specs. Pages are in the parsed form (what `UpdatePlanApiService` hands the page). */
export const DECK_ID = '33333333-3333-4333-8333-333333333333';
export const PLAN_VERSION = `sha256:${'ab'.repeat(32)}`;
export const APPLYING_COMMAND = 'abababab-abab-4bab-8bab-abababababab';

export const keyOf = (index: number): string => `aaaaaaaa-aaaa-4aaa-8aaa-${index.toString().padStart(12, '0')}`;

export function planUnit(kind: WireKind, index: number, unitClass: WireClass, extra: Partial<PlanUnit> = {}): PlanUnit {
    const key = keyOf(index);
    return {
        unitId: `${kind}:${key}`, kind, key, class: unitClass,
        reason: unitClass === 'CONFLICT' ? 'BOTH_CHANGED' : unitClass === 'ASK_AGAIN' ? 'CHANGED_AFTER_DECISION' : null,
        title: `${kind === 'EXERCISE' ? 'Упражнение' : 'Материал'} ${index}`, dependsOn: [], defaultChoice: WIRE_DEFAULT_CHOICES[unitClass], ...extra
    };
}

export const NO_SUMMARY: PlanSummary = { newMaterials: 0, newExercises: 0, fixes: 0, conflicts: 0, deletedByAuthor: 0, askAgain: 0 };

/** The summary of a list of units, as the server would count it. */
export function summaryOf(units: readonly PlanUnit[]): PlanSummary {
    const count = (predicate: (unit: PlanUnit) => boolean) => units.filter(predicate).length;
    return {
        newMaterials: count(unit => unit.class === 'NEW' && unit.kind === 'MATERIAL'), newExercises: count(unit => unit.class === 'NEW' && unit.kind === 'EXERCISE'),
        fixes: count(unit => unit.class === 'SAFE'), conflicts: count(unit => unit.class === 'CONFLICT'),
        deletedByAuthor: count(unit => unit.class === 'DELETED'), askAgain: count(unit => unit.class === 'ASK_AGAIN')
    };
}

export function readyPage(units: readonly PlanUnit[], extra: Partial<UpdatePlanPage> = {}): UpdatePlanPage {
    return {
        state: 'READY', forkDeckId: DECK_ID, planVersion: PLAN_VERSION, releaseNote: null, summary: summaryOf(units), units, nextCursor: null, lastApplyFailed: null, commandId: null, ...extra
    };
}

export function closedPage(state: UpdatePlanPage['state']): UpdatePlanPage {
    return { state, forkDeckId: DECK_ID, planVersion: null, releaseNote: null, summary: null, units: [], nextCursor: null, lastApplyFailed: null, commandId: state === 'APPLYING' ? APPLYING_COMMAND : null };
}

export const SIDE_PARAGRAPH = (text: string, id = 100) => nativeNode('paragraph', {}, [nativeNode('text', { text, marks: [] }, [], { id: `e6000000-0000-4000-8000-${(id + 1).toString(16).padStart(12, '0')}` })], { id: `e6000000-0000-4000-8000-${id.toString(16).padStart(12, '0')}` });
export const DOC_MINE: NativeDocument = documentOf([SIDE_PARAGRAPH('Мой вариант')]);
export const DOC_AUTHOR: NativeDocument = documentOf([SIDE_PARAGRAPH('Вариант автора')]);

export function materialSides(unit: PlanUnit, mine: NativeDocument | null, author: NativeDocument | null): UnitSides {
    return {
        unitId: unit.unitId, kind: 'MATERIAL', class: unit.class, planVersion: PLAN_VERSION, changedNodeIds: [],
        mine: mine && { kind: 'MATERIAL', memberKey: unit.key, document: mine, unavailable: false },
        author: author && { kind: 'MATERIAL', memberKey: unit.key, document: author, unavailable: false }
    };
}

export function exerciseSides(unit: PlanUnit): UnitSides {
    return {
        unitId: unit.unitId, kind: 'EXERCISE', class: unit.class, planVersion: PLAN_VERSION, changedNodeIds: [], mine: null,
        author: {
            kind: 'EXERCISE', exerciseId: unit.key, unavailable: false, type: 'CHOICE', enabled: true, objectiveTitle: null,
            content: { prompt: [{ kind: 'TEXT', text: 'Что значит hablar?' }], selectionMode: 'SINGLE', options: [
                { optionId: 'o1', blocks: [{ kind: 'TEXT', text: 'говорить' }] }, { optionId: 'o2', blocks: [{ kind: 'TEXT', text: 'есть' }] }] },
            answerKey: { kind: 'CHOICE', correctOptionIds: ['o1'] }
        }
    };
}
