import contract from '../../../../../contracts/onboarding/journey.json';
import { JourneyAction } from '../../core/onboarding/onboarding.models';
import { FakeJourneyStore, RHYTHM_VIEW } from './onboarding-test-data';

const STATES = contract.constants.states;
const ACTIONS = contract.constants.actions as JourneyAction[];
const TABLE = contract.transitions as unknown as Record<JourneyAction, { from: Record<string, string>; stepKey: string }>;

/** The stand-in the specs follow a learner with must refuse what the server refuses, or a spec proves a flow the server would not allow. */
describe('the fake journey store', () => {
    const storeIn = (state: string, branch: string | null = null): FakeJourneyStore =>
        new FakeJourneyStore({ state: state as never, branch: branch as never, eligible: true, autoOffer: true });

    it.each(STATES.flatMap(state => ACTIONS.map(action => [state, action] as const)))('follows the transition table: %s + %s', async (state, action) => {
        const rule = TABLE[action];
        const stepKey = rule.stepKey === 'required' ? 'welcome' : undefined;
        const result = await storeIn(state).act(action, stepKey === undefined ? {} : { stepKey });
        const to = rule.from[state];
        if (to === undefined) expect(result.status, 'refused like the 409').toBe('refused');
        else {
            expect(result.status).toBe('applied');
            if (result.status === 'applied') expect(result.view.journey?.state).toBe(to);
        }
    });

    it('moves the version with every applied command and not with a refused one', async () => {
        const store = storeIn('OFFERED');
        await store.act('START', { stepKey: 'survey.goal' });
        expect(store.journey()?.rowVersion).toBe('1');
        await store.act('START', { stepKey: 'survey.goal' });
        expect(store.journey()?.rowVersion).toBe('1');
    });

    it('fails a command the contract calls invalid: an unknown step, a step of another branch, a field the action forbids', async () => {
        const inProgress = (): FakeJourneyStore => storeIn('IN_PROGRESS', 'COMMUNITY');
        await expect(inProgress().act('STEP', { stepKey: 'tour.unknown' as never })).resolves.toEqual({ status: 'failed' });
        await expect(inProgress().act('STEP', { stepKey: 'own.material' })).resolves.toEqual({ status: 'failed' });
        await expect(inProgress().act('STEP', { stepKey: 'community.add', branch: 'OWN' })).resolves.toEqual({ status: 'failed' });
        // «choose» belongs to no branch: a branch sent with it is refused.
        await expect(inProgress().act('STEP', { stepKey: 'choose', branch: 'OWN' })).resolves.toEqual({ status: 'failed' });
        await expect(inProgress().act('STEP', { stepKey: 'choose' })).resolves.toMatchObject({ status: 'applied' });
        await expect(inProgress().act('STEP')).resolves.toEqual({ status: 'failed' });
        await expect(storeIn('OFFERED').act('START', { stepKey: 'survey.goal', branch: 'OWN' })).resolves.toEqual({ status: 'failed' });
        await expect(storeIn('OFFERED').act('SKIP', { stepKey: 'welcome' })).resolves.toEqual({ status: 'failed' });
        await expect(inProgress().act('STEP', { stepKey: 'own.details', branch: 'OWN' })).resolves.toMatchObject({ status: 'applied' });
    });

    it('sets and clears what the table says: a replay counts and clears the branch, a finish sets the day, an undo clears it', async () => {
        const store = storeIn('COMPLETED', 'OWN');
        await store.act('REPLAY', { stepKey: 'choose' });
        expect(store.journey()).toMatchObject({ state: 'IN_PROGRESS', branch: null, finishedAt: null, replayCount: 1, stepKey: 'choose' });
        await store.act('COMPLETE');
        expect(store.journey()?.finishedAt).not.toBeNull();
        await store.act('UNDO_FINISH');
        expect(store.journey()).toMatchObject({ state: 'PAUSED', finishedAt: null });
    });

    it('takes the preferences of an account with no journey yet and makes its row (version 0 -> 1), like the server since OB-401', async () => {
        const store = storeIn('NOT_OFFERED');
        await expect(store.setPreferences({ experience: 'NEW' })).resolves.toMatchObject({ status: 'applied' });
        expect(store.journey()).toMatchObject({ state: 'NOT_OFFERED', experience: 'NEW' });
        expect(store.rejections).toEqual([]);
    });

    it('moves the target of the rhythm with the weekly target, and back to the default when it is cleared', async () => {
        const store = new FakeJourneyStore({}, { rhythm: RHYTHM_VIEW.rhythm });
        await store.setPreferences({ weeklyTarget: 2 });
        expect(store.view()?.rhythm?.target).toBe(2);
        await store.setPreferences({ weeklyTarget: null });
        expect(store.view()?.rhythm?.target).toBe(3);
        await store.setPreferences({ experience: 'NEW' });
        expect(store.view()?.rhythm?.target).toBe(3);
    });

    it('rejects a weekly target outside the contract and keeps the journey as it was', async () => {
        const store = storeIn('IN_PROGRESS');
        await expect(store.setPreferences({ weeklyTarget: 8 })).resolves.toEqual({ status: 'failed' });
        await expect(store.setPreferences({ weeklyTarget: 5 })).resolves.toMatchObject({ status: 'applied' });
        expect(store.journey()?.weeklyTarget).toBe(5);
    });
});
