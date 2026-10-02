import { Mechanic } from '../../../content/exercise/exercise-content.models';
import { BulkDeleteResult, DeletionPreview, HubFailure, MaterialState } from './deck-hub.models';

/** Russian plural form for a count: «1 материал», «2 материала», «5 материалов». */
export function plural(count: number, one: string, few: string, many: string): string {
    const tail = Math.abs(count) % 100;
    const last = tail % 10;
    if (tail > 10 && tail < 20) return many;
    if (last === 1) return one;
    if (last >= 2 && last <= 4) return few;
    return many;
}

export function materialsText(count: number): string { return `${count} ${plural(count, 'материал', 'материала', 'материалов')}`; }
export function exercisesText(count: number): string { return `${count} ${plural(count, 'упражнение', 'упражнения', 'упражнений')}`; }
export function notesText(count: number): string { return `${count} ${plural(count, 'заметка', 'заметки', 'заметок')}`; }
export function daysText(count: number): string { return `${count} ${plural(count, 'день', 'дня', 'дней')}`; }

export const STATE_LABELS: Readonly<Record<MaterialState, string>> = {
    NOT_STARTED: 'Не начато', LEARNING: 'Учится', DUE: 'К повторению', ON_TRACK: 'В порядке'
};

/** Short author-facing names of the seven mechanics, the same words as the mechanic catalog. */
export const MECHANIC_LABELS: Readonly<Record<Mechanic, string>> = {
    SELF_CHECK: 'Вспомнить и сверить', FREE_RESPONSE: 'Ввести ответ', CLOZE: 'Заполнить пропуски', CHOICE: 'Выбрать ответ',
    MATCH: 'Сопоставить', ORDER: 'Восстановить порядок', CATEGORIZE: 'Распределить'
};

/** The sentence under the button that arms deletion: what disappears and what stays. */
export function consequenceText(preview: DeletionPreview): string {
    return `Удалит ${materialsText(preview.materialCount)} и ${exercisesText(preview.affectedExerciseCount)}. История занятий сохранится.`;
}

export const STALE_DECK_MESSAGE = 'Колода изменилась в другой вкладке. Ничего не удалено. Обновите список.';
export const UNCERTAIN_DELETE_MESSAGE = 'Удаление не подтверждено. Повторите удержание: будет отправлена та же команда.';
export const TOO_LARGE_MESSAGE = `За один раз можно удалить не больше 500 материалов. Снимите выбор с части.`;
export const TOO_MANY_EXPLICIT_MESSAGE = 'Отдельно можно отметить не больше 100 материалов. Снимите часть или выберите все материалы колоды.';

/** The honest outcome of a bulk deletion: what was deleted and, for a partial run, what is still there. */
export function deletionOutcomeText(result: BulkDeleteResult): string {
    const deleted = `Удалено ${materialsText(result.deleted)}.`;
    if (result.status === 'COMPLETED') return deleted;
    const left = result.notDeleted.length;
    const kept = `${left} ${plural(left, 'не тронут', 'не тронуты', 'не тронуты')}`;
    return `${deleted} ${kept}: колода изменилась в другой вкладке. Обновите список.`;
}

/** A calm sentence for a failed hub request that the surface has no more specific text for. */
export function hubFailureText(failure: HubFailure | null, action: string): string {
    if (failure === null || failure.status === 0) return `${action}: нет связи. Попробуйте ещё раз.`;
    if (failure.status === 412) return 'Колода или материал изменились в другой вкладке. Обновите список.';
    if (failure.status === 404) return 'Колода или материал больше недоступны. Обновите страницу.';
    if (failure.status === 403 || failure.status === 401) return 'Для этого действия не хватает прав. Войдите снова.';
    return `${action}: не удалось выполнить запрос. Попробуйте ещё раз.`;
}
