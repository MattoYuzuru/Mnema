import journey from '../../../../../contracts/onboarding/journey.json';
import contract from '../../../../../contracts/onboarding/milestones.json';
import { parseOnboardingView } from './onboarding-api.service';
import { ONBOARDING_TEXTS } from './onboarding-texts';
import { PATH_PLACES, PATH_STATIC_HREFS } from './path-places';
import {
    MILESTONE_CHAPTERS,
    MILESTONE_KEYS,
    MILESTONE_REGISTRY,
    MILESTONE_ROUTES,
    MILESTONE_STATES,
    MILESTONE_VISIBILITIES,
    pathChapters
} from './path.models';

/**
 * contracts/onboarding/milestones.json is the registry shared with the backend (MilestoneKey, the V74 CHECK, the inventory test):
 * the closed lists, the order, the targets and the route keys here must equal it, and the examples must parse strictly.
 */
describe('«Путь» wire contract (contracts/onboarding/milestones.json)', () => {
    const view = journey.views.offered;

    describe('closed lists', () => {
        it('has the keys in display order, each with its chapter, target, visibility and route key', () => {
            expect([...MILESTONE_KEYS]).toEqual(contract.milestones.map(milestone => milestone.key));
            for (const expected of contract.milestones) {
                const own = MILESTONE_REGISTRY.find(milestone => milestone.key === expected.key);
                expect(own, expected.key).toEqual({ key: expected.key, chapter: expected.chapter, target: expected.target, visibility: expected.visibility, route: expected.route });
            }
        });

        it('has the chapters, states and visibility conditions of the contract', () => {
            expect([...MILESTONE_CHAPTERS]).toEqual(contract.chapters);
            expect([...MILESTONE_STATES]).toEqual(contract.states);
            expect([...MILESTONE_VISIBILITIES]).toEqual(Object.keys(contract.visibility));
        });

        it('has a place resolver for every route key of the contract, and a static link for each', () => {
            const keys = Object.keys(contract.routes);
            expect([...MILESTONE_ROUTES].sort()).toEqual([...keys].sort());
            expect(Object.keys(PATH_PLACES).sort()).toEqual([...keys].sort());
            expect(Object.keys(PATH_STATIC_HREFS).sort()).toEqual([...keys].sort());
            // Every milestone of the registry leads somewhere the client knows.
            for (const milestone of contract.milestones) expect(MILESTONE_ROUTES, milestone.key).toContain(milestone.route);
        });

        it('has a title, a line of benefit and a toast phrase for every milestone', () => {
            for (const key of MILESTONE_KEYS) {
                for (const text of [`m.${key}`, `m.${key}.b`, `path.event.${key}`]) expect(Object.keys(ONBOARDING_TEXTS), text).toContain(text);
            }
            for (const chapter of MILESTONE_CHAPTERS) expect(Object.keys(ONBOARDING_TEXTS)).toContain(`path.chapter.${chapter}`);
            expect(contract.notification.kind).toBe('PATH_MILESTONE');
            expect(contract.notification.route).toBe('PATH');
        });
    });

    describe('views', () => {
        it('parses the example views of the registry strictly, with the hidden milestones named', () => {
            for (const [name, example] of Object.entries(contract.examples).filter(([key]) => key === 'fresh' || key === 'midway')) {
                const parsed = parseOnboardingView({ ...view, ...(example as { milestones: unknown[]; path: unknown }) });
                expect(parsed.milestones, name).toEqual((example as { milestones: unknown[] }).milestones);
                expect(parsed.path, name).toEqual((example as { path: unknown }).path);
            }
        });

        it('parses the fixture views that carry a path, the midway one included (12 visible, the rhythm hidden)', () => {
            const midway = parseOnboardingView(journey.views.pathMidway);
            expect(midway.path).toEqual({ done: 5, total: 12, hidden: ['RHYTHM_WEEK'] });
            expect(midway.milestones.map(milestone => milestone.state)).toEqual(['DONE', 'DONE', 'DONE', 'DONE', 'TODO', 'PARTIAL', 'PARTIAL', 'TODO', 'TODO', 'TODO', 'DONE', 'TODO']);
            expect(parseOnboardingView(journey.views.disabled)).toMatchObject({ milestones: [], path: null });
        });

        it('groups the visible milestones into the chapters that have any, the first with something left being the current one', () => {
            const midway = parseOnboardingView(journey.views.pathMidway);
            const chapters = pathChapters(midway.milestones);
            expect(chapters.map(chapter => [chapter.chapter, chapter.done, chapter.milestones.length, chapter.current])).toEqual([
                ['start', 3, 3, false], ['materials', 1, 3, true], ['exercises', 0, 4, false], ['rhythm', 1, 2, false]
            ]);
            // Nothing visible in a chapter: it is not listed; everything done: none is current.
            expect(pathChapters([]).length).toBe(0);
            expect(pathChapters(midway.milestones.map(milestone => ({ ...milestone, state: 'DONE' as const, progress: milestone.target, achievedAt: '2026-10-21T18:00:00Z' })))
                .some(chapter => chapter.current)).toBe(false);
        });
    });

    describe('strictness', () => {
        const midway = journey.views.pathMidway;
        const withMilestones = (change: (milestones: Record<string, unknown>[]) => void): unknown => {
            const milestones = structuredClone(midway.milestones) as Record<string, unknown>[];
            change(milestones);
            return { ...midway, milestones };
        };
        const withPath = (path: unknown): unknown => ({ ...midway, path });

        it('accepts [] and null only while onboarding is off', () => {
            expect(() => parseOnboardingView({ ...journey.views.disabled, milestones: midway.milestones })).toThrow();
            expect(() => parseOnboardingView({ ...journey.views.disabled, path: midway.path })).toThrow();
            expect(() => parseOnboardingView({ ...view, milestones: [] })).toThrow();
            expect(() => parseOnboardingView({ ...midway, path: null })).toThrow();
        });

        it.each<[string, (milestones: Record<string, unknown>[]) => void]>([
            ['an unknown key', milestones => { milestones[0]['key'] = 'NEW_ONE'; }],
            ['a repeated key', milestones => { milestones[1]['key'] = 'GOAL_TOLD'; }],
            ['keys out of the registry order', milestones => { milestones.reverse(); }],
            ['an unknown state', milestones => { milestones[0]['state'] = 'DOING'; }],
            ['DONE without a time', milestones => { milestones[0]['achievedAt'] = null; }],
            ['DONE short of its target', milestones => { milestones[11]['progress'] = 1; }],
            ['PARTIAL with no progress', milestones => { milestones[5]['progress'] = 0; }],
            ['PARTIAL at its target', milestones => { milestones[5]['progress'] = 2; }],
            ['TODO with progress', milestones => { milestones[4]['progress'] = 1; }],
            ['TODO that was achieved', milestones => { milestones[4]['achievedAt'] = '2026-10-21T18:00:00Z'; }],
            ['progress beyond the target', milestones => { milestones[6]['progress'] = 4; }],
            ['a target other than the registry\'s', milestones => { milestones[6]['target'] = 5; }],
            ['a malformed time', milestones => { milestones[0]['achievedAt'] = 'yesterday'; }],
            ['an extra member', milestones => { milestones[0]['extra'] = 1; }]
        ])('refuses %s', (_name, change) => {
            expect(() => parseOnboardingView(withMilestones(change))).toThrow();
        });

        it.each<[string, unknown]>([
            ['a wrong done', { done: 4, total: 12, hidden: ['RHYTHM_WEEK'] }],
            ['a wrong total', { done: 5, total: 13, hidden: ['RHYTHM_WEEK'] }],
            ['a hidden list that is not the rest of the registry', { done: 5, total: 12, hidden: [] }],
            ['a hidden list of the wrong key', { done: 5, total: 12, hidden: ['COMMUNITY_COPY'] }],
            ['an unknown hidden key', { done: 5, total: 12, hidden: ['NEW_ONE'] }],
            ['an extra member', { done: 5, total: 12, hidden: ['RHYTHM_WEEK'], more: true }],
            ['a path that is not an object', 'x']
        ])('refuses %s in the path', (_name, path) => {
            expect(() => parseOnboardingView(withPath(path))).toThrow();
        });

        it('refuses to hide a milestone that is always visible', () => {
            const hidden = (midway.milestones as Record<string, unknown>[]).filter(milestone => milestone['key'] !== 'GOAL_TOLD');
            expect(() => parseOnboardingView({ ...midway, milestones: hidden, path: { done: 4, total: 11, hidden: ['GOAL_TOLD', 'RHYTHM_WEEK'] } })).toThrow();
        });
    });
});
