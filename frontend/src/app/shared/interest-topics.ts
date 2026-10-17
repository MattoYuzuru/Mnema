import { CommunityTopic } from '../features/community/community.models';

export interface InterestTopicGroup {
    readonly id: string;
    readonly name: string;
    readonly topics: readonly CommunityTopic[];
}

// Названия родителей есть в справочнике публикации; профиль интересов передаёт только листья.
const CATEGORY_NAMES: Readonly<Record<string, string>> = {
    languages: 'Языки', exams: 'Экзамены', school: 'Школа', 'history-culture': 'История и культура',
    science: 'Наука', medicine: 'Медицина', it: 'IT и программирование', professional: 'Профессии', everyday: 'Жизнь'
};
const RELATED_CATEGORIES: Readonly<Record<string, readonly string[]>> = {
    languages: ['exams', 'history-culture'], exams: ['languages', 'school'], school: ['science', 'history-culture'],
    'history-culture': ['science', 'languages'], science: ['school', 'medicine'], medicine: ['science', 'school'],
    it: ['professional', 'science'], professional: ['it', 'science'], everyday: ['history-culture', 'professional']
};

export function interestTopicGroups(topics: readonly CommunityTopic[]): readonly InterestTopicGroup[] {
    const groups = new Map<string, { id: string; name: string; topics: CommunityTopic[] }>();
    for (const topic of topics) {
        const id = topic.parentId ?? topic.id;
        let group = groups.get(id);
        if (group === undefined) {
            group = { id, name: topic.parentId === null ? topic.name : CATEGORY_NAMES[id] ?? 'Другие темы', topics: [] };
            groups.set(id, group);
        }
        group.topics.push(topic);
    }
    return [...groups.values()];
}

/** Связи берутся из общего направления и соседних направлений, а значения — только из ответа API. */
export function relatedInterestTopics(topics: readonly CommunityTopic[], anchorId: string, selected: readonly string[]): readonly CommunityTopic[] {
    const anchor = topics.find(topic => topic.id === anchorId);
    if (anchor === undefined) return [];
    const groupId = anchor.parentId ?? anchor.id;
    const siblings = topics.filter(topic => (topic.parentId ?? topic.id) === groupId);
    const index = siblings.findIndex(topic => topic.id === anchorId);
    const orderedSiblings = [...siblings.slice(index + 1), ...siblings.slice(0, index)];
    const nearby = (RELATED_CATEGORIES[groupId] ?? []).flatMap(id => topics.filter(topic => (topic.parentId ?? topic.id) === id));
    return [...orderedSiblings, ...nearby].filter(topic => !selected.includes(topic.id)).slice(0, 5);
}
