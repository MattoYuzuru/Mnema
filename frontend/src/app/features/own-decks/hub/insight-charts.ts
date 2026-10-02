import { MECHANICS, Mechanic } from '../../../content/exercise/exercise-content.models';
import { DeckInsights, DueDay, MATERIAL_STATES, MaterialState } from './deck-hub.models';
import { MECHANIC_LABELS, STATE_LABELS, daysText, exercisesText, materialsText, notesText, plural } from './deck-hub.text';

/**
 * Pure geometry and wording for the five Insight widgets. The template only places what is computed here, so the
 * numbers in the drawing, the caption sentence and the table alternative cannot disagree.
 */

export interface StateSegment { readonly state: MaterialState; readonly label: string; readonly count: number; readonly x: number; readonly width: number; }
export interface DueBar { readonly date: string; readonly label: string; readonly tableLabel: string; readonly materials: number; readonly height: number; readonly x: number; }
export interface MechanicBar { readonly mechanic: Mechanic; readonly label: string; readonly count: number; readonly width: number; }

export const STATE_BAR_WIDTH = 300;
export const DUE_COLUMN = 40;
export const DUE_PLOT_HEIGHT = 80;
export const MECHANIC_PLOT_WIDTH = 100;
export const MECHANIC_ROW = 10;

/** Share of the ring that is covered, as a stroke length on a ring whose `pathLength` is 100. */
export function coverageArc(coverage: DeckInsights['coverage']): number {
    return coverage.total === 0 ? 0 : Math.round(coverage.withExercises / coverage.total * 1000) / 10;
}

export function stateSegments(states: DeckInsights['states']): readonly StateSegment[] {
    const total = MATERIAL_STATES.reduce((sum, state) => sum + states[state], 0);
    if (total === 0) return [];
    let x = 0;
    // The order is the learner's path: settled first, then the ones that need a visit, then untouched.
    return (['ON_TRACK', 'LEARNING', 'DUE', 'NOT_STARTED'] as const).filter(state => states[state] > 0).map(state => {
        const width = states[state] / total * STATE_BAR_WIDTH;
        const segment = { state, label: STATE_LABELS[state], count: states[state], x, width };
        x += width;
        return segment;
    });
}

export function dueBars(days: readonly DueDay[]): readonly DueBar[] {
    const peak = Math.max(1, ...days.map(day => day.materials));
    return days.map((day, index) => ({
        date: day.date, label: dayLabel(day.date, index), tableLabel: `${index === 0 ? 'Сегодня, ' : ''}${longDay(day.date)}`, materials: day.materials, x: index * DUE_COLUMN + 8,
        // A zero day keeps a short rule so the empty column is still drawn.
        height: day.materials === 0 ? 2 : Math.max(6, Math.round(day.materials / peak * (DUE_PLOT_HEIGHT - 8)))
    }));
}

export function mechanicBars(counts: DeckInsights['exercisesByMechanic']): readonly MechanicBar[] {
    const peak = Math.max(1, ...MECHANICS.map(mechanic => counts[mechanic]));
    return MECHANICS.map(mechanic => ({
        mechanic, label: MECHANIC_LABELS[mechanic], count: counts[mechanic],
        width: counts[mechanic] === 0 ? 0 : Math.max(4, Math.round(counts[mechanic] / peak * MECHANIC_PLOT_WIDTH))
    }));
}

/** «сегодня» for the first column, then «пт, 4 окт.» from the ISO date (the date is already the account's local day). */
export function dayLabel(date: string, index: number): string {
    if (index === 0) return 'сегодня';
    return new Intl.DateTimeFormat('ru-RU', { weekday: 'short', day: 'numeric', month: 'short', timeZone: 'UTC' })
        .format(new Date(`${date}T12:00:00Z`));
}

function longDay(date: string): string {
    return new Intl.DateTimeFormat('ru-RU', { weekday: 'long', day: 'numeric', month: 'long', timeZone: 'UTC' })
        .format(new Date(`${date}T12:00:00Z`));
}

export function coverageSentence(coverage: DeckInsights['coverage']): string {
    if (coverage.total === 0) return 'В колоде пока нет материалов.';
    const of = plural(coverage.total, 'материала', 'материалов', 'материалов');
    return `С упражнениями ${coverage.withExercises} из ${coverage.total} ${of}.`;
}

export function coverageHint(coverage: DeckInsights['coverage']): string {
    if (coverage.total === 0) return 'Добавьте первый материал, и здесь появится покрытие.';
    if (coverage.withoutExercises === 0) return 'Каждый материал попадёт в занятия.';
    const verb = plural(coverage.withoutExercises, 'не попадёт', 'не попадут', 'не попадут');
    return `${materialsText(coverage.withoutExercises)} без упражнений ${verb} в занятия.`;
}

export function statesSentence(states: DeckInsights['states']): string {
    const total = MATERIAL_STATES.reduce((sum, state) => sum + states[state], 0);
    if (total === 0) return 'Состояния появятся, когда в колоде будут материалы.';
    return `К повторению ${states.DUE}, учится ${states.LEARNING}, в порядке ${states.ON_TRACK}, не начато ${states.NOT_STARTED}.`;
}

export function dueSentence(days: readonly DueDay[]): string {
    if (days.length > 0 && days[0].materials > 0) {
        return `Сегодня можно повторить ${materialsText(days[0].materials)}.`;
    }
    const next = days.find(day => day.materials > 0);
    if (next === undefined) return 'На ближайшие 7 дней повторений нет.';
    return `Следующее повторение: ${longDay(next.date)}, ${materialsText(next.materials)}.`;
}

export function mechanicsSentence(counts: DeckInsights['exercisesByMechanic']): string {
    const total = MECHANICS.reduce((sum, mechanic) => sum + counts[mechanic], 0);
    if (total === 0) return 'Упражнений пока нет.';
    const used = MECHANICS.filter(mechanic => counts[mechanic] > 0);
    const top = used.reduce((best, mechanic) => counts[mechanic] > counts[best] ? mechanic : best, used[0]);
    const unused = MECHANICS.length - used.length;
    const spread = unused === 0 ? 'Используются все семь механик.'
        : `Не использовано ${unused} ${plural(unused, 'механика', 'механики', 'механик')} из ${MECHANICS.length}.`;
    return `Всего ${exercisesText(total)}; чаще всего «${MECHANIC_LABELS[top]}»: ${counts[top]}. ${spread}`;
}

export function capturesSentence(captures: DeckInsights['captures'], asOf: string): string {
    if (captures.open === 0 || captures.oldestOpenCreatedAt === null) return 'Неразобранных заметок нет.';
    const days = Math.max(0, Math.floor((Date.parse(asOf) - Date.parse(captures.oldestOpenCreatedAt)) / 86_400_000));
    const age = days === 0 ? 'сегодня' : `${daysText(days)} назад`;
    return `Ждут разбора ${notesText(captures.open)}; самая давняя — ${age}.`;
}

/** Whether some mechanic is unused while others are in use: the widget then suggests adding one. */
export function hasUnusedMechanic(counts: DeckInsights['exercisesByMechanic']): boolean {
    const used = MECHANICS.filter(mechanic => counts[mechanic] > 0).length;
    return used > 0 && used < MECHANICS.length;
}
