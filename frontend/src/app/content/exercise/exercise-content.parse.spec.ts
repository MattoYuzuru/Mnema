import { clone, mechanics } from '../../features/study/study-test-data';
import { AuthoringBlock, COMPACT_SLOT, PROMPT_SLOTS, SEQUENCE_SLOT, SLOT_PROFILES, allLearnerBlocks, categoryLabelKey, distinguishableItems, visibleBlocksKey } from './exercise-content.models';
import { ExerciseContentError, blockProblem, exactObject, parseExerciseSpec, parseLearnerBlock, parseLearnerContent, slotProblem } from './exercise-content.parse';

describe('Exercise content rules', () => {
    const asset = 'aaaaaaaa-0000-4000-8000-000000000001';

    it('keeps the slot profile table in line with the contract matrix', () => {
        expect(SLOT_PROFILES.PROMPT.kinds).toEqual(['TEXT', 'MATERIAL', 'IMAGE', 'AUDIO', 'VIDEO', 'YOUTUBE']);
        expect(SLOT_PROFILES.REFERENCE.textLimit).toBe(4000);
        expect(SLOT_PROFILES.COMPACT).toEqual({ kinds: ['TEXT', 'MATERIAL', 'IMAGE', 'AUDIO', 'VIDEO'], textLimit: 300, oneTextAndOneMedia: true });
        expect(SLOT_PROFILES.SEQUENCE).toEqual({ kinds: ['TEXT', 'MATERIAL', 'IMAGE', 'AUDIO', 'VIDEO'], textLimit: 1000, oneTextAndOneMedia: true });
        expect(SEQUENCE_SLOT).toEqual({ profile: 'SEQUENCE', min: 1, max: 2 });
        expect(PROMPT_SLOTS['ORDER']).toEqual({ profile: 'PROMPT', min: 0, max: 8 });
        expect(PROMPT_SLOTS['CATEGORIZE']).toEqual({ profile: 'PROMPT', min: 0, max: 8 });
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

        it('measures a SEQUENCE item up to 1000 units and keeps newlines and indentation', () => {
            expect(blockProblem({ kind: 'TEXT', text: 'for (;;) {\n    x++;\n}' }, 'SEQUENCE')).toBeNull();
            expect(blockProblem({ kind: 'TEXT', text: 'x'.repeat(1000) }, 'SEQUENCE')).toBeNull();
            expect(blockProblem({ kind: 'TEXT', text: 'x'.repeat(1001) }, 'SEQUENCE')).toContain('1000');
            expect(blockProblem({ kind: 'YOUTUBE', videoId: 'dQw4w9WgXcQ', title: 'ok' }, 'SEQUENCE')).toContain('недоступен');
            expect(slotProblem([{ kind: 'TEXT' }, { kind: 'TEXT' }], SEQUENCE_SLOT)).toContain('не больше одного текста');
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
            soft.answerKey.matchingMode = 'SOFT';
            soft.answerKey.accepted = ['!!!'];
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
            onlyBlanks.content.passage = onlyBlanks.content.passage.filter((segment: {
                kind: string;
            }) => segment.kind === 'BLANK');
            expect(() => parseExerciseSpec(onlyBlanks)).toThrowError(ExerciseContentError);
        });

        it('accepts rubric v1 and rejects the old critical/levels shape', () => {
            const ai = clone(mechanics['rejectedAiAssessment'].exercise);
            expect(parseExerciseSpec(ai)).toEqual(ai);
            const old = clone(ai);
            old.evaluatorPolicy.rubric = { referenceAnswer: 'x', criteria: [{ criterionId: ai.evaluatorPolicy.rubric.criteria[0].criterionId, description: 'd', critical: true }],
                levels: [{ level: 'COMPLETE', description: 'a' }, { level: 'PARTIAL', description: 'b' }, { level: 'INSUFFICIENT', description: 'c' }] };
            expect(() => parseExerciseSpec(old)).toThrowError(ExerciseContentError);
        });

        it('enforces the rubric bounds: 2-3 essential, 1-4 detail, 0-2 terminology points, weights 1-3, list lengths', () => {
            const rubric = () => {
                const ai = clone(mechanics['rejectedAiAssessment'].exercise);
                return { ai, rubric: ai.evaluatorPolicy.rubric as {
                    criteria: { criterionId: string; description: string; tier: string; weight: number }[];
                    misconceptions: string[]; acceptableTerms: string[]; referenceAnswer: string; extra?: unknown; } };
            };
            const reject = (change: (value: ReturnType<typeof rubric>['rubric']) => void) => {
                const sample = rubric();
                change(sample.rubric);
                expect(() => parseExerciseSpec(sample.ai)).toThrowError(ExerciseContentError);
            };
            const point = (tier: string, index: number) => ({ criterionId: `c0000000-0000-4000-8000-0000000001${String(index).padStart(2, '0')}`,
                description: `Пункт ${index}`, tier, weight: 1 });
            reject(value => { value.criteria = value.criteria.filter(entry => entry.tier !== 'DETAIL'); });
            reject(value => { value.criteria = value.criteria.filter(entry => entry.tier !== 'CORE').concat(point('DETAIL', 7)); });
            reject(value => { value.criteria.push(point('CORE', 1), point('CORE', 2)); });
            reject(value => { value.criteria.push(point('TERM', 3), point('TERM', 4)); });
            reject(value => { value.criteria = [...value.criteria, ...[1, 2, 3, 4, 5].map(index => point('DETAIL', index))]; });
            reject(value => { value.criteria[0].weight = 4; });
            reject(value => { value.criteria[0].weight = 0; });
            reject(value => { value.criteria[0].tier = 'OPTIONAL'; });
            reject(value => { value.criteria[1].criterionId = value.criteria[0].criterionId; });
            reject(value => { value.criteria[0].description = '   '; });
            reject(value => { value.criteria[0].description = 'я'.repeat(501); });
            reject(value => { value.referenceAnswer = ' '; });
            reject(value => { value.referenceAnswer = 'я'.repeat(4001); });
            reject(value => { value.misconceptions = Array.from({ length: 11 }, (_, index) => `ошибка ${index}`); });
            reject(value => { value.misconceptions = ['я'.repeat(301)]; });
            reject(value => { value.acceptableTerms = Array.from({ length: 31 }, (_, index) => `термин ${index}`); });
            reject(value => { value.acceptableTerms = ['']; });
            reject(value => { delete (value as Partial<typeof value>).misconceptions; });
            reject(value => { value.extra = true; });
            const edge = rubric();
            edge.rubric.misconceptions = Array.from({ length: 10 }, (_, index) => `ошибка ${index}`);
            edge.rubric.acceptableTerms = Array.from({ length: 30 }, () => 'т'.repeat(80));
            expect(() => parseExerciseSpec(edge.ai)).not.toThrow();
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
            expect(parseLearnerBlock({ kind: 'YOUTUBE', videoId: 'dQw4w9WgXcQ', title: 'x' }, 'PROMPT', null)).toEqual({ kind: 'YOUTUBE', videoId: 'dQw4w9WgXcQ', title: 'x' });
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

describe('ORDER and CATEGORIZE content', () => {
    it('parses both authoring fixtures and keeps the authored sequence and assignment order verbatim', () => {
        const order = parseExerciseSpec(mechanics['createOrder'].exercise);
        expect(order).toEqual(mechanics['createOrder'].exercise);
        expect(order.type === 'ORDER' && order.answerKey.sequence).toEqual(order.type === 'ORDER' ? order.content.items.map(item => item.itemId) : []);
        const categorize = parseExerciseSpec(mechanics['createCategorize'].exercise);
        expect(categorize).toEqual(mechanics['createCategorize'].exercise);
    });

    it('parses the issued presentations as learner content without any key', () => {
        for (const name of ['order', 'categorize'] as const) {
            const presentation = mechanics['presentations'][name];
            const parsed = parseLearnerContent(presentation.type, presentation.content, false);
            expect(parsed.content).toEqual(presentation.content);
            expect(JSON.stringify(parsed)).not.toContain('sequence');
            expect(JSON.stringify(parsed)).not.toContain('assignments');
        }
        const learner = parseLearnerContent('CATEGORIZE', mechanics['presentations']['categorize'].content, false);
        expect(allLearnerBlocks(learner).some(block => block.kind === 'AUDIO')).toBe(true);
    });

    it('folds group labels like the contract: NFC, edge whitespace and format characters, then case', () => {
        const same = (a: string, b: string) => expect(categoryLabelKey(a), `${a} / ${b}`).toBe(categoryLabelKey(b));
        same('  Глагол ', 'ГЛАГОЛ');
        same('Straße', 'STRASSE');
        same('Σ', 'ς');
        same('Cafe\u0301', 'caf\u00e9');
        same('\u00a0Глагол\u3000', 'глагол');
        same('\u200bГлагол\u200b', 'Глагол');
        expect(categoryLabelKey('Глагол')).not.toBe(categoryLabelKey('Глаголы'));
        expect(categoryLabelKey('Гла\u200bгол')).not.toBe(categoryLabelKey('Глагол')); // only the edges are trimmed
        expect(categoryLabelKey(' \u200b\u00a0\u3000')).toBe(''); // no visible character
    });

    it('rejects a group label without a visible character or one that folds onto another label, and keeps labels verbatim', () => {
        const base = mechanics['createCategorize'].exercise;
        for (const label of ['\u200b', '\u00a0\u3000', ' ']) {
            const spec = clone(base);
            spec.content.categories[0].label = label;
            expect(() => parseExerciseSpec(spec), JSON.stringify(label)).toThrowError(ExerciseContentError);
        }
        for (const [first, second] of [['Straße', 'STRASSE'], ['Σ', 'ς'], ['Caf\u00e9', 'Cafe\u0301'], ['Глагол', '\u200bГЛАГОЛ\u00a0']]) {
            const spec = clone(base);
            spec.content.categories[0].label = first;
            spec.content.categories[1].label = second;
            expect(() => parseExerciseSpec(spec), `${first} / ${second}`).toThrowError(ExerciseContentError);
        }
        const verbatim = clone(base);
        verbatim.content.categories[0].label = ' Существительное ';
        expect(parseExerciseSpec(verbatim).type === 'CATEGORIZE' && parseExerciseSpec(verbatim).content).toEqual(verbatim.content);
    });

    it('tells items apart by what the learner sees: identical blocks count once, author-only titles are ignored', () => {
        const text = (value: string) => ({ blocks: [{ kind: 'TEXT' as const, text: value }] });
        expect(visibleBlocksKey(text('очень').blocks)).toBe(visibleBlocksKey(text('очень').blocks));
        expect(visibleBlocksKey(text('очень').blocks)).not.toBe(visibleBlocksKey(text('Очень').blocks));
        const audio = (title: string, transcript?: string) => [{ kind: 'AUDIO' as const, assetId: 'aaaaaaaa-0000-4000-8000-000000000001', title,
                ...(transcript === undefined ? {} : { transcript }) }];
        expect(visibleBlocksKey(audio('Запись 1'))).toBe(visibleBlocksKey(audio('Другое название')));
        expect(visibleBlocksKey(audio('x'))).not.toBe(visibleBlocksKey(audio('x', 'расшифровка'))); // the learner can ask for one
        const material = (nodeId: string) => [{ kind: 'MATERIAL' as const, memberKey: 'm', itemRevisionId: 'r', nodeId }];
        expect(visibleBlocksKey(material('a'))).not.toBe(visibleBlocksKey(material('b'))); // preview: never more lenient
        expect(visibleBlocksKey(material('a'), () => 'один текст')).toBe(visibleBlocksKey(material('b'), () => 'один текст'));
        expect(visibleBlocksKey(material('a'), () => 'один текст')).toBe(visibleBlocksKey(text('один текст').blocks));
        expect(distinguishableItems([text('а'), text('а'), text('а')])).toBe(1);
        expect(distinguishableItems([text('а'), text('а'), text('б')])).toBe(2);
    });

    it('rejects an ORDER whose items are all indistinguishable', () => {
        const spec = clone(mechanics['createOrder'].exercise);
        spec.content.items = spec.content.items.slice(1, 3); // two identical «очень» tiles
        spec.answerKey.sequence = spec.content.items.map((entry: {
            itemId: string;
        }) => entry.itemId);
        expect(() => parseExerciseSpec(spec)).toThrowError(ExerciseContentError);
        spec.content.items[1].blocks = [{ kind: 'TEXT', text: 'совсем' }];
        expect(() => parseExerciseSpec(spec)).not.toThrow();
    });
});
