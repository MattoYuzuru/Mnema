import { GOAL_COPY, NEUTRAL_COPY, copyFor } from './goal-copy';
import { LEARNING_GOALS } from './goal.models';

describe('goal copy', () => {
    it('has every place of copy for every goal, all different from each other', () => {
        for (const key of ['label', 'recommendedFor', 'plansHeading', 'composerPlaceholder', 'emptyDecks'] as const) {
            const values = LEARNING_GOALS.map(goal => GOAL_COPY[goal][key]);
            expect(values.every(value => value.length > 0), key).toBe(true);
        }
        expect(new Set(LEARNING_GOALS.map(goal => GOAL_COPY[goal].label)).size).toBe(LEARNING_GOALS.length);
        expect(new Set(LEARNING_GOALS.map(goal => GOAL_COPY[goal].plansHeading)).size).toBe(LEARNING_GOALS.length);
    });

    it('is neutral without a goal', () => {
        expect(copyFor(null)).toBe(NEUTRAL_COPY);
        expect(copyFor('WORK')).toBe(GOAL_COPY['WORK']);
    });
});
