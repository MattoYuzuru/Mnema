import { interestTopicGroups, relatedInterestTopics } from './interest-topics';
import { DEMO_INTEREST_TOPICS } from '../styleguide/sg-interest-topics-demo';

describe('interest topic exploration', () => {
    it('groups existing selectable leaves by their catalogue parents without inventing choices', () => {
        const groups = interestTopicGroups(DEMO_INTEREST_TOPICS);
        expect(groups).toHaveLength(10);
        expect(groups.find(group => group.id === 'languages')?.name).toBe('Языки');
        expect(groups.find(group => group.id === 'other')?.topics.map(topic => topic.id)).toEqual(['other']);
        expect(groups.flatMap(group => group.topics)).toEqual(expect.arrayContaining(DEMO_INTEREST_TOPICS));
    });
    it('offers at most five unselected neighbours deterministically and reaches related directions for small groups', () => {
        const neighbours = relatedInterestTopics(DEMO_INTEREST_TOPICS, 'anatomy', ['anatomy']);
        expect(neighbours).toHaveLength(5);
        expect(neighbours.slice(0, 2).map(topic => topic.id)).toEqual(['pharmacology', 'medicine-general']);
        expect(neighbours.some(topic => topic.parentId === 'science')).toBe(true);
        expect(neighbours).toEqual(relatedInterestTopics(DEMO_INTEREST_TOPICS, 'anatomy', ['anatomy']));
        expect(neighbours.every(topic => DEMO_INTEREST_TOPICS.includes(topic))).toBe(true);
        expect(relatedInterestTopics(DEMO_INTEREST_TOPICS, 'missing', [])).toEqual([]);
        expect(relatedInterestTopics(DEMO_INTEREST_TOPICS, 'anatomy', DEMO_INTEREST_TOPICS.map(topic => topic.id))).toEqual([]);
    });
});
