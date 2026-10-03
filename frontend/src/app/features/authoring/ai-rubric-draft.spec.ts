import { AiRubric } from '../../content/exercise/exercise-content.models';
import { clone, mechanics } from '../study/study-test-data';
import {
    AiRubricDraft, emptyRubric, newCriterion, nextTier, rubricDraft, rubricErrors, rubricSpec, tierCount, tierRange
} from './ai-rubric-draft';
import { buildSpec, draftsFromDetail, emptyDrafts, newAnswer, validateDraft } from './exercise-draft';
import { ExerciseDetail } from './exercise.models';

describe('AI rubric draft (rubric v1)', () => {
    const wire = (): AiRubric => clone(mechanics['rejectedAiAssessment'].exercise.evaluatorPolicy.rubric);
    const valid = (): AiRubricDraft => rubricDraft(wire());

    it('round-trips the contract fixture without reordering or changing a criterion', () => {
        const rubric = wire();
        expect(rubricSpec(rubricDraft(rubric))).toEqual(rubric);
    });

    it('starts with two essential points and one detail so only the words are missing', () => {
        const draft = emptyRubric();
        expect(['CORE', 'DETAIL', 'TERM'].map(tier => tierCount(draft, tier as 'CORE'))).toEqual([2, 1, 0]);
        expect(draft.criteria.every(criterion => criterion.description === '')).toBe(true);
        expect(new Set(draft.criteria.map(criterion => criterion.criterionId)).size).toBe(3);
        const errors = rubricErrors(draft);
        expect(Object.keys(errors).sort()).toEqual(['rubric:criterion:' + draft.criteria[0].criterionId, 'rubric:criterion:' + draft.criteria[1].criterionId,
            'rubric:criterion:' + draft.criteria[2].criterionId, 'rubric:reference'].sort());
    });

    it('accepts a valid rubric and reports each rule where it is broken', () => {
        expect(rubricErrors(valid())).toEqual({});
        const base = valid();
        const without = (tier: 'CORE' | 'DETAIL' | 'TERM') => ({ ...base, criteria: base.criteria.filter(criterion => criterion.tier !== tier) });
        expect(rubricErrors(without('DETAIL'))['rubric:criteria']).toContain('«Детали» (0)');
        expect(rubricErrors(without('CORE'))['rubric:criteria']).toContain('«Суть» (0)');
        const manyTerms = { ...base, criteria: [...base.criteria, newCriterion('TERM'), newCriterion('TERM')].map((criterion, index) => ({ ...criterion, description: `п${index}` })) };
        expect(rubricErrors(manyTerms)['rubric:criteria']).toContain('«Термины» (3)');
        expect(rubricErrors({ ...base, referenceAnswer: 'я'.repeat(4001) })['rubric:reference']).toContain('4001');
        expect(rubricErrors({ ...base, referenceAnswer: ' ' })['rubric:reference']).toContain('Напишите');
        const long = { ...base, criteria: base.criteria.map((criterion, index) => index === 0 ? { ...criterion, description: 'я'.repeat(501) } : criterion) };
        expect(rubricErrors(long)[`rubric:criterion:${base.criteria[0].criterionId}`]).toContain('500');
        const row = (value: string) => ({ id: value + Math.random(), value });
        expect(rubricErrors({ ...base, misconceptions: [row('')] })['rubric:misconceptions']).toContain('пустые');
        expect(rubricErrors({ ...base, misconceptions: Array.from({ length: 11 }, (_, i) => row(`о${i}`)) })['rubric:misconceptions']).toContain('10');
        expect(rubricErrors({ ...base, acceptableTerms: [row('я'.repeat(81))] })['rubric:terms']).toContain('80');
        expect(rubricErrors({ ...base, acceptableTerms: Array.from({ length: 31 }, (_, i) => row(`т${i}`)) })['rubric:terms']).toContain('30');
    });

    it('suggests the tier a new point most likely needs', () => {
        const draft = valid();
        expect(nextTier({ ...draft, criteria: draft.criteria.slice(1, 3) })).toBe('CORE');
        expect(nextTier({ ...draft, criteria: [draft.criteria[0], draft.criteria[1]] })).toBe('DETAIL');
        expect(tierRange('CORE')).toBe('2–3');
        expect(tierRange('TERM')).toBe('0–2');
    });

    describe('inside the exercise draft', () => {
        const detail = (): ExerciseDetail => ({ exerciseId: 'e', exerciseRevisionId: 'r', exerciseVersion: '1', ordinal: 0, createdAt: 'a', updatedAt: 'b',
            objective: mechanics['exerciseDetail'].objective, deckId: 'c', deckRevisionId: 'd', deckVersion: '1',
            ...mechanics['rejectedAiAssessment'].exercise } as ExerciseDetail);
        const context = { document: { formatVersion: 1, root: { id: 'x', type: 'doc', version: 1, attrs: {}, content: [] } } as never, memberKey: 'm', itemRevisionId: 'i', projections: [] };

        it('does not ask for the deterministic answer list while AI checking is on', () => {
            const drafts = emptyDrafts();
            const ai = { ...drafts, FREE_RESPONSE: { ...drafts.FREE_RESPONSE, prompt: [{ kind: 'TEXT' as const, text: 'Что такое X?' }], aiRubric: valid() } };
            expect(validateDraft('FREE_RESPONSE', ai, context)).toEqual({});
            expect(validateDraft('FREE_RESPONSE', { ...ai, FREE_RESPONSE: { ...ai.FREE_RESPONSE, aiRubric: null } }, context)['accepted']).toBeDefined();
        });

        it('keeps a valid typed answer key and otherwise derives a strict one from the reference answer, cut at 512 characters', () => {
            const drafts = emptyDrafts();
            const subject = { memberKey: 'm', itemRevisionId: 'i' };
            const rubric = { ...valid(), referenceAnswer: 'ж'.repeat(600) };
            const derived = buildSpec('FREE_RESPONSE', { ...drafts, FREE_RESPONSE: { ...drafts.FREE_RESPONSE, aiRubric: rubric } }, subject, true);
            expect(derived.answerKey).toEqual({ kind: 'TEXT', accepted: ['ж'.repeat(512)], normalization: ['UNICODE_NFC', 'TRIM', 'CASE_FOLD'], matchingMode: 'STRICT' });
            const typed = { ...drafts.FREE_RESPONSE, aiRubric: rubric, answer: { ...newAnswer(['свой ответ']), matchingMode: 'SOFT' as const } };
            expect(buildSpec('FREE_RESPONSE', { ...drafts, FREE_RESPONSE: typed }, subject, true).answerKey)
                .toMatchObject({ accepted: ['свой ответ'], matchingMode: 'SOFT' });
        });

        it('loads a published rubric and rebuilds exactly the same specification', () => {
            const loaded = draftsFromDetail(detail());
            expect(loaded.FREE_RESPONSE.aiRubric?.criteria.length).toBe(4);
            const rebuilt = buildSpec('FREE_RESPONSE', loaded, mechanics['rejectedAiAssessment'].exercise.subject, true);
            expect(rebuilt).toEqual(mechanics['rejectedAiAssessment'].exercise);
        });
    });
});
