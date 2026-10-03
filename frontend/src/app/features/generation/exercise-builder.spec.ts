import {
    BuilderTarget, DEFAULT_BUILDER_VALUE, buildExercisesSpec, describeExerciseLimit, describeExerciseUsage, exercisesCount, materialsCount,
    capSessions, mechanicName, percentText, perTargetText, readLimits, splitNotice, splitTargets, targetsSummary, toggleMechanic
} from './exercise-builder';
import { materialIds, materialRevisions } from './exercise-test-data';
import { BlockingBucket, serializeExercisesSpec, serializeSpec } from './generation.models';
import { AuthoringProtocolError } from '../authoring/authoring.models';
import { NBSP } from './generation-view';

const target = (index: number, exerciseCount: number | null = null): BuilderTarget => ({
    memberKey: `44444444-4444-4444-8444-${String(index).padStart(12, '0')}`, itemRevisionId: `55555555-5555-4555-8555-${String(index).padStart(12, '0')}`,
    title: `Материал ${index}`, exerciseCount });

describe('exercise builder rules', () => {
    describe('the request', () => {
        const pins = [{ memberKey: materialIds.first, itemRevisionId: materialRevisions.first }];

        it('sends AUTO with the default choices and nothing the page only shows (titles, counts)', () => {
            const spec = buildExercisesSpec([target(1, 4)], DEFAULT_BUILDER_VALUE, 'ru');
            expect(serializeExercisesSpec(spec)).toEqual({
                kind: 'EXERCISES', outputLanguage: 'ru',
                targets: [{ memberKey: target(1).memberKey, itemRevisionId: target(1).itemRevisionId }],
                settings: { mechanics: 'AUTO', priority: 'UNCOVERED_FIRST', quantity: { mode: 'AUTO' }, planFirst: false, budgetPercent: null }
            });
        });

        it('says the three quantity modes exactly, mutually exclusive by mode', () => {
            const quantity = (change: Partial<typeof DEFAULT_BUILDER_VALUE>) =>
                (serializeExercisesSpec(buildExercisesSpec(pins, { ...DEFAULT_BUILDER_VALUE, ...change }))['settings'] as { quantity: unknown }).quantity;
            expect(quantity({ quantityMode: 'AUTO', perTarget: 7, percent: 40 })).toEqual({ mode: 'AUTO' });
            expect(quantity({ quantityMode: 'EXACT', perTarget: 7, percent: 40 })).toEqual({ mode: 'EXACT', perTarget: 7 });
            expect(quantity({ quantityMode: 'BUDGET_PERCENT', perTarget: 7, percent: 40 })).toEqual({ mode: 'BUDGET_PERCENT', percent: 40 });
        });

        it('sends a chosen set of mechanics in the editor order, and the priority as chosen', () => {
            const spec = buildExercisesSpec(pins, { ...DEFAULT_BUILDER_VALUE, mechanics: ['CHOICE', 'CLOZE'], priority: 'BALANCED' });
            expect((serializeSpec(spec)['settings'] as Record<string, unknown>)['mechanics']).toEqual(['CLOZE', 'CHOICE']);
            expect((serializeSpec(spec)['settings'] as Record<string, unknown>)['priority']).toBe('BALANCED');
        });

        it('refuses what the server would refuse before anything is sent', () => {
            const spec = buildExercisesSpec(pins, DEFAULT_BUILDER_VALUE);
            expect(() => serializeExercisesSpec({ ...spec, targets: [] })).toThrow(AuthoringProtocolError);
            expect(() => serializeExercisesSpec({ ...spec, targets: Array.from({ length: 21 }, (_, index) => target(index + 1)) })).toThrow(AuthoringProtocolError);
            const exact = (perTarget: number) => buildExercisesSpec(pins, { ...DEFAULT_BUILDER_VALUE, quantityMode: 'EXACT', perTarget });
            expect(() => serializeExercisesSpec(exact(0))).toThrow(AuthoringProtocolError);
            expect(() => serializeExercisesSpec(exact(11))).toThrow(AuthoringProtocolError);
            expect(() => serializeExercisesSpec(exact(2.5))).toThrow(AuthoringProtocolError);
            const percent = (value: number) => buildExercisesSpec(pins, { ...DEFAULT_BUILDER_VALUE, quantityMode: 'BUDGET_PERCENT', percent: value });
            expect(() => serializeExercisesSpec(percent(0))).toThrow(AuthoringProtocolError);
            expect(() => serializeExercisesSpec(percent(101))).toThrow(AuthoringProtocolError);
            expect(() => serializeExercisesSpec({ ...spec, settings: { ...spec.settings, mechanics: [] } })).toThrow(AuthoringProtocolError);
            expect(() => serializeExercisesSpec({ ...spec, settings: { ...spec.settings, mechanics: ['CLOZE', 'CLOZE'] } })).toThrow(AuthoringProtocolError);
            expect(() => serializeExercisesSpec({ ...spec, settings: { ...spec.settings, priority: 'x' as never } })).toThrow(AuthoringProtocolError);
        });
    });

    describe('mechanics', () => {
        it('names them as the editor does', () => {
            expect(mechanicName('CLOZE')).toBe('Заполнить пропуски');
            expect(mechanicName('SELF_CHECK')).toBe('Вспомнить и сверить');
        });

        it('checking a mechanic adds it in the editor order, unchecking the last one returns to the empty (Авто) set', () => {
            let current = toggleMechanic([], 'CHOICE', true);
            current = toggleMechanic(current, 'CLOZE', true);
            expect(current).toEqual(['CLOZE', 'CHOICE']);
            expect(toggleMechanic(current, 'CLOZE', false)).toEqual(['CHOICE']);
            expect(toggleMechanic(['CHOICE'], 'CHOICE', false)).toEqual([]);
        });
    });

    describe('splitting a large selection', () => {
        const many = (count: number, exerciseCount: (index: number) => number | null = () => null) =>
            Array.from({ length: count }, (_, index) => target(index + 1, exerciseCount(index)));

        it('is one session up to 20 materials', () => {
            expect(splitTargets(many(20), 'UNCOVERED_FIRST')).toHaveLength(1);
            expect(splitTargets(many(1), 'BALANCED')).toHaveLength(1);
        });

        it('makes ceil(n/20) sessions and puts the materials without exercises first (stable among equals)', () => {
            const targets = many(45, index => index % 2 === 0 ? 3 : 0);
            const sessions = splitTargets(targets, 'UNCOVERED_FIRST');
            expect(sessions.map(session => session.length)).toEqual([20, 20, 5]);
            const flat = sessions.flat();
            expect(flat.slice(0, 22).every(entry => entry.exerciseCount === 0)).toBe(true);
            expect(flat.slice(0, 3).map(entry => entry.title)).toEqual(['Материал 2', 'Материал 4', 'Материал 6']);
            expect(new Set(flat.map(entry => entry.memberKey)).size).toBe(45);
        });

        it('keeps the user order with «поровну»', () => {
            const targets = many(25, index => 25 - index);
            expect(splitTargets(targets, 'BALANCED').flat().map(entry => entry.title)).toEqual(targets.map(entry => entry.title));
        });

        it('treats an unknown count like none and is empty for no materials', () => {
            expect(splitTargets(many(3, index => index === 0 ? 2 : null), 'UNCOVERED_FIRST')[0]!.map(entry => entry.title))
                .toEqual(['Материал 2', 'Материал 3', 'Материал 1']);
            expect(splitTargets([], 'UNCOVERED_FIRST')).toEqual([]);
        });

        it('says so before anything starts, and says nothing for one session', () => {
            expect(splitNotice(45, 3, 'UNCOVERED_FIRST')).toContain('3');
            expect(splitNotice(45, 3, 'UNCOVERED_FIRST')).toContain('в одной мастерской — не больше 20');
            expect(splitNotice(5, 1, 'BALANCED')).toBeNull();
        });

        it('names the order only for «Сначала без упражнений»', () => {
            expect(splitNotice(45, 3, 'UNCOVERED_FIRST')).toContain('сначала с материалами без упражнений');
            expect(splitNotice(45, 3, 'BALANCED')).not.toContain('без упражнений');
        });

        it('opens at most three sessions at once and says which materials wait', () => {
            const sessions = [[1], [2], [3], [4], [5]];
            expect(capSessions(sessions)).toEqual([[1], [2], [3]]);
            expect(capSessions(sessions, 1)).toEqual([[1], [2]]);
            expect(capSessions(sessions, 5)).toEqual([]);
            const note = splitNotice(100, 3, 'BALANCED', 40)!;
            expect(note).toContain('не больше 3 мастерских');
            expect(note).toContain('40');
            expect(splitNotice(20, 1, 'BALANCED', 0)).toBeNull();
        });
    });

    describe('words', () => {
        it('declines the counts in Russian', () => {
            expect(materialsCount(1)).toBe(`1${NBSP}материал`);
            expect(materialsCount(3)).toBe(`3${NBSP}материала`);
            expect(materialsCount(7)).toBe(`7${NBSP}материалов`);
            expect(materialsCount(12)).toBe(`12${NBSP}материалов`);
            expect(exercisesCount(21)).toBe(`21${NBSP}упражнение`);
            expect(targetsSummary(7)).toBe(`Для${NBSP}7${NBSP}материалов`);
            expect(targetsSummary(1)).toBe(`Для${NBSP}1${NBSP}материала`);
            expect(targetsSummary(2)).toBe(`Для${NBSP}2${NBSP}материалов`);
            expect(targetsSummary(11)).toBe(`Для${NBSP}11${NBSP}материалов`);
            expect(targetsSummary(21)).toBe(`Для${NBSP}21${NBSP}материала`);
            expect(perTargetText(5)).toBe(`5${NBSP}упражнений на${NBSP}материал`);
            expect(percentText(30)).toBe(`Не больше 30${NBSP}% лимита`);
        });

        it('explains each limit with the numbers the server reported', () => {
            const limits = readLimits({ maxExerciseTargets: 20, maxExercisesPerTarget: 10, maxExercisesPerSession: 60 });
            expect(describeExerciseLimit('EXERCISES_PER_SESSION', limits, 20)).toContain('не больше 60');
            expect(describeExerciseLimit('EXERCISES_PER_SESSION', limits, 20)).toContain('до 3');
            expect(describeExerciseLimit('EXERCISES_PER_SESSION', limits, 7)).toContain('до 8');
            expect(describeExerciseLimit('EXERCISES_PER_TARGET', limits, 1)).toContain('не больше 10');
            expect(describeExerciseLimit('EXERCISE_TARGETS', limits, 1)).toContain('не больше 20');
            expect(describeExerciseLimit('SOURCES', limits, 1)).toContain('Достигнут предел');
        });

        it('falls back to the contract limits when the server names none, and reads the ones it names', () => {
            expect(readLimits(null)).toEqual({ targets: 20, perTarget: 10, perSession: 60 });
            expect(readLimits({ maxExercisesPerSession: 30 })).toEqual({ targets: 20, perTarget: 10, perSession: 30 });
        });

        const bucket = (overrides: Partial<BlockingBucket> = {}): BlockingBucket => ({ bucket: 'ai', window: 'MONTH', unit: 'CREDITS', limit: 100, used: 90, required: 20,
            offered: true, renewsAt: '2026-11-01T00:00:00Z', fitsAfterRenewal: true, plan: 'FREE', ...overrides });

        it('explains a budget that does not cover the request, without advice that exercises cannot use', () => {
            const fits = describeExerciseUsage(bucket());
            expect(fits.headline).toBe('Не хватит лимита ИИ на этот запрос.');
            expect(fits.options.join(' ')).toContain('Подождите до');
            expect(fits.options.join(' ')).not.toContain('Кратко');
            expect(fits.options.join(' ')).not.toContain('медиа');
            expect(describeExerciseUsage(bucket({ window: 'DAY' })).headline).toBe('На сегодня лимит ИИ исчерпан.');
            expect(describeExerciseUsage(bucket({ fitsAfterRenewal: false })).options.join(' ')).toContain('всё равно не хватит');
            expect(describeExerciseUsage(bucket({ renewsAt: null })).options).toHaveLength(1);
            expect(describeExerciseUsage(bucket({ offered: false })).headline).toBe('На вашем тарифе это недоступно.');
            expect(describeExerciseUsage(undefined).options[0]).toContain('меньше упражнений');
        });
    });
});
