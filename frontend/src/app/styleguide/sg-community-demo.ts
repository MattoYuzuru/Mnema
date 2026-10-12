import { AuthorProfile } from '../shared/author-header.component';
import { LanguageOption, TopicOption } from '../shared/catalog-filters';
import { PublicDeckCard } from '../shared/public-deck-card';

/** Выдуманные данные для образцов раздела «Сообщество»: ни имена, ни адреса, ни числа не настоящие. */

const base = (patch: Partial<PublicDeckCard> & Pick<PublicDeckCard, 'id' | 'title' | 'description'>): PublicDeckCard => ({
    topic: 'Языки', language: 'Испанский', author: { username: 'anna.k', avatarSrc: null }, materialCount: 120, exerciseCount: 240, media: [],
    addedCount: 0, learningNowCount: 0, updatedAt: '2026-10-03T09:00:00Z', shareUrl: `https://mnema.app/d/${patch.id}/demo`, ...patch
});

/** Восемь разных карточек: с автором и без, малые и большие счётчики, все виды медиа, колода по приглашению с пометкой. */
export const COMMUNITY_CARDS: readonly PublicDeckCard[] = [
    base({
        id: 'cm-spanish', title: 'Испанские глаголы: 300 самых частых',
        description: 'Спряжение в настоящем и прошедшем времени, короткие примеры из жизни и проверка на слух. Подходит для уровня A2 и выше, занимает около месяца.',
        materialCount: 312, exerciseCount: 640, media: ['audio', 'image'], addedCount: 12_345, learningNowCount: 1_234
    }),
    base({
        id: 'cm-capitals', title: 'Столицы Европы', description: 'Страна и столица, без картинок.', topic: 'География', language: 'Русский',
        author: { username: null, avatarSrc: null }, materialCount: 44, exerciseCount: 44, addedCount: 9, learningNowCount: 3, updatedAt: '2025-12-20T09:00:00Z'
    }),
    base({
        id: 'cm-anatomy', title: 'Анатомия: кости, суставы и мышцы', description: 'Атлас на латыни и по-русски. Каждая кость с рисунком, суставы с типами движений, мышцы с местами прикрепления.',
        topic: 'Наука', language: 'Латинский', author: { username: 'dr.petrov', avatarSrc: '/assets/demo/wave-dense.svg' }, materialCount: 1_240, exerciseCount: 2_480,
        media: ['image', 'video'], addedCount: 98_765, learningNowCount: 410, updatedAt: '2026-10-09T09:00:00Z'
    }),
    base({
        id: 'cm-hiragana', title: 'Хирагана за неделю', description: 'Пять знаков в день: написание, звук и слово-подсказка.', language: 'Японский',
        author: { username: 'mari', avatarSrc: null }, materialCount: 46, exerciseCount: 92, media: ['audio', 'image', 'video'], addedCount: 640, learningNowCount: 58
    }),
    base({
        id: 'cm-sql', title: 'SQL для собеседований', description: 'Двадцать запросов, которые спрашивают чаще всего, с разбором ошибок.', topic: 'Программирование', language: 'Английский',
        author: { username: 'dev.olga', avatarSrc: null }, materialCount: 20, exerciseCount: 60, addedCount: 2_300, learningNowCount: 130, updatedAt: '2026-09-28T09:00:00Z'
    }),
    base({
        id: 'cm-lectures', title: 'Закрытый курс по истории философии', description: 'Лекции и вопросы к ним. Автор открывает курс только приглашённым.', topic: 'Гуманитарные науки', language: 'Русский',
        author: { username: 'prof.lev', avatarSrc: null }, materialCount: 85, exerciseCount: 170, media: ['audio'], addedCount: 22, learningNowCount: 14, updatedAt: '2026-10-05T09:00:00Z'
    }),
    base({
        id: 'cm-german', title: 'Немецкие артикли без боли', description: 'Правила рода по окончаниям и 500 слов с картинками.', language: 'Немецкий',
        author: { username: 'anna.k', avatarSrc: null }, materialCount: 510, exerciseCount: 900, media: ['image'], addedCount: 4_100, learningNowCount: 205
    }),
    base({
        id: 'cm-chemistry', title: 'Таблица Менделеева', description: 'Символы, названия и группы элементов. Короткие вечерние подходы.', topic: 'Наука', language: 'Русский',
        author: { username: 'himik', avatarSrc: null }, materialCount: 118, exerciseCount: 236, media: ['image'], addedCount: 7_800, learningNowCount: 320, updatedAt: '2026-08-14T09:00:00Z'
    })
];

/** «Доступна вам»: колода по приглашению в профиле автора (индекс в {@link COMMUNITY_CARDS}). */
export const INVITATION_CARD: PublicDeckCard = COMMUNITY_CARDS[5];
export const INVITATION_NOTE = 'Доступна вам';

/** Колоды для автоподгрузки: восемь образцов, повторённые с новыми id, чтобы набралось много. */
export function demoCards(from: number, to: number): PublicDeckCard[] {
    return Array.from({ length: to - from }, (_, index) => {
        const number = from + index;
        const card = COMMUNITY_CARDS[number % COMMUNITY_CARDS.length];
        return { ...card, id: `${card.id}-${number}`, title: number < COMMUNITY_CARDS.length ? card.title : `${card.title} (${Math.floor(number / COMMUNITY_CARDS.length) + 1})` };
    });
}

/** Темы: родитель стоит перед своими детьми, дети собраны под его заголовком. */
export const DEMO_TOPICS: readonly TopicOption[] = [
    { id: 'languages', name: 'Языки' },
    { id: 'spanish', name: 'Испанский язык', parentName: 'Языки' },
    { id: 'japanese', name: 'Японский язык', parentName: 'Языки' },
    { id: 'german', name: 'Немецкий язык', parentName: 'Языки' },
    { id: 'science', name: 'Наука' },
    { id: 'anatomy', name: 'Анатомия', parentName: 'Наука' },
    { id: 'chemistry', name: 'Химия', parentName: 'Наука' },
    { id: 'it', name: 'Программирование' },
    { id: 'sql', name: 'SQL и базы данных', parentName: 'Программирование' }
];

export const DEMO_LANGUAGES: readonly LanguageOption[] = [
    { code: 'es', name: 'Испанский' }, { code: 'ja', name: 'Японский' }, { code: 'de', name: 'Немецкий' }, { code: 'en', name: 'Английский' },
    { code: 'la', name: 'Латинский' }
];

export const DEMO_AUTHOR: AuthorProfile = {
    accountId: '00000000-0000-4000-8000-000000000001', profileUsername: 'anna.k', displayName: 'Анна Ковалёва',
    bio: 'Преподаю испанский девять лет.\nДелаю колоды для тех, кто начинает с нуля.', avatarSrc: '/assets/demo/wave-dense.svg'
};

/** Автор, который показал только логин. */
export const DEMO_AUTHOR_BARE: AuthorProfile = {
    accountId: '00000000-0000-4000-8000-000000000002', profileUsername: 'dev.olga', displayName: null, bio: null, avatarSrc: null
};
