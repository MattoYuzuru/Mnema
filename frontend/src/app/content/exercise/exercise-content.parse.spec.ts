import { clone, mechanics } from '../../features/study/study-test-data';
import { AuthoringBlock, COMPACT_SLOT, PROMPT_SLOTS, SLOT_PROFILES, allLearnerBlocks } from './exercise-content.models';
import {
    ExerciseContentError, blockProblem, exactObject, parseExerciseSpec, parseLearnerBlock, parseLearnerContent, slotProblem
} from './exercise-content.parse';

describe('Exercise content rules', () => {
    const asset = 'aaaaaaaa-0000-4000-8000-000000000001';

    it('keeps the slot profile table in line with the contract matrix', () => {
        expect(SLOT_PROFILES.PROMPT.kinds).toEqual(['TEXT', 'MATERIAL', 'IMAGE', 'AUDIO', 'VIDEO', 'YOUTUBE']);
        expect(SLOT_PROFILES.REFERENCE.textLimit).toBe(4000);
        expect(SLOT_PROFILES.COMPACT).toEqual({ kinds: ['TEXT', 'MATERIAL', 'IMAGE', 'AUDIO', 'VIDEO'], textLimit: 300, oneTextAndOneMedia: true });
        expect(PROMPT_SLOTS['CLOZE'].min).toBe(0);
        expect(PROMPT_SLOTS['MATCH'].min).toBe(0);
        expect(PROMPT_SLOTS['CHOICE'].min).toBe(1);
        expect(COMPACT_SLOT).toEqual({ profile: 'COMPACT', min: 1, max: 2 });
    });

    describe('block problems', () => {
        const problem = (block: AuthoringBlock, profile: 'PROMPT' | 'COMPACT' = 'PROMPT') => blockProblem(block, profile);

        it('measures text in UTF-16 units against the profile limit and never trims it', () => {
            expect(problem({ kind: 'TEXT', text: ' \n' })).toContain('Введите текст');
            expect(problem({ kind: 'TEXT', text: 'x'.repeat(4000) })).toBeNull();
            expect(problem({ kind: 'TEXT', text: 'x'.repeat(4001) })).toContain('4000');
            expect(problem({ kind: 'TEXT', text: 'x'.repeat(301) }, 'COMPACT')).toContain('300');
            expect(problem({ kind: 'TEXT', text: '😀'.repeat(150) }, 'COMPACT')).toBeNull();
            expect(problem({ kind: 'TEXT', text: '😀'.repeat(151) }, 'COMPACT')).toContain('300');
        });

        it('requires alternative text, an author label and valid ids or video ids', () => {
            expect(problem({ kind: 'IMAGE', assetId: asset, alt: '' })).toContain('альтернативный текст');
            expect(problem({ kind: 'IMAGE', assetId: asset, alt: 'x'.repeat(1025) })).toContain('1024');
            expect(problem({ kind: 'IMAGE', assetId: 'nope', alt: 'x' })).toContain('Выберите изображение');
            expect(problem({ kind: 'AUDIO', assetId: asset, title: '' })).toContain('Укажите название');
            expect(problem({ kind: 'AUDIO', assetId: asset, title: 'x'.repeat(1025) })).toContain('1024');
            expect(problem({ kind: 'AUDIO', assetId: 'nope', title: 'x' })).toContain('Выберите аудио');
            expect(problem({ kind: 'VIDEO', assetId: 'nope', title: 'x' })).toContain('Выберите видео');
            expect(problem({ kind: 'VIDEO', assetId: asset, title: 'x', transcript: ' ' })).toContain('Расшифровка');
            expect(problem({ kind: 'VIDEO', assetId: asset, title: 'x', transcript: 'y'.repeat(16385) })).toContain('16384');
            expect(problem({ kind: 'VIDEO', assetId: asset, title: 'x', transcript: 'y' })).toBeNull();
            expect(problem({ kind: 'MATERIAL', memberKey: asset, itemRevisionId: asset, nodeId: '' })).toContain('Выберите фрагмент');
            expect(problem({ kind: 'YOUTUBE', videoId: 'short', title: 'x' })).toContain('YouTube');
            expect(problem({ kind: 'YOUTUBE', videoId: 'dQw4w9WgXcQ', title: ' ' })).toContain('Укажите название ролика');
            expect(problem({ kind: 'YOUTUBE', videoId: 'dQw4w9WgXcQ', title: 'x'.repeat(1025) })).toContain('1024');
            expect(problem({ kind: 'YOUTUBE', videoId: 'dQw4w9WgXcQ', title: 'ok' })).toBeNull();
        });

        it('allows YouTube only where the profile does', () => {
            expect(problem({ kind: 'YOUTUBE', videoId: 'dQw4w9WgXcQ', title: 'ok' }, 'COMPACT')).toContain('недоступен');
        });
    });

    it('checks the slot size and the compact composition', () => {
        const text: AuthoringBlock = { kind: 'TEXT', text: 'a' };
        const image: AuthoringBlock = { kind: 'IMAGE', assetId: asset, alt: 'x' };
        expect(slotProblem([], PROMPT_SLOTS['SELF_CHECK'])).toContain('от 1 до 8');
        expect(slotProblem(Array(9).fill(text), PROMPT_SLOTS['SELF_CHECK'])).toContain('сейчас 9');
        expect(slotProblem([], PROMPT_SLOTS['CLOZE'])).toBeNull();
        expect(slotProblem([text, text], COMPACT_SLOT)).toContain('не больше одного текста');
        expect(slotProblem([image, image], COMPACT_SLOT)).toContain('не больше одного текста');
        expect(slotProblem([text, image], COMPACT_SLOT)).toBeNull();
        expect(slotProblem([text], { profile: 'COMPACT', min: 2, max: 2 })).toBe('Нужно блоков: 2.');
    });

    describe('strict exercise parsing', () => {
        it('accepts the largest slot sizes, rejects one asset used as two kinds and soft answers without letters', () => {
            const many = clone(mechanics['createSelfCheck'].exercise);
            many.content.prompt = Array.from({ length: 8 }, () => ({ kind: 'IMAGE', assetId: asset, alt: 'x' }));
            many.content.reference = Array.from({ length: 8 }, () => ({ kind: 'IMAGE', assetId: asset, alt: 'x' }));
            expect(() => parseExerciseSpec(many)).not.toThrow();
            const kinds = clone(mechanics['createSelfCheck'].exercise);
            kinds.content.prompt = [{ kind: 'IMAGE', assetId: asset, alt: 'x' }, { kind: 'AUDIO', assetId: asset, title: 'x' }];
            expect(() => parseExerciseSpec(kinds)).toThrowError(ExerciseContentError);
            const soft = clone(mechanics['createFreeResponseAudio'].exercise);
            soft.answerKey.matchingMode = 'SOFT'; soft.answerKey.accepted = ['!!!'];
            expect(() => parseExerciseSpec(soft)).toThrowError(ExerciseContentError);
            const rule = clone(mechanics['createFreeResponseAudio'].exercise);
            rule.answerKey.normalization = ['TRIM', 'TRIM'];
            expect(() => parseExerciseSpec(rule)).toThrowError(ExerciseContentError);
        });

        it('requires ANSWER_LENGTH blanks to have answers of one length and key blank ids to match the passage', () => {
            const cloze = clone(mechanics['createCloze'].exercise);
            cloze.answerKey.blanks[0].accepted = ['map', 'filter'];
            expect(() => parseExerciseSpec(cloze)).toThrowError(ExerciseContentError);
            const ids = clone(mechanics['createCloze'].exercise);
            ids.answerKey.blanks[1].blankId = ids.answerKey.blanks[0].blankId;
            expect(() => parseExerciseSpec(ids)).toThrowError(ExerciseContentError);
            const size = clone(mechanics['createCloze'].exercise);
            size.content.passage[3].size.length = 4;
            expect(() => parseExerciseSpec(size)).toThrowError(ExerciseContentError);
            const onlyBlanks = clone(mechanics['createCloze'].exercise);
            onlyBlanks.content.passage = onlyBlanks.content.passage.filter((segment: { kind: string }) => segment.kind === 'BLANK');
            expect(() => parseExerciseSpec(onlyBlanks)).toThrowError(ExerciseContentError);
        });

        it('requires a rubric with the three levels in contract order', () => {
            const ai = clone(mechanics['rejectedAiAssessment'].exercise);
            expect(() => parseExerciseSpec(ai)).not.toThrow();
            ai.evaluatorPolicy.rubric.levels.reverse();
            expect(() => parseExerciseSpec(ai)).toThrowError(ExerciseContentError);
            const critical = clone(mechanics['rejectedAiAssessment'].exercise);
            critical.evaluatorPolicy.rubric.criteria = [];
            expect(() => parseExerciseSpec(critical)).toThrowError(ExerciseContentError);
        });

        it('exactObject accepts only the listed keys', () => {
            expect(exactObject({ a: 1 }, ['a'])).toEqual({ a: 1 });
            expect(exactObject({ a: 1 }, ['a'], ['b'])).toEqual({ a: 1 });
            expect(() => exactObject({ a: 1, c: 1 }, ['a'], ['b'])).toThrowError(ExerciseContentError);
            expect(() => exactObject({}, ['a'])).toThrowError(ExerciseContentError);
            expect(() => exactObject([], [])).toThrowError(ExerciseContentError);
        });
    });

    describe('learner content', () => {
        it('rejects titles, unrevealed transcripts and kinds that the profile does not allow', () => {
            expect(() => parseLearnerBlock({ kind: 'AUDIO', assetId: asset, transcriptAvailable: false, transcript: 'x' }, 'PROMPT', null)).toThrowError(ExerciseContentError);
            expect(() => parseLearnerBlock({ kind: 'AUDIO', assetId: asset, transcriptAvailable: true, transcript: 'x' }, 'PROMPT', false)).toThrowError(ExerciseContentError);
            expect(() => parseLearnerBlock({ kind: 'AUDIO', assetId: asset, transcriptAvailable: true }, 'PROMPT', true)).toThrowError(ExerciseContentError);
            expect(() => parseLearnerBlock({ kind: 'AUDIO', assetId: asset, title: 'x', transcriptAvailable: false }, 'PROMPT', null)).toThrowError(ExerciseContentError);
            expect(() => parseLearnerBlock({ kind: 'MATERIAL', memberKey: asset }, 'PROMPT', null)).toThrowError(ExerciseContentError);
            expect(() => parseLearnerBlock({ kind: 'YOUTUBE', videoId: 'dQw4w9WgXcQ', title: 'x' }, 'COMPACT', null)).toThrowError(ExerciseContentError);
            expect(() => parseLearnerBlock({ kind: 'YOUTUBE', videoId: 'bad', title: 'x' }, 'PROMPT', null)).toThrowError(ExerciseContentError);
            expect(parseLearnerBlock({ kind: 'YOUTUBE', videoId: 'dQw4w9WgXcQ', title: 'x' }, 'PROMPT', null)).toEqual(
                { kind: 'YOUTUBE', videoId: 'dQw4w9WgXcQ', title: 'x' });
            expect(() => parseLearnerBlock('text', 'PROMPT', null)).toThrowError(ExerciseContentError);
        });

        it('parses the transcript revealed content and finds transcripts in every slot', () => {
            const parsed = parseLearnerContent('FREE_RESPONSE', mechanics['transcriptRevealResponse'].content, true);
            expect(allLearnerBlocks(parsed).length).toBe(2);
            const choice = parseLearnerContent('CHOICE', mechanics['presentations']['choice'].content, false);
            expect(allLearnerBlocks(choice).length).toBe(7);
            const match = parseLearnerContent('MATCH', mechanics['presentations']['match'].content, false);
            expect(allLearnerBlocks(match).length).toBe(9);
            expect(allLearnerBlocks(parseLearnerContent('SELF_CHECK', mechanics['presentations']['selfCheck'].content, false)).length).toBe(4);
            const cloze = parseLearnerContent('CLOZE', mechanics['presentations']['cloze'].content, false);
            expect(allLearnerBlocks(cloze).length).toBe(1);
        });

        it('rejects content of the wrong shape for the type and malformed cloze or match content', () => {
            const choice = mechanics['presentations']['choice'].content;
            expect(() => parseLearnerContent('MATCH', choice, false)).toThrowError(ExerciseContentError);
            const cloze = clone(mechanics['presentations']['cloze'].content);
            cloze.passage[1].size = { mode: 'FIXED', length: 3 };
            expect(() => parseLearnerContent('CLOZE', cloze, false)).toThrowError(ExerciseContentError);
            const answerLength = clone(mechanics['presentations']['cloze'].content);
            answerLength.passage[1].size = { mode: 'ANSWER_LENGTH' };
            expect(() => parseLearnerContent('CLOZE', answerLength, false)).toThrowError(ExerciseContentError);
            const match = clone(mechanics['presentations']['match'].content);
            match.right.pop();
            expect(() => parseLearnerContent('MATCH', match, false)).toThrowError(ExerciseContentError);
            const duplicate = clone(mechanics['presentations']['match'].content);
            duplicate.right[0].itemId = duplicate.left[0].itemId;
            expect(() => parseLearnerContent('MATCH', duplicate, false)).toThrowError(ExerciseContentError);
            const options = clone(choice);
            options.options[1].optionId = options.options[0].optionId;
            expect(() => parseLearnerContent('CHOICE', options, false)).toThrowError(ExerciseContentError);
        });
    });
});
