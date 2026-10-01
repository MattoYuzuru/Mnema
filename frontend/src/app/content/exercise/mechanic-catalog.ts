import { DEMO_CHOICE, DEMO_CLOZE, DEMO_FREE_RESPONSE, DEMO_MATCH, DEMO_SELF_CHECK } from './demo/demo-fixtures';
import { Mechanic, PreviewExercise } from './exercise-content.models';

/**
 * The steps that follow the type choice and the preview. `prompt` and `context` are the question slot (the
 * second is optional context for a cloze), `finish` is naming and saving. A step opens after the previous one
 * is completed with an explicit action, never on keystrokes.
 */
export type StepId = 'prompt' | 'reference' | 'answers' | 'context' | 'passage' | 'options' | 'pairs' | 'finish';

export interface DemoFixture { readonly exercise: PreviewExercise; }

export interface MechanicCatalogEntry {
    readonly mechanic: Mechanic;
    /** Learner action in plain words; the technical enum never reaches the author. */
    readonly title: string;
    readonly description: string;
    readonly steps: readonly StepId[];
    readonly demo: DemoFixture;
}

/**
 * The single registry of what an author can create. The type picker, the step flow, the demo and the
 * exercise list all read it, so a new mechanic is added by appending one entry (plus its editor step).
 */
export const MECHANIC_CATALOG: readonly MechanicCatalogEntry[] = [
    {
        mechanic: 'SELF_CHECK', title: 'Вспомнить и сверить',
        description: 'Сначала вспомните ответ, затем откройте образец и оцените себя. Подходит для карточек и коротких объяснений.',
        steps: ['prompt', 'reference', 'finish'], demo: { exercise: DEMO_SELF_CHECK }
    },
    {
        mechanic: 'FREE_RESPONSE', title: 'Ввести ответ',
        description: 'Ученик напишет ответ на ваш вопрос. Mnema сравнит его с допустимыми вариантами, которые вы зададите.',
        steps: ['prompt', 'answers', 'finish'], demo: { exercise: DEMO_FREE_RESPONSE }
    },
    {
        mechanic: 'CLOZE', title: 'Заполнить пропуски',
        description: 'Скройте нужные слова или части ответа. Ученик восстановит их прямо в тексте.',
        steps: ['context', 'passage', 'finish'], demo: { exercise: DEMO_CLOZE }
    },
    {
        mechanic: 'CHOICE', title: 'Выбрать ответ',
        description: 'Предложите несколько вариантов и отметьте правильные. Ученик выберет один или несколько.',
        steps: ['prompt', 'options', 'finish'], demo: { exercise: DEMO_CHOICE }
    },
    {
        mechanic: 'MATCH', title: 'Сопоставить элементы',
        description: 'Соедините связанные элементы: понятия и определения, записи и текст, изображения и названия.',
        steps: ['prompt', 'pairs', 'finish'], demo: { exercise: DEMO_MATCH }
    }
];

export function catalogEntry(mechanic: Mechanic): MechanicCatalogEntry {
    const entry = MECHANIC_CATALOG.find(candidate => candidate.mechanic === mechanic);
    if (entry === undefined) throw new Error(`Mechanic ${mechanic} is not in the catalog.`);
    return entry;
}

const STEP_TITLES: Readonly<Record<StepId, string>> = {
    prompt: 'Условие', reference: 'Эталон ответа', answers: 'Допустимые ответы', context: 'Контекст',
    passage: 'Текст и пропуски', options: 'Варианты ответа', pairs: 'Пары', finish: 'Название и сохранение'
};
const STEP_TITLE_OVERRIDES: Readonly<Partial<Record<Mechanic, Partial<Record<StepId, string>>>>> = {
    FREE_RESPONSE: { prompt: 'Вопрос' }, CHOICE: { prompt: 'Вопрос' }, MATCH: { prompt: 'Общая инструкция' }
};

export function stepTitle(mechanic: Mechanic, step: StepId): string {
    return STEP_TITLE_OVERRIDES[mechanic]?.[step] ?? STEP_TITLES[step];
}
