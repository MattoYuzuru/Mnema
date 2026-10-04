import { MECHANICS } from '../../content/exercise/exercise-content.models';
import { requireCommand, requireEntity, requireVersion } from '../authoring/authoring.models';
import { exercisesCount, materialsCount } from './exercise-builder';
import { describeShare } from './generation-view';
import { GenerationProblem } from './generation-problem';
import {
    ExercisePlanItem, ExercisesPlan, MaterialPlanItem, PLAN_EFFORTS, PlanEffort, RequestValidationError, SessionPlan
} from './generation.models';

/**
 * The pure rules of the plan the owner edits before launching it (AI-14, #295, contract decision 17): the draft rows, what an edit
 * costs (priced by the client from the rates the plan carries), the live totals, the request `approvePlan` takes, and the words for
 * its refusals. Nothing here touches the DOM or the network.
 */

/** A draft row keeps an id of its own, so a row that is removed or taken back never shifts the controls of the others. */
export interface DraftRow<T> { readonly id: number; readonly item: T; }

export type ExerciseDraft = readonly DraftRow<ExercisePlanItem>[];
export type MaterialDraft = readonly DraftRow<MaterialPlanItem>[];

/** The draft of a plan as the server proposed it: every item a row, in the order the work is done. */
export function draftOf<T>(items: readonly T[]): readonly DraftRow<T>[] {
    return items.map((item, id) => ({ id, item }));
}

/** The rows with one changed; the others are the very same objects. */
export function withItem<T>(rows: readonly DraftRow<T>[], id: number, change: Partial<T>): readonly DraftRow<T>[] {
    return rows.map(row => row.id === id ? { id, item: { ...row.item, ...change } } : row);
}

export function withoutRow<T>(rows: readonly DraftRow<T>[], id: number): readonly DraftRow<T>[] {
    return rows.filter(row => row.id !== id);
}

/** Largest id plus one: a row added later never reuses the id of a removed one. */
function nextId(rows: readonly DraftRow<unknown>[], floor: number): number {
    return rows.reduce((highest, row) => Math.max(highest, row.id + 1), floor);
}

/**
 * Takes a material back into an exercise plan. It returns with what the owner had given it before it was removed (`previous`); a
 * material the model never planned comes with the first two allowed mechanics and three exercises (the number the Stub and the
 * builder default to), and an empty reason: the reason was the model's, and there is none for a row the owner added.
 */
export function takeBack(plan: ExercisesPlan, rows: ExerciseDraft, memberKey: string, previous: ExercisePlanItem | null, ids: number): ExerciseDraft {
    if (rows.some(row => row.item.memberKey === memberKey)) return rows;
    const target = plan.targets.find(candidate => candidate.memberKey === memberKey);
    if (target === undefined) return rows;
    const item: ExercisePlanItem = previous ?? { memberKey, title: target.title, mechanics: plan.allowedMechanics.slice(0, 2),
        count: Math.min(3, plan.limits.maxExercisesPerTarget), why: '' };
    return [...rows, { id: nextId(rows, ids), item }];
}

/** Exercises the plan creates: the sum of the counts. */
export function exerciseTotal(rows: ExerciseDraft): number {
    return rows.reduce((sum, row) => sum + row.item.count, 0);
}

/** What `count` exercises cost: the server's price, `ceil(exercisesPerFive x n / 5)` (the plan carries the rate). */
export function exerciseCredits(plan: ExercisesPlan, count: number): number {
    return count <= 0 ? 0 : Math.ceil(plan.rates.exercisesPerFive * count / 5);
}

/** What the materials cost: the sum of the price of each at its own effort. */
export function materialCredits(rows: MaterialDraft): number {
    return rows.reduce((sum, row) => sum + row.item.creditsByEffort[row.item.effort], 0);
}

export interface PlanTotals {
    /** Rows (items) of the plan. */
    readonly rows: number;
    /** What the plan creates: exercises or materials. */
    readonly artifacts: number;
    /** What creating them costs, apart from the plan itself, which is already paid. */
    readonly credits: number;
}

export function exercisesTotals(plan: ExercisesPlan, rows: ExerciseDraft): PlanTotals {
    const artifacts = exerciseTotal(rows);
    return { rows: rows.length, artifacts, credits: exerciseCredits(plan, artifacts) };
}

export function materialsTotals(rows: MaterialDraft): PlanTotals {
    return { rows: rows.length, artifacts: rows.length, credits: materialCredits(rows) };
}

/** «Всего 14 упражнений · ≈ 5 % лимита»: the live total under the table. */
export function describeTotals(kind: SessionPlan['kind'], totals: PlanTotals, bar: number): string {
    const count = kind === 'EXERCISES' ? exercisesCount(totals.artifacts) : materialsCount(totals.artifacts);
    const share = describeShare(totals.credits, bar);
    return share === null ? `Всего ${count}` : `Всего ${count} · ${share}`;
}

/** «Составление плана: ≈ 1 % лимита — уже списано»: the plan's own cost, which no edit changes. */
export function describePlanPaid(plan: SessionPlan): string {
    const share = describeShare(plan.cost.planCredits, plan.cost.barCredits);
    return share === null ? 'Составление плана уже списано.' : `Составление плана: ${share} — уже списано.`;
}

/**
 * Why the plan as it stands would be refused, said before the owner presses «Запустить по плану»; `null` when it is within the limits.
 * The server decides (and answers `422` for a plan over them: nothing is clamped), this is the same sentence one step earlier.
 */
export function describePlanOverLimit(plan: SessionPlan, rows: readonly DraftRow<unknown>[]): string | null {
    if (plan.kind === 'EXERCISES') {
        const total = exerciseTotal(rows as ExerciseDraft);
        const { maxExercisesPerSession: session, maxExercisesPerTarget: target } = plan.limits;
        if ((rows as ExerciseDraft).some(row => row.item.count > target)) return `На один материал — не больше ${exercisesCount(target)}.`;
        return total > session ? `За один раз — не больше ${exercisesCount(session)}, сейчас ${total}: уберите строки или уменьшите числа.` : null;
    }
    const max = plan.limits.maxArtifactsPerSession;
    return rows.length > max ? `В одной мастерской — не больше ${materialsCount(max)}, сейчас ${rows.length}: уберите строки.` : null;
}

/** A row title as it goes on the wire: trimmed, 1 to 160 code points. */
export const MAX_PLAN_TITLE = 160;

function codePoints(value: string): number {
    let count = 0;
    for (const _ of value) count += 1;
    return count;
}

export function isValidPlanTitle(title: string): boolean {
    const length = codePoints(title.trim());
    return length >= 1 && length <= MAX_PLAN_TITLE;
}

/**
 * The exact body of `approvePlan` (unknown members are `INVALID_REQUEST`; `why`, `title` of an exercise row and `creditsByEffort` are
 * never sent). A plan the client cannot build is refused here and never sent. Counts and totals are **not** checked against the limits:
 * the server answers `422` for a plan over them, and the owner is told in words.
 */
export function serializePlanApproval(plan: SessionPlan, rows: readonly DraftRow<ExercisePlanItem | MaterialPlanItem>[],
                                      expectedSessionVersion: string, commandId: string): Record<string, unknown> {
    if (rows.length === 0) throw new RequestValidationError('A plan needs at least one row.');
    let items: Record<string, unknown>[];
    if (plan.kind === 'EXERCISES') {
        const exercises = rows.map(row => row.item as ExercisePlanItem);
        const keys = exercises.map(item => requireEntity(item.memberKey));
        if (new Set(keys).size !== keys.length || keys.some(key => !plan.targets.some(target => target.memberKey === key))) {
            throw new RequestValidationError('A plan row is not one of the materials.');
        }
        items = exercises.map(item => {
            const mechanics = MECHANICS.filter(mechanic => item.mechanics.includes(mechanic));
            if (mechanics.length === 0 || mechanics.length !== item.mechanics.length || mechanics.some(mechanic => !plan.allowedMechanics.includes(mechanic))) {
                throw new RequestValidationError('A plan row needs allowed mechanics.');
            }
            if (!Number.isInteger(item.count) || item.count < 1) throw new RequestValidationError('A plan row needs at least one exercise.');
            return { memberKey: item.memberKey, mechanics, count: item.count };
        });
    } else {
        items = rows.map(row => {
            const item = row.item as MaterialPlanItem;
            if (!isValidPlanTitle(item.title) || !(PLAN_EFFORTS as readonly string[]).includes(item.effort)) {
                throw new RequestValidationError('A plan row needs a title and an effort.');
            }
            return { source: item.source === null ? null : requireEntity(item.source), title: item.title.trim(), effort: item.effort };
        });
    }
    return { commandId: requireCommand(commandId), expectedSessionVersion: requireVersion(expectedSessionVersion), plan: { items } };
}

/** The words for the owner's effort choice, in the Materials composer's vocabulary. */
export const PLAN_EFFORT_LABELS: Readonly<Record<PlanEffort, string>> = { SHORT: 'Кратко', MEDIUM: 'Средне', DETAILED: 'Подробно' };

/** The capability a launch needs and the server does not offer, in a word the owner knows. */
const CAPABILITY_NAMES: Readonly<Record<string, string>> = { webSearch: 'веб-поиск', imageSearch: 'поиск изображений', textToSpeech: 'озвучивание', aiGeneration: 'ИИ' };

/** What the owner is told when the hold made for a waiting plan has lapsed: the launch reserves the limit again. */
export const HOLD_LAPSED_NOTE = 'Лимит, отложенный под этот план, уже освободился. При запуске он будет отложен заново; если его не хватит, запуск не начнётся и ничего не спишется. Можно и отменить план.';

const REFUSAL_TAIL = 'Исправьте план и запустите его ещё раз.';

/** What the plan editor says about a refused or failed launch. A refusal that changed nothing says so; the plan the owner edited is kept. */
export function describePlanProblem(problem: GenerationProblem, plan: SessionPlan): string {
    if (problem.uncertain) {
        return 'Не удалось подтвердить запуск: связь прервалась или сервер не ответил. Нажмите «Запустить по плану» ещё раз — будет отправлена та же команда, дубликатов не появится.';
    }
    switch (problem.status) {
        case 400: return `План не принят: проверьте строки (у каждой должны быть типы упражнений или тема). ${REFUSAL_TAIL}`;
        case 404: return 'Мастерская больше недоступна.';
        case 412: return 'План изменился, пока вы его правили. Мы обновили данные: проверьте план и запустите его ещё раз.';
        case 409:
            switch (problem.code) {
                case 'USAGE_LIMIT_REACHED':
                    return `Не хватает лимита ИИ на этот план: он дороже, чем было отложено. Ничего не изменилось и не списано. Уберите строки или уменьшите числа — или дождитесь обновления лимита (подробности — в профиле, в блоке «ИИ-бюджет»).`;
                case 'GENERATION_STATE_CONFLICT':
                    return 'Этот план уже запущен или остановлен. Мы обновили мастерскую.';
                case 'CAPABILITY_UNAVAILABLE': {
                    const what = problem.capability === null ? null : CAPABILITY_NAMES[problem.capability] ?? null;
                    return `${what === null ? 'Для этого плана нужна возможность, которая сейчас недоступна' : `Для этого плана нужен ${what}, а он сейчас недоступен`}. Ничего не создано и не списано. Верните прежнюю подробность или запустите план позже.`;
                }
                case 'IDEMPOTENCY_CONFLICT': return 'Эта команда уже использована с другим планом. Нажмите «Запустить по плану» ещё раз.';
                default: return 'Действие сейчас невозможно. Мы обновили данные.';
            }
        case 422: return describePlanLimit(problem, plan);
        default: return 'Не удалось запустить план. Попробуйте ещё раз.';
    }
}

function describePlanLimit(problem: GenerationProblem, plan: SessionPlan): string {
    const limits = problem.limits;
    switch (problem.limit) {
        case 'EXERCISES_PER_TARGET': {
            const max = limits?.['maxExercisesPerTarget'] ?? (plan.kind === 'EXERCISES' ? plan.limits.maxExercisesPerTarget : 10);
            return `На один материал — не больше ${exercisesCount(max)}. ${REFUSAL_TAIL}`;
        }
        case 'EXERCISES_PER_SESSION': {
            const max = limits?.['maxExercisesPerSession'] ?? (plan.kind === 'EXERCISES' ? plan.limits.maxExercisesPerSession : 60);
            return `За один раз можно создать не больше ${exercisesCount(max)}. Уменьшите числа или уберите строки. Ничего не создано и не списано.`;
        }
        case 'ARTIFACTS_PER_SESSION': {
            const max = limits?.['maxArtifactsPerSession'] ?? (plan.kind === 'MATERIALS' ? plan.limits.maxArtifactsPerSession : 20);
            return `В одной мастерской — не больше ${materialsCount(max)}. Уберите строки. Ничего не создано и не списано.`;
        }
        default: return `Достигнут предел. ${REFUSAL_TAIL}`;
    }
}
