import { sideLines } from './exercise-projection';
import { ExerciseSide, UnitSide } from './update-plan.models';

const text = (value: string) => ({ kind: 'TEXT', text: value });
const side = (type: string, content: unknown, answerKey: unknown, extra: Partial<ExerciseSide> = {}): ExerciseSide => ({
    kind: 'EXERCISE', exerciseId: 'aaaaaaaa-aaaa-4aaa-8aaa-000000000001', unavailable: false, type, enabled: true, objectiveTitle: null, content, answerKey, ...extra
});

describe('sideLines', () => {
    it('projects a self-check, a free response and a disabled exercise with its goal', () => {
        expect(sideLines(side('SELF_CHECK', { prompt: [text('Вопрос?')], reference: [text('Ответ')] }, { kind: 'SELF_REPORT' }))).toEqual([
            { label: 'Тип', values: ['Вспомнить и сверить'] }, { label: 'Вопрос', values: ['Вопрос?'] }, { label: 'Эталон ответа', values: ['Ответ'] },
            { label: 'Ответы', values: ['Ученик сам оценивает ответ.'] }
        ]);
        expect(sideLines(side('FREE_RESPONSE', { prompt: [text('Q')], reference: [] }, { accepted: ['a', 'b'] }, { enabled: false, objectiveTitle: 'Цель' }))).toEqual([
            { label: 'Тип', values: ['Ввести ответ'] }, { label: 'Состояние', values: ['Отключено'] }, { label: 'Вопрос', values: ['Q'] },
            { label: 'Допустимые ответы', values: ['a', 'b'] }, { label: 'Цель', values: ['Цель'] }
        ]);
    });

    it('projects a cloze with its blanks and answers', () => {
        const lines = sideLines(side('CLOZE', { prompt: [], passage: [{ kind: 'TEXT', text: 'Yo ' }, { kind: 'BLANK', blankId: 'b1' }, { kind: 'TEXT', text: ' español.' }] },
            { kind: 'CLOZE', blanks: [{ blankId: 'b1', accepted: ['hablo', 'Hablo'] }] }));
        expect(lines.slice(1)).toEqual([{ label: 'Текст с пропусками', values: ['Yo [пропуск] español.'] }, { label: 'Ответы', values: ['1. hablo / Hablo'] }]);
    });

    it('projects choices with the right ones named, in words and not by mark alone', () => {
        const options = [{ optionId: 'a', blocks: [text('да')] }, { optionId: 'b', blocks: [text('нет')] }, { optionId: 'c', blocks: [] }];
        expect(sideLines(side('CHOICE', { prompt: [text('Q')], selectionMode: 'MULTIPLE', options }, { correctOptionIds: ['a', 'c'] })).slice(2)).toEqual([
            { label: 'Варианты', values: ['Можно выбрать несколько', 'да (верный)', 'нет', 'Без текста (верный)'] }
        ]);
    });

    it('projects pairs, an order and groups through the ids of the key', () => {
        const items = (...labels: string[]) => labels.map((label, index) => ({ itemId: `i${index}`, blocks: [text(label)] }));
        expect(sideLines(side('MATCH', { prompt: [], left: items('ser', 'estar'), right: items('быть', 'находиться') }, { pairs: [{ leftId: 'i0', rightId: 'i0' }, { leftId: 'i1', rightId: 'i1' }] })).slice(1))
            .toEqual([{ label: 'Пары', values: ['ser → быть', 'estar → находиться'] }]);
        expect(sideLines(side('ORDER', { prompt: [], items: items('один', 'два') }, { sequence: ['i1', 'i0'] })).slice(1)).toEqual([{ label: 'Верный порядок', values: ['1. два', '2. один'] }]);
        expect(sideLines(side('CATEGORIZE', { prompt: [], categories: [{ categoryId: 'c1', label: 'Постоянное' }, { categoryId: 'c2', label: 'Пусто' }], items: items('ser') },
            { assignments: [{ itemId: 'i0', categoryId: 'c1' }] })).slice(1)).toEqual([{ label: 'Группы: Постоянное', values: ['ser'] }]);
    });

    it('names media blocks and never reads a shape it does not know', () => {
        const blocks = [{ kind: 'IMAGE', alt: 'карта' }, { kind: 'AUDIO', title: 'диалог' }, { kind: 'VIDEO', title: 'урок' }, { kind: 'YOUTUBE', title: 'лекция' }, { kind: 'MATERIAL' }, { kind: 'WEIRD' }];
        expect(sideLines(side('SELF_CHECK', { prompt: blocks }, null))[1]!.values).toEqual(['Картинка: карта', 'Звук: диалог', 'Видео: урок', 'Видео на YouTube: лекция', 'Фрагмент материала']);
        expect(sideLines(side('NEW_MECHANIC', 'nonsense', 7))).toEqual([{ label: 'Тип', values: ['NEW_MECHANIC'] }]);
        expect(sideLines(side('CHOICE', null, null))).toEqual([{ label: 'Тип', values: ['Выбрать ответ'] }]);
    });

    it('projects nothing for a taken-down exercise or a material, and words for the other units', () => {
        expect(sideLines(side('CHOICE', null, null, { unavailable: true }))).toEqual([]);
        expect(sideLines({ kind: 'MATERIAL', memberKey: 'k', document: null, unavailable: true })).toEqual([]);
        expect(sideLines({ kind: 'OBJECTIVE', title: '' })).toEqual([{ label: 'Цель', values: ['Без текста'] }]);
        expect(sideLines({ kind: 'METADATA', title: 'Т', description: '  ' })).toEqual([{ label: 'Название', values: ['Т'] }, { label: 'Описание', values: ['Без описания'] }]);
    });

    it('projects the order: moved titles with places, a count when titles are withheld, and the truncation', () => {
        const move = (title: string | null, from: number | null, to: number | null) => ({ memberKey: 'k', title, from, to });
        const order = (moved: ReturnType<typeof move>[], total: number, truncated = false): UnitSide => ({ kind: 'ORDER', moved, total, truncated });
        expect(sideLines(order([], 0))).toEqual([{ label: 'Передвинутые материалы', values: ['Здесь порядок не менялся.'] }]);
        expect(sideLines(order([], 9000))).toEqual([{ label: 'Передвинутые материалы', values: ['Передвинуто материалов: 9000'] }]);
        expect(sideLines(order([move(null, null, null)], 6000, true))).toEqual([{ label: 'Передвинутые материалы', values: ['Передвинуто материалов: 6000'] }]);
        expect(sideLines(order([move('comer', 7, 2), move('vivir', null, null)], 70, true))).toEqual([
            { label: 'Передвинутые материалы', values: ['«comer»: было место 7, стало 2', '«vivir»', 'Показано 2 из 70.'] }
        ]);
    });
});
