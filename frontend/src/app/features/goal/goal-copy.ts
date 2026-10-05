import { LearningGoal } from './goal.models';

/**
 * Every place the goal changes words, in one map. The goal never changes a right, a limit or a price: only copy and which
 * tier is highlighted. Add a place here, not in a template.
 */
export interface GoalCopy {
    /** The option of the onboarding question. */
    readonly label: string;
    /** The sentence after «Рекомендуем для», in the genitive: «Рекомендуем для подготовки к экзаменам». */
    readonly recommendedFor: string;
    /** The heading of /plans. */
    readonly plansHeading: string;
    /** The placeholder of the composer of a new material. */
    readonly composerPlaceholder: string;
    /** The sentence of the empty deck list. */
    readonly emptyDecks: string;
}

export const NEUTRAL_COPY: Omit<GoalCopy, 'label' | 'recommendedFor'> = {
    plansHeading: 'Тарифы Mnema',
    composerPlaceholder: 'Например: 20 глаголов движения с примерами из аниме',
    emptyDecks: 'Создайте колоду и задайте ей название. Материалы появятся в следующих шагах.'
};

export const GOAL_COPY: Readonly<Record<LearningGoal, GoalCopy>> = {
    EXAMS: {
        label: 'Экзамены и сессия', recommendedFor: 'подготовки к экзаменам',
        plansHeading: 'Подготовка к экзаменам: тариф, которому хватит на сессию',
        composerPlaceholder: 'Например: 20 ключевых дат по истории с короткими пояснениями',
        emptyDecks: 'Создайте колоду под предмет или экзамен: в неё лягут конспекты и вопросы к сессии.'
    },
    INTERVIEW: {
        label: 'Собеседование', recommendedFor: 'подготовки к собеседованию',
        plansHeading: 'Подготовка к собеседованию: тариф на время подготовки',
        composerPlaceholder: 'Например: 15 вопросов по коллекциям Java с короткими ответами',
        emptyDecks: 'Создайте колоду под вакансию: в неё лягут вопросы собеседования и ваши ответы.'
    },
    LANGUAGE: {
        label: 'Язык', recommendedFor: 'изучения языка',
        plansHeading: 'Изучение языка: голос и проверка ответов каждый день',
        composerPlaceholder: 'Например: 20 глаголов движения с примерами из аниме',
        emptyDecks: 'Создайте колоду под язык: в неё лягут слова, фразы и правила, которые вы встретили.'
    },
    WORK: {
        label: 'Работа', recommendedFor: 'учёбы для работы',
        plansHeading: 'Учёба для работы: больше материалов и голоса',
        composerPlaceholder: 'Например: термины из рабочей презентации с примерами',
        emptyDecks: 'Создайте колоду под рабочую тему: в неё лягут термины, регламенты и заметки.'
    },
    SELF: {
        label: 'Для себя', recommendedFor: 'учёбы для себя',
        plansHeading: 'Учёба для себя: платите, только если не хватает',
        composerPlaceholder: 'Например: 10 фактов о космосе с короткими объяснениями',
        emptyDecks: 'Создайте колоду о том, что вам интересно: в неё лягут заметки и находки.'
    }
};

export function copyFor(goal: LearningGoal | null): Omit<GoalCopy, 'label' | 'recommendedFor'> {
    return goal === null ? NEUTRAL_COPY : GOAL_COPY[goal];
}
