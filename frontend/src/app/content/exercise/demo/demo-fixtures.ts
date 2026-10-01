import { PreviewExercise } from '../exercise-content.models';
import { DEMO_ASSETS } from './demo-media';

/**
 * Original, self-contained example exercises, one per mechanic. They exist only in the frontend, are shown
 * only while the author's draft is pristine and are evaluated by the author preview endpoint like any other
 * exercise. They are never copied into a draft, a saved payload or a learner cache.
 */
const id = (suffix: number): string => `de000000-0000-4000-8000-${suffix.toString().padStart(12, '0')}`;
const text = (value: string) => ({ kind: 'TEXT', text: value }) as const;
const rules = ['UNICODE_NFC', 'TRIM', 'CASE_FOLD'] as const;

export const DEMO_SELF_CHECK: PreviewExercise = {
    type: 'SELF_CHECK', schemaVersion: 2,
    content: {
        prompt: [text('Какой город является столицей Австралии?')],
        reference: [text('Канберра. Самые большие города страны — Сидней и Мельбурн, поэтому столицу построили отдельно, как компромисс между ними.')]
    },
    answerKey: { kind: 'SELF_REPORT' }, evaluatorPolicy: { id: 'self-check', version: '1' }
};

export const DEMO_FREE_RESPONSE: PreviewExercise = {
    type: 'FREE_RESPONSE', schemaVersion: 2,
    content: { prompt: [text('Как по-немецки «среда» — день недели между вторником и четвергом?')], reference: [], responseInput: 'TEXT' },
    answerKey: { kind: 'TEXT', accepted: ['Mittwoch', 'der Mittwoch'], normalization: rules, matchingMode: 'STRICT' },
    evaluatorPolicy: { id: 'deterministic-text', version: '1' }
};

export const DEMO_CLOZE: PreviewExercise = {
    type: 'CLOZE', schemaVersion: 2,
    content: {
        prompt: [text('Дополните расписание недели.')],
        passage: [
            { kind: 'TEXT', text: 'Понедельник — ' },
            { kind: 'BLANK', blankId: id(11), size: { mode: 'ANSWER_LENGTH' }, firstLetterHint: true },
            { kind: 'TEXT', text: ', вторник — ' },
            { kind: 'BLANK', blankId: id(12), size: { mode: 'FIXED', length: 8 }, firstLetterHint: false },
            { kind: 'TEXT', text: ', среда — выходной.' }
        ]
    },
    answerKey: {
        kind: 'CLOZE', blanks: [
            { blankId: id(11), accepted: ['математика'], normalization: rules, matchingMode: 'STRICT' },
            { blankId: id(12), accepted: ['физика'], normalization: rules, matchingMode: 'STRICT' }
        ]
    },
    evaluatorPolicy: { id: 'deterministic-cloze', version: '1' }
};

export const DEMO_CHOICE: PreviewExercise = {
    type: 'CHOICE', schemaVersion: 2,
    content: {
        prompt: [
            text('Схема показывает очень частые колебания. Какому из двух звуков она соответствует?'),
            { kind: 'IMAGE', assetId: DEMO_ASSETS.waveDense, alt: 'Схема звуковой волны: колебания частые' }
        ],
        selectionMode: 'SINGLE',
        options: [
            { optionId: id(21), blocks: [text('Звук 1'), { kind: 'AUDIO', assetId: DEMO_ASSETS.toneLow, title: 'Низкий тон' }] },
            { optionId: id(22), blocks: [text('Звук 2'), { kind: 'AUDIO', assetId: DEMO_ASSETS.toneHigh, title: 'Высокий тон' }] }
        ]
    },
    answerKey: { kind: 'CHOICE', correctOptionIds: [id(22)] },
    evaluatorPolicy: { id: 'deterministic-choice', version: '1' }
};

export const DEMO_MATCH: PreviewExercise = {
    type: 'MATCH', schemaVersion: 2,
    content: {
        prompt: [text('Соедините пары: слова, звук и схему.')],
        left: [
            { itemId: id(31), blocks: [text('Понедельник')] },
            { itemId: id(32), blocks: [text('Среда')] },
            { itemId: id(33), blocks: [{ kind: 'AUDIO', assetId: DEMO_ASSETS.toneLow, title: 'Низкий тон' }] },
            { itemId: id(34), blocks: [{ kind: 'IMAGE', assetId: DEMO_ASSETS.waveDense, alt: 'Схема звуковой волны: колебания частые' }] }
        ],
        right: [
            { itemId: id(41), blocks: [text('Montag')] },
            { itemId: id(42), blocks: [text('Mittwoch')] },
            { itemId: id(43), blocks: [text('Низкий звук')] },
            { itemId: id(44), blocks: [text('Высокий звук')] }
        ]
    },
    answerKey: { kind: 'MATCH', pairs: [31, 32, 33, 34].map(left => ({ leftId: id(left), rightId: id(left + 10) })) },
    evaluatorPolicy: { id: 'deterministic-match', version: '1' }
};
