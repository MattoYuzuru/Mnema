import { AuthoringBlock, ExerciseSpec } from '../../content/exercise/exercise-content.models';

/**
 * The words a learner reads in an exercise, and the correct answers, one line per field: what the diff of a revised exercise compares
 * (AI-16, #294). Media has no words and a quote of the material is shown by its text; ids, order of the ids and policies are not text.
 * A line starts with the role of the field («Вопрос», «Вариант», «Ответ»…), so a change reads as «Вариант: …».
 */
export function exerciseTextLines(exercise: ExerciseSpec, quotes: Readonly<Record<string, string>> = {}): readonly string[] {
    const lines: string[] = [];
    const blocks = (role: string, list: readonly AuthoringBlock[]): void => {
        for (const block of list) {
            if (block.kind === 'TEXT') lines.push(`${role}: ${block.text}`);
            else if (block.kind === 'MATERIAL') {
                const quote = quotes[block.nodeId];
                if (quote !== undefined && quote.length > 0) lines.push(`${role}: ${quote}`);
            }
        }
    };
    switch (exercise.type) {
        case 'SELF_CHECK':
            blocks('Вопрос', exercise.content.prompt);
            blocks('Эталон', exercise.content.reference);
            break;
        case 'FREE_RESPONSE':
            blocks('Вопрос', exercise.content.prompt);
            blocks('Эталон', exercise.content.reference);
            for (const accepted of exercise.answerKey.accepted) lines.push(`Ответ: ${accepted}`);
            break;
        case 'CLOZE': {
            blocks('Задание', exercise.content.prompt);
            const passage = exercise.content.passage.map(segment => segment.kind === 'TEXT' ? segment.text : '____').join('');
            lines.push(`Текст: ${passage}`);
            for (const blank of exercise.answerKey.blanks) lines.push(`Ответ: ${blank.accepted.join(' / ')}`);
            break;
        }
        case 'CHOICE': {
            blocks('Вопрос', exercise.content.prompt);
            const correct = new Set(exercise.answerKey.correctOptionIds);
            for (const option of exercise.content.options) blocks(correct.has(option.optionId) ? 'Верный вариант' : 'Вариант', option.blocks);
            break;
        }
        case 'MATCH': {
            blocks('Задание', exercise.content.prompt);
            const left = new Map(exercise.content.left.map(item => [item.itemId, item] as const));
            const right = new Map(exercise.content.right.map(item => [item.itemId, item] as const));
            for (const pair of exercise.answerKey.pairs) {
                const first = left.get(pair.leftId);
                const second = right.get(pair.rightId);
                const half = (list: readonly AuthoringBlock[] | undefined): string => (list ?? []).flatMap(block => block.kind === 'TEXT' ? [block.text] : []).join(' ');
                lines.push(`Пара: ${half(first?.blocks)} — ${half(second?.blocks)}`);
            }
            break;
        }
        case 'ORDER': {
            blocks('Задание', exercise.content.prompt);
            const items = new Map(exercise.content.items.map(item => [item.itemId, item] as const));
            exercise.answerKey.sequence.forEach((itemId, position) => blocks(`Шаг ${position + 1}`, items.get(itemId)?.blocks ?? []));
            break;
        }
        case 'CATEGORIZE': {
            blocks('Задание', exercise.content.prompt);
            const categories = new Map(exercise.content.categories.map(category => [category.categoryId, category.label] as const));
            for (const category of exercise.content.categories) lines.push(`Группа: ${category.label}`);
            const items = new Map(exercise.content.items.map(item => [item.itemId, item] as const));
            for (const assignment of exercise.answerKey.assignments) {
                const words = (items.get(assignment.itemId)?.blocks ?? []).flatMap(block => block.kind === 'TEXT' ? [block.text] : []).join(' ');
                lines.push(`Элемент: ${words} → ${categories.get(assignment.categoryId) ?? ''}`);
            }
            break;
        }
    }
    return lines;
}
