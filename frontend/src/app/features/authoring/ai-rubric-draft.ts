import {
    AiRubric, CRITERION_TIERS, CriterionTier, CriterionWeight, LIMITS, isBlank
} from '../../content/exercise/exercise-content.models';

/**
 * Editable rubric v1 of an `ai-semantic` exercise. Rows carry stable ids so the editor can keep focus and
 * identity while the author adds, edits and removes them; the ids of criteria are the wire `criterionId`s.
 */
export interface RubricCriterionDraft {
    readonly criterionId: string;
    readonly description: string;
    readonly tier: CriterionTier;
    readonly weight: CriterionWeight;
}
export interface RubricTextRow { readonly id: string; readonly value: string; }
export interface AiRubricDraft {
    readonly referenceAnswer: string;
    /** The authored order is kept as typed; nothing is sorted by tier. */
    readonly criteria: readonly RubricCriterionDraft[];
    readonly misconceptions: readonly RubricTextRow[];
    readonly acceptableTerms: readonly RubricTextRow[];
}

export const TIER_LABELS: Readonly<Record<CriterionTier, string>> = { CORE: 'Суть', DETAIL: 'Детали', TERM: 'Термины' };
export const TIER_HINTS: Readonly<Record<CriterionTier, string>> = {
    CORE: 'без этого ответ не засчитают', DETAIL: 'делают ответ полным', TERM: 'точные слова и обозначения'
};

const DEFAULT_WEIGHT: Readonly<Record<CriterionTier, CriterionWeight>> = { CORE: 3, DETAIL: 1, TERM: 1 };

function newId(): string { return crypto.randomUUID(); }

export function newCriterion(tier: CriterionTier = 'CORE'): RubricCriterionDraft {
    return { criterionId: newId(), description: '', tier, weight: DEFAULT_WEIGHT[tier] };
}

export function newTextRow(value = ''): RubricTextRow { return { id: newId(), value }; }

/** A starting rubric already shaped like a valid one (two essential points and one detail), only its words are missing. */
export function emptyRubric(): AiRubricDraft {
    return { referenceAnswer: '', criteria: [newCriterion('CORE'), newCriterion('CORE'), newCriterion('DETAIL')],
        misconceptions: [], acceptableTerms: [] };
}

export function rubricDraft(rubric: AiRubric): AiRubricDraft {
    return { referenceAnswer: rubric.referenceAnswer,
        criteria: rubric.criteria.map(criterion => ({ ...criterion })),
        misconceptions: rubric.misconceptions.map(value => newTextRow(value)),
        acceptableTerms: rubric.acceptableTerms.map(value => newTextRow(value)) };
}

export function rubricSpec(draft: AiRubricDraft): AiRubric {
    return { referenceAnswer: draft.referenceAnswer,
        criteria: draft.criteria.map(({ criterionId, description, tier, weight }) => ({ criterionId, description, tier, weight })),
        misconceptions: draft.misconceptions.map(row => row.value),
        acceptableTerms: draft.acceptableTerms.map(row => row.value) };
}

export function tierCount(draft: AiRubricDraft, tier: CriterionTier): number {
    return draft.criteria.filter(criterion => criterion.tier === tier).length;
}

/**
 * The tier a new key point most likely needs: a tier that is still short of its minimum (essence first), otherwise
 * another detail, then another essential point, then terminology.
 */
export function nextTier(draft: AiRubricDraft): CriterionTier {
    for (const tier of CRITERION_TIERS) {
        if (tierCount(draft, tier) < LIMITS.aiRubric.tiers[tier].min) return tier;
    }
    return (['DETAIL', 'CORE', 'TERM'] as const).find(tier => tierCount(draft, tier) < LIMITS.aiRubric.tiers[tier].max) ?? 'DETAIL';
}

export function tierRange(tier: CriterionTier): string {
    const { min, max } = LIMITS.aiRubric.tiers[tier];
    return min === max ? `${min}` : `${min}–${max}`;
}

function criteriaProblem(draft: AiRubricDraft): string | null {
    const wrong = CRITERION_TIERS.filter(tier => {
        const count = tierCount(draft, tier);
        return count < LIMITS.aiRubric.tiers[tier].min || count > LIMITS.aiRubric.tiers[tier].max;
    });
    if (wrong.length === 0) return null;
    return `Нужно: «${TIER_LABELS.CORE}» — ${tierRange('CORE')}, «${TIER_LABELS.DETAIL}» — ${tierRange('DETAIL')}, «${TIER_LABELS.TERM}» — ${tierRange('TERM')}. `
        + `Сейчас не подходит: ${wrong.map(tier => `«${TIER_LABELS[tier]}» (${tierCount(draft, tier)})`).join(', ')}.`;
}

function listProblem(rows: readonly RubricTextRow[], limit: { readonly max: number; readonly length: number }, noun: string): string | null {
    if (rows.length > limit.max) return `Не больше ${limit.max} ${noun}.`;
    if (rows.some(row => isBlank(row.value))) return 'Заполните или удалите пустые строки.';
    if (rows.some(row => row.value.length > limit.length)) return `Одна из строк длиннее ${limit.length} знаков.`;
    return null;
}

/** Author-facing messages by field key (all keys start with `rubric:`); an empty map means the rubric is valid. */
export function rubricErrors(draft: AiRubricDraft): Readonly<Record<string, string>> {
    const errors: Record<string, string> = {};
    const limits = LIMITS.aiRubric;
    if (isBlank(draft.referenceAnswer)) errors['rubric:reference'] = 'Напишите эталонный ответ: по нему проверяется смысл.';
    else if (draft.referenceAnswer.length > limits.referenceAnswer) {
        errors['rubric:reference'] = `Эталонный ответ длиннее ${limits.referenceAnswer} знаков (сейчас ${draft.referenceAnswer.length}). Сократите его.`;
    }
    const criteria = criteriaProblem(draft);
    if (criteria !== null) errors['rubric:criteria'] = criteria;
    for (const criterion of draft.criteria) {
        if (isBlank(criterion.description)) errors[`rubric:criterion:${criterion.criterionId}`] = 'Опишите пункт или удалите его.';
        else if (criterion.description.length > limits.description) {
            errors[`rubric:criterion:${criterion.criterionId}`] = `Пункт длиннее ${limits.description} знаков.`;
        }
    }
    const mistakes = listProblem(draft.misconceptions, limits.misconceptions, 'типичных ошибок');
    if (mistakes !== null) errors['rubric:misconceptions'] = mistakes;
    const terms = listProblem(draft.acceptableTerms, limits.acceptableTerms, 'терминов');
    if (terms !== null) errors['rubric:terms'] = terms;
    return errors;
}
