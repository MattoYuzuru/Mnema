import { MECHANICS, Mechanic } from '../../content/exercise/exercise-content.models';
import { catalogEntry } from '../../content/exercise/mechanic-catalog';
import { SegmentedOption } from '../../shared/segmented-choice.component';
import { NBSP, UsageExplanation, formatDay } from './generation-view';
import {
    BlockingBucket, ExercisePriority, ExerciseQuantity, ExerciseTarget, ExercisesSpec, MAX_EXERCISES_PER_SESSION, MAX_EXERCISES_PER_TARGET,
    MAX_EXERCISE_TARGETS
} from './generation.models';

/**
 * What the user chose in the exercise builder, and the pure rules around it: the request it makes, the split of a large
 * selection into sessions, and the words. The builder page owns the state; nothing here touches the DOM or the network.
 */

/** A material the builder works on: the pin the server needs, plus what the page shows (never sent). */
export interface BuilderTarget extends ExerciseTarget {
    readonly title: string;
    /** Enabled exercises that already assess the material; `null` while unknown. Orders «Сначала без упражнений». */
    readonly exerciseCount: number | null;
}

export type QuantityMode = ExerciseQuantity['mode'];

export interface BuilderValue {
    /** An empty selection means «Авто»: it is what the request says, and what the checkboxes show. */
    readonly mechanics: readonly Mechanic[];
    readonly priority: ExercisePriority;
    readonly quantityMode: QuantityMode;
    readonly perTarget: number;
    readonly percent: number;
}

export const DEFAULT_BUILDER_VALUE: BuilderValue = {
    mechanics: [], priority: 'UNCOVERED_FIRST', quantityMode: 'AUTO', perTarget: 3, percent: 10
};

export const PRIORITY_OPTIONS: readonly SegmentedOption<ExercisePriority>[] = [
    { value: 'UNCOVERED_FIRST', label: 'Сначала без упражнений',
        hint: 'Сначала Мнема займётся материалами, у которых ещё нет упражнений: если лимита не хватит, они не останутся без них.' },
    { value: 'BALANCED', label: 'Все выбранные поровну', hint: 'Каждый выбранный материал получит свою долю упражнений.' }
];

export const QUANTITY_OPTIONS: readonly SegmentedOption<QuantityMode>[] = [
    { value: 'AUTO', label: 'Авто', hint: 'Авто: Мнема сама выберет число упражнений на материал — обычно до пяти.' },
    { value: 'EXACT', label: 'Точно', hint: 'Точно: столько упражнений на каждый материал.' },
    { value: 'BUDGET_PERCENT', label: 'Не больше X% лимита', hint: 'Мнема потратит не больше выбранной доли оставшегося лимита ИИ.' }
];

/** Russian name of a mechanic, the one the editor uses. */
export function mechanicName(mechanic: Mechanic): string {
    return catalogEntry(mechanic).title;
}

export const MECHANIC_CHOICES: readonly { readonly value: Mechanic; readonly label: string }[] =
    MECHANICS.map(value => ({ value, label: mechanicName(value) }));

/** Checking a mechanic leaves «Авто»; unchecking the last one returns to it. The result is in the editor's order. */
export function toggleMechanic(current: readonly Mechanic[], mechanic: Mechanic, checked: boolean): readonly Mechanic[] {
    const next = new Set(current);
    if (checked) next.add(mechanic); else next.delete(mechanic);
    return MECHANICS.filter(value => next.has(value));
}

/** The request for one session. The caller keeps `targets` within {@link MAX_EXERCISE_TARGETS}. */
export function buildExercisesSpec(targets: readonly ExerciseTarget[], value: BuilderValue, outputLanguage?: string): ExercisesSpec {
    // Only the pins go to the server: the title and the count the page shows are not part of the request.
    const pins = targets.map(({ memberKey, itemRevisionId }) => ({ memberKey, itemRevisionId }));
    const quantity: ExerciseQuantity = value.quantityMode === 'AUTO' ? { mode: 'AUTO' }
        : value.quantityMode === 'EXACT' ? { mode: 'EXACT', perTarget: value.perTarget }
            : { mode: 'BUDGET_PERCENT', percent: value.percent };
    return {
        kind: 'EXERCISES', ...(outputLanguage === undefined ? {} : { outputLanguage }), targets: pins,
        settings: { mechanics: value.mechanics.length === 0 ? 'AUTO' : value.mechanics, priority: value.priority, quantity }
    };
}

/**
 * A selection beyond one session's limit becomes several sessions of at most {@link MAX_EXERCISE_TARGETS} materials. With
 * «Сначала без упражнений» the materials without exercises go first (stable: the order the user saw is kept among equals), so the
 * first sessions cover the neediest ones; with «поровну» the order is the user's.
 */
export function splitTargets<T extends BuilderTarget>(targets: readonly T[], priority: ExercisePriority): readonly (readonly T[])[] {
    const ordered = priority === 'UNCOVERED_FIRST'
        ? targets.map((target, index) => ({ target, index }))
            .sort((first, second) => (first.target.exerciseCount ?? 0) - (second.target.exerciseCount ?? 0) || first.index - second.index)
            .map(entry => entry.target)
        : [...targets];
    const sessions: T[][] = [];
    for (let start = 0; start < ordered.length; start += MAX_EXERCISE_TARGETS) sessions.push(ordered.slice(start, start + MAX_EXERCISE_TARGETS));
    return sessions;
}

function plural(count: number, one: string, few: string, many: string): string {
    const lastTwo = count % 100;
    const last = count % 10;
    if (lastTwo >= 11 && lastTwo <= 14) return many;
    if (last === 1) return one;
    return last >= 2 && last <= 4 ? few : many;
}

export function materialsCount(count: number): string {
    return `${count}${NBSP}${plural(count, 'материал', 'материала', 'материалов')}`;
}

export function exercisesCount(count: number): string {
    return `${count}${NBSP}${plural(count, 'упражнение', 'упражнения', 'упражнений')}`;
}

/** «Для 7 материалов»: after «для» the noun is in the genitive, so 2 and 3 are «материалов», not «материала». */
export function targetsSummary(count: number): string {
    const one = count % 10 === 1 && count % 100 !== 11;
    return `Для${NBSP}${count}${NBSP}${one ? 'материала' : 'материалов'}`;
}

/** At most this many generation sessions may be active at once (`RESOURCE_LIMIT_EXCEEDED` / `ACTIVE_SESSIONS`). */
export const MAX_ACTIVE_SESSIONS = 3;

/** The sessions one press can open: no more than the account may have active at once, less the ones this request already opened. */
export function capSessions<T>(sessions: readonly (readonly T[])[], alreadyOpen = 0): readonly (readonly T[])[] {
    return sessions.slice(0, Math.max(0, MAX_ACTIVE_SESSIONS - alreadyOpen));
}

/**
 * What a big selection becomes, said before anything starts: how many sessions (20 materials each), in what order (only when the
 * priority is «Сначала без упражнений»), and, above what the active-session limit allows at once, which materials wait.
 */
export function splitNotice(targets: number, sessions: number, priority: ExercisePriority, deferred = 0): string | null {
    if (sessions <= 1 && deferred === 0) return null;
    const order = priority === 'UNCOVERED_FIRST' ? ': сначала с материалами без упражнений' : '';
    const lead = `Выбрано ${materialsCount(targets)}, а в одной мастерской — не больше ${MAX_EXERCISE_TARGETS}. Мнема откроет ${sessions}${NBSP}${plural(sessions, 'мастерскую', 'мастерские', 'мастерских')}${order}.`;
    if (deferred === 0) return lead;
    return `${lead} Одновременно могут идти не больше ${MAX_ACTIVE_SESSIONS} мастерских, поэтому ${materialsCount(deferred)} в этот раз не войдут: выберите их снова после разбора.`;
}

/** `aria-valuetext` and the visible output of the «Точно» slider: «5 упражнений на материал». */
export function perTargetText(count: number): string {
    return `${exercisesCount(count)} на${NBSP}материал`;
}

/** `aria-valuetext` and the visible output of the percent slider. */
export function percentText(percent: number): string {
    return `Не больше ${percent}${NBSP}% лимита`;
}

/** The limits a 422 `RESOURCE_LIMIT_EXCEEDED` carries (`limits`), with the contract defaults when the server does not name them. */
export interface ExerciseLimits {
    readonly targets: number;
    readonly perTarget: number;
    readonly perSession: number;
}

export function readLimits(members: Readonly<Record<string, number>> | null): ExerciseLimits {
    return {
        targets: members?.['maxExerciseTargets'] ?? MAX_EXERCISE_TARGETS,
        perTarget: members?.['maxExercisesPerTarget'] ?? MAX_EXERCISES_PER_TARGET,
        perSession: members?.['maxExercisesPerSession'] ?? MAX_EXERCISES_PER_SESSION
    };
}

/**
 * Why a request is over a limit, in words with the numbers (there is no silent clamp on the server). `inSession` is how many
 * materials the refused session had, to turn the per-session total into a per-material number the user can choose.
 */
export function describeExerciseLimit(limit: string | null, limits: ExerciseLimits, inSession: number): string {
    switch (limit) {
        case 'EXERCISES_PER_SESSION': {
            const each = Math.max(1, Math.floor(limits.perSession / Math.max(1, inSession)));
            return `За один раз можно создать не больше ${exercisesCount(limits.perSession)}. Для ${materialsCount(inSession)} это до ${each}${NBSP}на${NBSP}материал: уменьшите число или выберите «Авто».`;
        }
        case 'EXERCISES_PER_TARGET': return `На один материал — не больше ${exercisesCount(limits.perTarget)}. Уменьшите число.`;
        case 'EXERCISE_TARGETS': return `В одной мастерской — не больше ${materialsCount(limits.targets)}. Выберите меньше материалов.`;
        default: return 'Достигнут предел. Сократите запрос и повторите.';
    }
}

/**
 * What the user can do when the budget does not cover the request: the generic explanation advises media and effort, which
 * exercises do not have. The same text is built from the estimate (`blockingBuckets`) and from a `USAGE_LIMIT_REACHED` problem.
 */
export function describeExerciseUsage(limit: BlockingBucket | undefined): UsageExplanation {
    if (limit === undefined) {
        return { headline: 'Не хватит лимита ИИ на этот запрос.',
            options: ['Выберите меньше упражнений на материал или меньше материалов.'], plansLink: true };
    }
    if (!limit.offered) return { headline: 'На вашем тарифе это недоступно.', options: ['Посмотрите тарифы.'], plansLink: true };
    const date = formatDay(limit.renewsAt);
    const options = ['Выберите «Авто», меньше упражнений на материал или «Не больше X% лимита»: запрос обойдётся дешевле.'];
    if (date !== null && limit.fitsAfterRenewal) options.push(`Подождите до ${date}: лимит обновится, и запроса хватит.`);
    else if (date !== null) options.push(`После ${date} лимит обновится, но этому запросу всё равно не хватит: сократите запрос.`);
    return { headline: limit.window === 'DAY' ? 'На сегодня лимит ИИ исчерпан.' : 'Не хватит лимита ИИ на этот запрос.', options, plansLink: true };
}
