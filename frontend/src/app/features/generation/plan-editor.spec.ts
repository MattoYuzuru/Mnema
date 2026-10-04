import { HttpErrorResponse } from '@angular/common/http';

import { readProblem } from './generation-problem';
import { clone, examples, httpContract, ids, problemResponse } from './generation-test-data';
import { ExercisesPlan, MaterialsPlan, RequestValidationError, SessionPlan, parsePlan } from './generation.models';
import {
    describePlanOverLimit, describePlanPaid, describePlanProblem, describeTotals, draftOf, exerciseCredits, exercisesTotals, isValidPlanTitle,
    materialCredits, materialsTotals, serializePlanApproval, takeBack, withItem, withoutRow
} from './plan-editor';

const NBSP = '\u00a0';
const first = '44444444-4444-4444-8444-444444444444';
const second = '44444444-4444-4444-8444-444444444445';
const noteA = '20700000-0000-4000-8000-000000000001';

const exercisesPlan = (): ExercisesPlan => parsePlan(clone(examples['sessionDetailPlanReady'])['plan']) as ExercisesPlan;
/** A MATERIALS plan in the shape `schemas.plan` describes (the contract has no example of one). */
const materialsPlan = (overrides: Record<string, unknown> = {}): MaterialsPlan => parsePlan({
    kind: 'MATERIALS', approved: false,
    items: [{ source: noteA, title: 'Seq Scan: когда он быстрее', effort: 'SHORT', why: 'Короткая заметка.', creditsByEffort: { SHORT: 4, MEDIUM: 10, DETAILED: 22 } },
        { source: null, title: 'Общая картина', effort: 'DETAILED', why: '', creditsByEffort: { SHORT: 4, MEDIUM: 10, DETAILED: 22 } }],
    sources: [{ noteId: noteA, label: 'Seq Scan читает всю таблицу' }], totals: { items: 2, artifacts: 2 },
    cost: { planCredits: 20, batchCredits: 26, holdCredits: 30, barCredits: 360, holdActive: true }, rates: { note: 'creditsByEffort of each item' },
    limits: { maxArtifactsPerSession: 20 }, notes: [], ...overrides }) as MaterialsPlan;

describe('the plan the owner edits (#295)', () => {
    describe('what the contract says', () => {
        it('reads the plan of the contract examples, the owner\'s own identifiers and the server\'s notes', () => {
            const plan = exercisesPlan();
            expect(plan).toMatchObject({ kind: 'EXERCISES', approved: false, totals: { items: 2, artifacts: 6 },
                cost: { planCredits: 20, batchCredits: 10, holdCredits: 10, barCredits: 360, holdActive: true }, rates: { exercisesPerFive: 8 },
                limits: { maxExercisesPerTarget: 10, maxExercisesPerSession: 60 }, notes: [] });
            expect(plan.items.map(item => [item.memberKey, item.mechanics, item.count])).toEqual([[second, ['CLOZE', 'CHOICE'], 3], [first, ['CHOICE', 'ORDER'], 3]]);
            expect(plan.allowedMechanics).toHaveLength(7);
            expect(plan.targets.map(target => target.exercises)).toEqual([2, 0]);
            expect((parsePlan(clone(examples['sessionDetailPlanApproved'])['plan']) as ExercisesPlan).approved).toBe(true);
            expect(materialsPlan().items[1]!.source).toBeNull();
        });

        it('refuses a plan with an unknown member, a mechanic twice, a mechanic that does not exist, a count that is not a number or another kind', () => {
            const make = (change: (plan: any) => void) => { const plan = clone(examples['sessionDetailPlanReady'])['plan']; change(plan); return () => parsePlan(plan); };
            expect(make(plan => { plan.extra = 1; })).toThrow();
            expect(make(plan => { plan.items[0].mechanics = ['CLOZE', 'CLOZE']; })).toThrow();
            expect(make(plan => { plan.items[0].mechanics = []; })).toThrow();
            expect(make(plan => { plan.items[0].mechanics = ['RIDDLE']; })).toThrow();
            expect(make(plan => { plan.items[0].count = '3'; })).toThrow();
            expect(make(plan => { plan.items[0].memberKey = 'm1'; })).toThrow();
            expect(make(plan => { plan.cost.barCredits = -1; })).toThrow();
            expect(make(plan => { plan.kind = 'REVISE_ITEM'; })).toThrow();
            expect(make(plan => { plan.notes = [{ code: 'X' }]; })).toThrow();
        });

        it('serializes exactly the request of the contract example, in the order of the rows, without why or title', () => {
            const plan = exercisesPlan();
            const example = httpContract['examples']['planApprovalRequest'];
            const rows = draftOf([plan.items[0]!, plan.items[1]!]).map(row => ({ ...row, item: { ...row.item, mechanics: row.item.memberKey === second ? ['CLOZE' as const] : row.item.mechanics,
                count: row.item.memberKey === second ? 4 : 2 } }));
            expect(serializePlanApproval(plan, rows, '3', example.commandId)).toEqual(example);
            const materials = httpContract['examples']['planApprovalRequestMaterials'];
            const forMaterials = materialsPlan({ sources: [{ noteId: '20700000-0000-4000-8000-000000000001', label: 'x' }] });
            const kept = draftOf(forMaterials.items).map((row, index) => ({ ...row, item: { ...row.item, title: materials.plan.items[index].title, effort: materials.plan.items[index].effort } }));
            expect(serializePlanApproval(forMaterials, kept, '3', materials.commandId)).toEqual(materials);
        });

        it('puts the mechanics of a row in the registry order and refuses what the plan cannot say', () => {
            const plan = exercisesPlan();
            const rows = draftOf([{ ...plan.items[0]!, mechanics: ['ORDER' as const, 'CLOZE' as const] }]);
            expect((serializePlanApproval(plan, rows, '3', ids.command)['plan'] as any).items[0].mechanics).toEqual(['CLOZE', 'ORDER']);
            const refused = (items: readonly unknown[]) => () => serializePlanApproval(plan, draftOf(items as any), '3', ids.command);
            expect(() => serializePlanApproval(plan, [], '3', ids.command)).toThrow(RequestValidationError);
            expect(refused([{ ...plan.items[0]!, mechanics: [] }])).toThrow(RequestValidationError);
            expect(refused([{ ...plan.items[0]!, count: 0 }])).toThrow(RequestValidationError);
            expect(refused([{ ...plan.items[0]!, count: 1.5 }])).toThrow(RequestValidationError);
            expect(refused([{ ...plan.items[0]!, memberKey: '99999999-9999-4999-8999-999999999999' }])).toThrow(RequestValidationError);
            expect(refused([plan.items[0]!, plan.items[0]!])).toThrow(RequestValidationError);
            expect(() => serializePlanApproval(plan, draftOf([plan.items[0]!]), 'x', ids.command)).toThrow();
        });

        it('sends a count above the limits as it is: the server answers 422, nothing is clamped', () => {
            const plan = exercisesPlan();
            const body = serializePlanApproval(plan, draftOf([{ ...plan.items[0]!, count: 99 }]), '3', ids.command);
            expect((body['plan'] as any).items[0].count).toBe(99);
        });

        it('trims a material title and refuses an empty one or one over 160 characters', () => {
            const plan = materialsPlan();
            const rows = draftOf(plan.items);
            expect((serializePlanApproval(plan, withItem(rows, 0, { title: '  Тема  ' }), '3', ids.command)['plan'] as any).items[0].title).toBe('Тема');
            expect(() => serializePlanApproval(plan, withItem(rows, 0, { title: '   ' }), '3', ids.command)).toThrow(RequestValidationError);
            expect(() => serializePlanApproval(plan, withItem(rows, 0, { title: 'я'.repeat(161) }), '3', ids.command)).toThrow(RequestValidationError);
            expect(isValidPlanTitle('я'.repeat(160))).toBe(true);
            expect(isValidPlanTitle('😀'.repeat(160))).toBe(true);
            expect(isValidPlanTitle('')).toBe(false);
        });
    });

    describe('what an edit costs and the totals', () => {
        it('prices exercises as the server does, ceil(rate x n / 5), and materials by the effort each row has', () => {
            const plan = exercisesPlan();
            expect([0, 1, 5, 6, 14].map(count => exerciseCredits(plan, count))).toEqual([0, 2, 8, 10, 23]);
            const materials = materialsPlan();
            expect(materialCredits(draftOf(materials.items))).toBe(4 + 22);
            expect(materialCredits(withItem(draftOf(materials.items), 0, { effort: 'MEDIUM' }))).toBe(10 + 22);
        });

        it('says the live total in words: «Всего 14 упражнений · ≈ 6,4 % лимита»', () => {
            const plan = exercisesPlan();
            const rows = draftOf([{ ...plan.items[0]!, count: 10 }, { ...plan.items[1]!, count: 4 }]);
            const totals = exercisesTotals(plan, rows);
            expect(totals).toEqual({ rows: 2, artifacts: 14, credits: 23 });
            expect(describeTotals('EXERCISES', totals, plan.cost.barCredits)).toBe(`Всего 14${NBSP}упражнений · ≈${NBSP}6,4${NBSP}% лимита`);
            expect(describeTotals('EXERCISES', { rows: 1, artifacts: 1, credits: 2 }, 360)).toBe(`Всего 1${NBSP}упражнение · ≈${NBSP}0,6${NBSP}% лимита`);
            expect(describeTotals('MATERIALS', materialsTotals(draftOf(materialsPlan().items)), 360)).toBe(`Всего 2${NBSP}материала · ≈${NBSP}7,2${NBSP}% лимита`);
            expect(describeTotals('MATERIALS', { rows: 3, artifacts: 3, credits: 5 }, 0)).toBe(`Всего 3${NBSP}материала`);
        });

        it('shows the cost of the plan itself apart as already paid', () => {
            expect(describePlanPaid(exercisesPlan())).toBe(`Составление плана: ≈${NBSP}5,6${NBSP}% лимита — уже списано.`);
            expect(describePlanPaid(materialsPlan({ cost: { planCredits: 20, batchCredits: 1, holdCredits: 1, barCredits: 0, holdActive: true } }))).toBe('Составление плана уже списано.');
        });

        it('explains a plan over the limits before the server does', () => {
            const plan = exercisesPlan();
            const rows = draftOf([{ ...plan.items[0]!, count: 10 }, { ...plan.items[1]!, count: 10 }]);
            expect(describePlanOverLimit(plan, rows)).toBeNull();
            const many = draftOf(Array.from({ length: 7 }, (_, index) => ({ ...plan.items[0]!, memberKey: `44444444-4444-4444-8444-44444444444${index}`, count: 10 })));
            expect(describePlanOverLimit(plan, many)).toBe(`За один раз — не больше 60${NBSP}упражнений, сейчас 70: уберите строки или уменьшите числа.`);
            expect(describePlanOverLimit(plan, withItem(rows, 0, { count: 11 }))).toBe(`На один материал — не больше 10${NBSP}упражнений.`);
            const materials = materialsPlan();
            expect(describePlanOverLimit(materials, draftOf(materials.items))).toBeNull();
            const rowsOfMaterials = draftOf(Array.from({ length: 21 }, () => materials.items[0]!));
            expect(describePlanOverLimit(materials, rowsOfMaterials)).toBe(`В одной мастерской — не больше 20${NBSP}материалов, сейчас 21: уберите строки.`);
        });
    });

    describe('the draft', () => {
        it('removes a row by its id and never shifts the others', () => {
            const rows = draftOf(['a', 'b', 'c']);
            expect(withoutRow(rows, 1).map(row => [row.id, row.item])).toEqual([[0, 'a'], [2, 'c']]);
            expect(withItem(draftOf([{ count: 1 }, { count: 2 }]), 1, { count: 5 }).map(row => row.item.count)).toEqual([1, 5]);
        });

        it('takes a removed material back as it was, and a never planned one with two allowed mechanics and three exercises', () => {
            const plan = exercisesPlan();
            const rows = draftOf([plan.items[0]!]);
            const back = takeBack(plan, rows, first, { ...plan.items[1]!, count: 7 }, 10);
            expect(back).toHaveLength(2);
            expect(back[1]).toMatchObject({ id: 10, item: { memberKey: first, count: 7 } });
            const fresh = takeBack({ ...plan, items: [] }, [], first, null, 0);
            expect(fresh[0]!.item).toMatchObject({ memberKey: first, mechanics: ['SELF_CHECK', 'FREE_RESPONSE'], count: 3, why: '' });
            expect(takeBack(plan, rows, second, null, 5)).toBe(rows);
            expect(takeBack(plan, rows, '99999999-9999-4999-8999-999999999999', null, 5)).toBe(rows);
        });
    });

    describe('the words for a refused launch', () => {
        const problem = (status: number, body: Record<string, unknown> = {}) => readProblem(problemResponse(status, body));
        const plan: SessionPlan = exercisesPlan();

        it('explains a plan above the limits with the numbers the server named, and says nothing was created or charged', () => {
            expect(describePlanProblem(problem(422, { code: 'RESOURCE_LIMIT_EXCEEDED', limit: 'EXERCISES_PER_SESSION', limits: { maxExercisesPerSession: 60 } }), plan))
                .toBe(`За один раз можно создать не больше 60${NBSP}упражнений. Уменьшите числа или уберите строки. Ничего не создано и не списано.`);
            expect(describePlanProblem(problem(422, { code: 'RESOURCE_LIMIT_EXCEEDED', limit: 'EXERCISES_PER_TARGET' }), plan)).toContain(`не больше 10${NBSP}упражнений`);
            expect(describePlanProblem(problem(422, { code: 'RESOURCE_LIMIT_EXCEEDED', limit: 'ARTIFACTS_PER_SESSION', limits: { maxArtifactsPerSession: 20 } }), materialsPlan()))
                .toContain(`не больше 20${NBSP}материалов`);
            expect(describePlanProblem(problem(422, { code: 'RESOURCE_LIMIT_EXCEEDED', limit: 'SOMETHING' }), plan)).toContain('Достигнут предел');
        });

        it('explains a plan the balance cannot pay, a stale plan, a plan that is no longer waiting and a plan that does not read', () => {
            expect(describePlanProblem(problem(409, { code: 'USAGE_LIMIT_REACHED' }), plan)).toContain('Не хватает лимита ИИ на этот план');
            expect(describePlanProblem(problem(409, { code: 'USAGE_LIMIT_REACHED' }), plan)).toContain('Ничего не изменилось и не списано');
            expect(describePlanProblem(problem(412, { code: 'VERSION_CONFLICT' }), plan)).toContain('План изменился, пока вы его правили');
            expect(describePlanProblem(problem(409, { code: 'GENERATION_STATE_CONFLICT', reason: 'ILLEGAL_STATE' }), plan)).toContain('уже запущен или остановлен');
            expect(describePlanProblem(problem(409, { code: 'IDEMPOTENCY_CONFLICT' }), plan)).toContain('другим планом');
            expect(describePlanProblem(problem(409, { code: 'CAPABILITY_UNAVAILABLE', capability: 'webSearch' }), plan))
                .toBe('Для этого плана нужен веб-поиск, а он сейчас недоступен. Ничего не создано и не списано. Верните прежнюю подробность или запустите план позже.');
            expect(describePlanProblem(problem(409, { code: 'CAPABILITY_UNAVAILABLE' }), plan)).toContain('нужна возможность, которая сейчас недоступна');
            expect(describePlanProblem(problem(400, { code: 'INVALID_REQUEST' }), plan)).toContain('План не принят');
            expect(describePlanProblem(problem(404), plan)).toContain('больше недоступна');
            expect(describePlanProblem(problem(418), plan)).toContain('Не удалось запустить');
        });

        it('says an unknown outcome is retried with the same command, so nothing is launched twice', () => {
            expect(describePlanProblem(readProblem(new HttpErrorResponse({ status: 0 })), plan)).toContain('будет отправлена та же команда');
        });
    });
});
