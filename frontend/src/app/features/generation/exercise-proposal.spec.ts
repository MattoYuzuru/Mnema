import { parseArtifactDetail } from './generation.models';
import { examples } from './generation-test-data';
import { artifactIds, choice, createCommand, exerciseDetail, materialIds, nodeIds, QUOTE, selfCheck } from './exercise-test-data';
import { proposalPresentation, quoteContext, readProposal } from './exercise-proposal';

describe('exercise proposal', () => {
    /** `'absent'`: the display the client would have read as none (the parser refuses an exercise payload without it, so it is dropped after); `'none'`: no display at all (an item or an empty artifact). */
    const detail = (command: Record<string, unknown> = createCommand(selfCheck()), overrides: Record<string, unknown> = {}, display: Record<string, unknown> | null | 'absent' | 'none' = null) => {
        const shown = display === 'absent' ? { mechanic: 'CHOICE', objectiveTitle: 'x', quotes: {} } : display === 'none' ? 'absent' : display;
        const parsed = parseArtifactDetail(exerciseDetail(artifactIds[0]!, 0, command, overrides, shown));
        return display === 'absent' ? { ...parsed, display: null } : parsed;
    };

    it('reads the stored command, the objective title and the quotes of the pinned revision', () => {
        const proposal = readProposal(detail())!;
        expect(proposal.mechanic).toBe('SELF_CHECK');
        expect(proposal.objective).toEqual({ operation: 'create', title: 'Выбор между Seq Scan и Index Scan' });
        expect(proposal.objectiveTitle).toBe('Выбор между Seq Scan и Index Scan');
        expect(proposal.exercise.subject.memberKey).toBe(materialIds.first);
        expect(proposal.quotes).toEqual({ [nodeIds.first]: QUOTE });
        expect(proposal.artifactId).toBe(artifactIds[0]);
    });

    it('falls back to the command when the server sends no display: the objective title, and no quotes', () => {
        const proposal = readProposal(detail(createCommand(choice(), 'Своя цель'), {}, 'absent'))!;
        expect(proposal.objectiveTitle).toBe('Своя цель');
        expect(proposal.quotes).toEqual({});
        const reuse = readProposal(detail({ objective: { operation: 'reuse', objectiveId: '77777777-7777-4777-8777-777777777771', objectiveRevisionId: '77777777-7777-4777-8777-777777777773' },
            exercise: choice() }, {}, 'absent'))!;
        expect(reuse.objectiveTitle).toBe('');
    });

    it('is none for an artifact without a revision, an item payload, or a command that does not read', () => {
        expect(readProposal(detail(undefined, { revision: null, currentRevisionId: null, state: 'QUEUED' }, 'none'))).toBeNull();
        expect(readProposal(detail(createCommand(choice()), { revision: { ...exerciseDetail(artifactIds[0]!, 0)['revision'], payload: { kind: 'EXERCISE_COMMAND', command: 'x' } } }))).toBeNull();
        const itemPayload = examples['artifactDetailItem'].revision.payload;
        expect(readProposal(detail(createCommand(choice()), { revision: { ...exerciseDetail(artifactIds[0]!, 0)['revision'], payload: itemPayload } }, 'none'))).toBeNull();
        expect(readProposal(detail({ objective: { operation: 'create', title: 'x' }, exercise: { type: 'NOPE' } }, {}, 'absent'))).toBeNull();
        expect(readProposal(detail({ objective: { operation: 'bad' }, exercise: choice() }, {}, 'absent'))).toBeNull();
    });

    it('reads the command and ignores members of it that it does not know', () => {
        expect(readProposal(detail({ objective: { operation: 'create', title: 'x' }, exercise: choice(), extra: 'x' }, {}, 'absent'))).toMatchObject({ mechanic: 'CHOICE' });
    });

    it('turns the quotes into projections with a one-line label', () => {
        const long = 'слово '.repeat(40);
        const { projections } = quoteContext({ [nodeIds.first]: 'Первая\nстрока', [nodeIds.second]: long });
        expect(projections[0]).toEqual({ nodeId: nodeIds.first, text: 'Первая\nстрока', label: 'Первая строка' });
        expect(projections[1]!.label.endsWith('…')).toBe(true);
        expect(projections[1]!.label.length).toBeLessThanOrEqual(78);
    });

    it('plays a proposal like a learner sees it: the quoted material resolved, the answer checked by the preview endpoint', () => {
        const proposal = readProposal(detail())!;
        const presentation = proposalPresentation(proposal);
        expect(presentation.mode).toBe('PROPOSAL');
        expect(presentation.blockedReason).toBeNull();
        expect(presentation.key).toBe(`proposal:${artifactIds[0]}:${proposal.revisionId}`);
        expect(presentation.exercise).toMatchObject({ type: 'SELF_CHECK' });
        expect(presentation.exercise).not.toHaveProperty('subject');
        const hidden = presentation.learner(false);
        expect(hidden).toMatchObject({ type: 'SELF_CHECK', content: { prompt: [{ kind: 'TEXT', text: 'Когда планировщик выберет Seq Scan?' }] } });
        expect(JSON.stringify(hidden)).toContain(QUOTE);
    });
});
