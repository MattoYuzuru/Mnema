import { AuthoringBlock, LIMITS, previewExerciseOf } from '../../content/exercise/exercise-content.models';
import { parseExerciseSpec } from '../../content/exercise/exercise-content.parse';
import { NativeDocument } from '../../content/native-document';
import { mechanics } from '../study/study-test-data';
import { ExerciseDrafts, SlotContext, buildSpec, carryPrompt, choiceSelectionProblem, clozePassage, draftsFromDetail, emptyDrafts, isPristine, learnerContent, materialText, mechanicSpecificData, newBlank, newCategorizeItem, newCategory, newOrderItem, newPair, slotErrorMessage, validateDraft } from './exercise-draft';
import { ExerciseDetail } from './exercise.models';

describe('Exercise drafts', () => {
    const subject = mechanics['createSelfCheck'].exercise.subject;
    const nodeId = '00000000-0000-4000-8000-000000000003';
    const context: SlotContext = {
        document: { formatVersion: 1, root: { id: nodeId, type: 'doc', version: 1, attrs: {}, content: [] } } as unknown as NativeDocument,
        memberKey: subject.memberKey, itemRevisionId: subject.itemRevisionId,
        projections: [{ nodeId, label: 'Ядро', text: 'Ядро хранит ДНК' }, { nodeId: '00000000-0000-4000-8000-000000000004', label: 'Длинный', text: 'я'.repeat(301) }]
    };
    const text = (value: string): AuthoringBlock => ({ kind: 'TEXT', text: value });

    function detailFor(name: string): ExerciseDetail {
        return { exerciseId: 'a', exerciseRevisionId: 'b', exerciseVersion: '0', ordinal: 0, createdAt: '', updatedAt: '',
            objective: mechanics['exerciseDetail'].objective, deckId: 'c', deckRevisionId: 'd', deckVersion: '1',
            ...mechanics[name].exercise } as ExerciseDetail;
    }

    it('starts with a valid-shaped but empty draft for every mechanic', () => {
        const drafts = emptyDrafts();
        expect(drafts.CHOICE.options.length).toBe(2);
        expect(drafts.MATCH.pairs.length).toBe(2);
        expect(drafts.CLOZE.texts).toEqual(['']);
        expect(drafts.FREE_RESPONSE.aiRubric).toBeNull();
        expect(drafts.FREE_RESPONSE.responseInput).toBe('TEXT');
        for (const type of ['SELF_CHECK', 'FREE_RESPONSE', 'CLOZE', 'CHOICE', 'MATCH', 'ORDER', 'CATEGORIZE'] as const) {
            expect(Object.keys(validateDraft(type, drafts, context)).length).toBeGreaterThan(0);
        }
    });

    it('round-trips every fixture through the drafts without changing the specification', () => {
        for (const name of ['createSelfCheck', 'createFreeResponseAudio', 'createCloze', 'createChoiceVideoMultiple', 'createMatchMixed', 'createOrder', 'createCategorize', 'rejectedAiAssessment']) {
            const detail = detailFor(name);
            const rebuilt = buildSpec(detail.type, draftsFromDetail(detail), detail.subject, detail.enabled);
            expect(rebuilt).toEqual(mechanics[name].exercise);
            expect(validateDraft(detail.type, draftsFromDetail(detail), context)).toEqual({});
        }
    });

    it('turns a passage into text and blank segments and back without trimming or losing text', () => {
        const detail = detailFor('createCloze');
        const drafts = draftsFromDetail(detail);
        expect(drafts.CLOZE.texts).toEqual(['list.stream()\n    .', '(x -> x * 2)\n    .', '();\n// map и map: одинаковые слова, разные пропуски — ', '']);
        expect(drafts.CLOZE.blanks.map(blank => blank.answer.rows[0].value)).toEqual(['map', 'toList', 'map']);
        expect(clozePassage(drafts.CLOZE)).toEqual(mechanics['createCloze'].exercise.content.passage);
        const adjacent = { ...drafts.CLOZE, texts: ['', '', 'x'], blanks: drafts.CLOZE.blanks.slice(0, 2) };
        expect(clozePassage(adjacent).map(segment => segment.kind)).toEqual(['BLANK', 'BLANK', 'TEXT']);
    });

    describe('validation', () => {
        it('reports slot, block and material problems with a position and never truncates', () => {
            const slot = { profile: 'PROMPT' as const, min: 1, max: 2 };
            expect(slotErrorMessage([], slot, context)).toContain('от 1 до 2');
            expect(slotErrorMessage([text('a'), text(' ')], slot, context)).toContain('Блок 2');
            expect(slotErrorMessage([{ kind: 'MATERIAL', memberKey: subject.memberKey, itemRevisionId: subject.itemRevisionId,
                    nodeId: '00000000-0000-4000-8000-000000000004' }], { profile: 'COMPACT', min: 1, max: 2 }, context)).toContain('длиннее 300');
            expect(slotErrorMessage([{ kind: 'MATERIAL', memberKey: subject.memberKey, itemRevisionId: subject.itemRevisionId, nodeId }], { profile: 'COMPACT', min: 1, max: 2 }, context)).toBeNull();
            expect(materialText({ kind: 'MATERIAL', memberKey: subject.memberKey, itemRevisionId: subject.itemRevisionId, nodeId }, context)).toBe('Ядро хранит ДНК');
            expect(materialText(text('x'), context)).toBeNull();
        });

        it('validates free-response alternatives, rules and soft matching', () => {
            const drafts = emptyDrafts();
            const valid = { ...drafts.FREE_RESPONSE, prompt: [text('q')] };
            const check = (answer: Partial<typeof valid.answer>) => validateDraft('FREE_RESPONSE', { ...drafts, FREE_RESPONSE: { ...valid, answer: { ...valid.answer, ...answer } } }, context)['accepted'];
            expect(check({ rows: [{ id: 'a', value: 'x' }] })).toBeUndefined();
            expect(check({ rows: [] })).toContain('от 1 до 20');
            expect(check({ rows: [{ id: 'a', value: ' ' }] })).toContain('пустые');
            expect(check({ rows: [{ id: 'a', value: 'x'.repeat(513) }] })).toContain('512');
            expect(check({ rows: [{ id: 'a', value: 'x' }, { id: 'b', value: 'x' }] })).toContain('повторяющиеся');
            expect(check({ rows: [{ id: 'a', value: 'x' }], normalization: [] })).toContain('правило');
            expect(check({ rows: [{ id: 'a', value: '--' }], matchingMode: 'SOFT' })).toContain('букву или цифру');
        });

        it('validates cloze sizes, lengths and passage composition', () => {
            const base = emptyDrafts();
            const blank = newBlank('abcde');
            const cloze = (draft: Partial<ExerciseDrafts['CLOZE']>) => validateDraft('CLOZE', { ...base, CLOZE: { ...base.CLOZE, ...draft } }, context);
            expect(cloze({ texts: ['a ', ''], blanks: [blank] })).toEqual({});
            expect(cloze({ texts: ['', ''], blanks: [blank] })['passage']).toContain('хотя бы один знак текста');
            expect(cloze({ texts: ['я'.repeat(4001), ''], blanks: [blank] })['passage']).toContain('4000');
            expect(cloze({ texts: Array(14).fill('a'), blanks: Array.from({ length: 13 }, () => newBlank('abcde')) })['passage']).toContain('от 1 до 12');
            expect(cloze({ texts: ['a ', ''], blanks: [{ ...blank, size: { mode: 'FIXED', length: 4 } }] })['blank:' + blank.blankId]).toContain('от 5 до 20');
            const key = 'blank:' + blank.blankId;
            const lengths = (values: string[]) => cloze({ texts: ['a ', ''], blanks: [{ ...blank, size: { mode: 'ANSWER_LENGTH' },
                        answer: { ...blank.answer, rows: values.map((value, index) => ({ id: String(index), value })) } }] })[key];
            expect(lengths(['ab', 'abc'])).toContain('одной длины');
            expect(lengths(['😀😀', 'ab'])).toBeUndefined();
        });

        it('validates choice counts and the selection rule without dropping marks', () => {
            const base = emptyDrafts();
            const [first, second] = base.CHOICE.options;
            const choice = { ...base.CHOICE, prompt: [text('q')], options: [{ ...first, blocks: [text('a')] }, { ...second, blocks: [text('b')] }] };
            expect(choiceSelectionProblem(choice)).toContain('Отметьте один');
            expect(choiceSelectionProblem({ ...choice, correctIds: [first.optionId] })).toBeNull();
            expect(choiceSelectionProblem({ ...choice, correctIds: [first.optionId, second.optionId] })).toContain('сейчас отмечено 2');
            expect(choiceSelectionProblem({ ...choice, selectionMode: 'MULTIPLE', correctIds: [first.optionId, second.optionId] })).toBeNull();
            expect(choiceSelectionProblem({ ...choice, selectionMode: 'MULTIPLE', correctIds: [] })).toContain('хотя бы один');
            const tooMany = { ...choice, correctIds: [first.optionId], options: Array.from({ length: 13 }, (_, index) => ({ optionId: `o${index}`, blocks: [text('x')] })) };
            expect(validateDraft('CHOICE', { ...base, CHOICE: tooMany }, context)['options']).toContain('от 2 до 12');
            const spec = buildSpec('CHOICE', { ...base, CHOICE: { ...choice, correctIds: [second.optionId, first.optionId] } }, subject, true);
            expect(spec.answerKey).toEqual({ kind: 'CHOICE', correctOptionIds: [first.optionId, second.optionId] });
        });

        it('validates the number of match pairs and the shape of every item', () => {
            const base = emptyDrafts();
            const filled = base.MATCH.pairs.map(pair => ({ ...pair, left: { ...pair.left, blocks: [text('a')] }, right: { ...pair.right, blocks: [text('b')] } }));
            expect(validateDraft('MATCH', { ...base, MATCH: { prompt: [], pairs: filled } }, context)).toEqual({});
            expect(validateDraft('MATCH', { ...base, MATCH: { prompt: [], pairs: filled.slice(0, 1) } }, context)['pairs']).toContain('от 2 до 6');
            expect(validateDraft('MATCH', { ...base, MATCH: { prompt: [], pairs: [...filled, ...Array.from({ length: 5 }, newPair)] } }, context)['pairs'])
                .toContain('от 2 до 6');
            const empty = validateDraft('MATCH', base, context);
            expect(Object.keys(empty).some(key => key.startsWith('left:'))).toBe(true);
        });

        it('limits media per exercise and refuses one asset as two kinds', () => {
            const base = emptyDrafts();
            const asset = 'aaaaaaaa-0000-4000-8000-000000000001';
            const drafts = { ...base, SELF_CHECK: { prompt: [{ kind: 'IMAGE' as const, assetId: asset, alt: 'x' }, text('q')],
                    reference: [{ kind: 'AUDIO' as const, assetId: asset, title: 'x' }] } };
            expect(validateDraft('SELF_CHECK', drafts, context)['media']).toContain('одновременно');
            expect(LIMITS.mediaBlocksPerExercise).toBe(32);
        });

        it('every built draft passes the strict contract parser', () => {
            const detail = detailFor('createMatchMixed');
            const spec = buildSpec('MATCH', draftsFromDetail(detail), detail.subject, true);
            expect(parseExerciseSpec(spec)).toEqual(mechanics['createMatchMixed'].exercise);
        });
    });

    describe('preview', () => {
        const project = (type: Parameters<typeof buildSpec>[0], drafts: ExerciseDrafts, revealed = false, placeholders = false) => learnerContent(previewExerciseOf(buildSpec(type, drafts, subject, true)), { context, revealed, placeholders });

        it('keeps hidden things hidden: no labels, no transcripts until revealed, no keys', () => {
            const detail = detailFor('createSelfCheck');
            const drafts = draftsFromDetail(detail);
            const hidden = project('SELF_CHECK', drafts);
            expect(JSON.stringify(hidden)).not.toContain('Моё объяснение');
            expect(JSON.stringify(hidden)).not.toContain('Ядро хранит ДНК" ');
            const audio = hidden.type === 'SELF_CHECK' ? hidden.content.reference.find(block => block.kind === 'AUDIO') : null;
            expect(audio).toEqual({ kind: 'AUDIO', assetId: 'aaaaaaaa-0000-4000-8000-000000000004', transcriptAvailable: true });
            const revealed = project('SELF_CHECK', drafts, true);
            expect(JSON.stringify(revealed)).toContain('Ядро хранит ДНК, митохондрии');
        });

        it('skips incomplete blocks, sizes blanks and rotates the right column of a match', () => {
            const base = emptyDrafts();
            const free = project('FREE_RESPONSE', { ...base, FREE_RESPONSE: { ...base.FREE_RESPONSE,
                    prompt: [text(' '), { kind: 'IMAGE', assetId: 'bad', alt: 'x' }, { kind: 'YOUTUBE', videoId: 'x', title: 'y' }, text('ok')] } });
            expect(free.content.prompt).toEqual([{ kind: 'TEXT', text: 'ok' }]);

            const blank = newBlank('abcdefg');
            const cloze = project('CLOZE', { ...base, CLOZE: { prompt: [], texts: ['a ', ''], blanks: [{ ...blank, size: { mode: 'ANSWER_LENGTH' } }] } });
            expect(cloze.type === 'CLOZE' && cloze.content.passage[1]).toEqual({ kind: 'BLANK', blankId: blank.blankId, size: { mode: 'ANSWER_LENGTH', length: 7 }, firstLetterHint: false });

            const pairs = [newPair(), newPair(), newPair()];
            const match = project('MATCH', { ...base, MATCH: { prompt: [], pairs } });
            if (match.type !== 'MATCH')
                throw new Error('Expected MATCH');
            expect(match.content.right.map(item => item.itemId)).toEqual([pairs[1].right.itemId, pairs[2].right.itemId, pairs[0].right.itemId]);
            expect(match.content.left.every(item => item.blocks.length === 1)).toBe(true);
            expect(match.content.left[0].blocks).toEqual([{ kind: 'TEXT', text: 'Пустой элемент' }]);
        });

        it('previews a choice without any answer key', () => {
            const drafts = draftsFromDetail(detailFor('createChoiceVideoMultiple'));
            const choice = project('CHOICE', drafts);
            expect(JSON.stringify(choice)).not.toContain('correct');
            expect(choice.type === 'CHOICE' && choice.content.options.length).toBe(4);
        });

        it('shows neutral placeholders for unfinished parts of a draft and never invents content', () => {
            const base = emptyDrafts();
            const choice = project('CHOICE', base, false, true);
            if (choice.type !== 'CHOICE')
                throw new Error('Expected CHOICE');
            expect(choice.content.prompt).toEqual([{ kind: 'TEXT', text: 'Добавьте вопрос' }]);
            expect(choice.content.options.map(option => option.blocks)).toEqual([
                [{ kind: 'TEXT', text: 'Добавьте вариант' }], [{ kind: 'TEXT', text: 'Добавьте вариант' }]
            ]);
            const half = project('CHOICE', { ...base, CHOICE: { ...base.CHOICE, options: [{ ...base.CHOICE.options[0], blocks: [text('Да')] }, base.CHOICE.options[1]] } }, false, true);
            expect(half.type === 'CHOICE' && half.content.options[1].blocks).toEqual([{ kind: 'TEXT', text: 'Добавьте ещё один вариант' }]);
            const check = project('SELF_CHECK', { ...base, SELF_CHECK: { prompt: [text('Вопрос')], reference: [text('')] } }, false, true);
            expect(check.type === 'SELF_CHECK' && check.content.reference).toEqual([{ kind: 'TEXT', text: 'Укажите правильный ответ' }]);
            // Without placeholders (a complete exercise) nothing is substituted.
            expect(project('SELF_CHECK', { ...base, SELF_CHECK: { prompt: [text('Вопрос')], reference: [] } }).content)
                .toEqual({ prompt: [{ kind: 'TEXT', text: 'Вопрос' }], reference: [] });
        });
    });

    describe('pristine drafts and mechanic switching', () => {
        it('recognises a fresh draft regardless of the random ids and notices the first edit', () => {
            const drafts = emptyDrafts();
            for (const type of ['SELF_CHECK', 'FREE_RESPONSE', 'CLOZE', 'CHOICE', 'MATCH'] as const) {
                expect(isPristine(type, drafts), type).toBe(true);
                expect(isPristine(type, emptyDrafts()), type).toBe(true);
            }
            expect(isPristine('SELF_CHECK', { ...drafts, SELF_CHECK: { ...drafts.SELF_CHECK, prompt: [text('a')] } })).toBe(false);
            expect(isPristine('CHOICE', { ...drafts, CHOICE: { ...drafts.CHOICE, selectionMode: 'MULTIPLE' } })).toBe(false);
            expect(isPristine('CHOICE', { ...drafts, CHOICE: { ...drafts.CHOICE, options: [...drafts.CHOICE.options, drafts.CHOICE.options[0]] } })).toBe(false);
            expect(isPristine('MATCH', { ...drafts, MATCH: { ...drafts.MATCH, pairs: [...drafts.MATCH.pairs, newPair()] } })).toBe(false);
            // Typing and deleting again returns to a pristine draft.
            expect(isPristine('SELF_CHECK', { ...drafts, SELF_CHECK: { ...drafts.SELF_CHECK, prompt: [text('')] } })).toBe(true);
        });

        it('lists only the parts another mechanic cannot show', () => {
            const base = emptyDrafts();
            expect(mechanicSpecificData('CHOICE', base)).toEqual([]);
            expect(mechanicSpecificData('CHOICE', { ...base, CHOICE: { ...base.CHOICE, prompt: [text('только вопрос')] } })).toEqual([]);
            expect(mechanicSpecificData('CHOICE', { ...base, CHOICE: { ...base.CHOICE, correctIds: [base.CHOICE.options[0].optionId] } }).length).toBe(1);
            expect(mechanicSpecificData('MATCH', draftsFromDetail(detailFor('createMatchMixed')))).toEqual(['пары']);
            expect(mechanicSpecificData('CLOZE', draftsFromDetail(detailFor('createCloze')))[0]).toContain('пропусками');
            expect(mechanicSpecificData('FREE_RESPONSE', { ...base, FREE_RESPONSE: { ...base.FREE_RESPONSE, reference: [text('эталон')] } })).toEqual(['эталон ответа']);
        });

        it('carries the question into an empty mechanic and never overwrites authored text', () => {
            const base = emptyDrafts();
            const source = { ...base, CHOICE: { ...base.CHOICE, prompt: [text('Вопрос')] } };
            expect(carryPrompt(source, 'CHOICE', 'CLOZE').CLOZE.prompt).toEqual([text('Вопрос')]);
            expect(carryPrompt(base, 'CHOICE', 'CLOZE')).toBe(base);
            const taken = { ...source, MATCH: { ...source.MATCH, prompt: [text('Своё')] } };
            expect(carryPrompt(taken, 'CHOICE', 'MATCH')).toBe(taken);
            expect(carryPrompt(source, 'CHOICE', 'CHOICE')).toBe(source);
        });
    });

    describe('ORDER and CATEGORIZE', () => {
        it('mints every item and group id with crypto.randomUUID, never from a counter or from the content', () => {
            const minted = vi.spyOn(crypto, 'randomUUID');
            const order = newOrderItem('один');
            const category = newCategory('Глагол');
            const item = newCategorizeItem();
            const drafts = emptyDrafts();
            const ids = [order.itemId, category.categoryId, item.itemId, ...drafts.ORDER.items.map(entry => entry.itemId),
                ...drafts.CATEGORIZE.categories.map(entry => entry.categoryId), ...drafts.CATEGORIZE.items.map(entry => entry.itemId)];
            for (const value of ids)
                expect(value).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
            expect(new Set(ids).size).toBe(ids.length);
            expect(vi.mocked(minted).mock.calls.length).toBeGreaterThanOrEqual(ids.length); // every id above came through the spy
        });

        const withItems = (...values: string[]) => ({ prompt: [], items: values.map(value => newOrderItem(value)) });

        it('uses the authored list order as the key and keeps identical items as separate ids', () => {
            const draft = withItems('Это', 'очень', 'очень', 'важно');
            const spec = buildSpec('ORDER', { ...emptyDrafts(), ORDER: draft }, subject, true);
            expect(spec.type === 'ORDER' && spec.answerKey.sequence).toEqual(draft.items.map(item => item.itemId));
            expect(new Set(draft.items.map(item => item.itemId)).size).toBe(4);
            expect(validateDraft('ORDER', { ...emptyDrafts(), ORDER: draft }, context)).toEqual({});
        });

        it('checks the item count, every item against the SEQUENCE profile and the media cap', () => {
            const errors = (draft: ReturnType<typeof withItems>) => validateDraft('ORDER', { ...emptyDrafts(), ORDER: draft }, context);
            expect(errors(withItems('один'))['items']).toContain('от 2 до 12');
            expect(errors(withItems(...Array.from({ length: 13 }, (_, index) => `${index}`)))['items']).toContain('разделите упражнение');
            const long = withItems('x'.repeat(1001), 'ок');
            expect(errors(long)['item:' + long.items[0].itemId]).toContain('1000');
            const code = withItems('for (;;) {\n    x++;\n}', 'ок');
            expect(errors(code)).toEqual({});
            expect(Object.keys(errors({ prompt: [], items: [{ itemId: 'a', blocks: [] }, newOrderItem('x')] })).some(key => key.startsWith('item:'))).toBe(true);
        });

        it('previews an ORDER or CATEGORIZE in a rotated order, never the authored one, with placeholders for empty parts', () => {
            const draft = withItems('первый', 'второй', 'третий');
            const exercise = previewExerciseOf(buildSpec('ORDER', { ...emptyDrafts(), ORDER: draft }, subject, true));
            const learner = learnerContent(exercise, { context: null, revealed: false, placeholders: false });
            const shown = learner.type === 'ORDER' ? learner.content.items.map(item => item.itemId) : [];
            expect(shown.slice().sort()).toEqual(draft.items.map(item => item.itemId).sort());
            expect(shown).not.toEqual(draft.items.map(item => item.itemId));
            const blank = previewExerciseOf(buildSpec('ORDER', emptyDrafts(), subject, true));
            const placeholder = learnerContent(blank, { context: null, revealed: false, placeholders: true });
            expect(placeholder.type === 'ORDER' && placeholder.content.items.map(item => item.blocks[0])).toEqual([
                { kind: 'TEXT', text: 'Добавьте элемент' }, { kind: 'TEXT', text: 'Добавьте элемент' }
            ]);

            const categorize = emptyDrafts();
            const groups = categorize.CATEGORIZE.categories;
            const cat = previewExerciseOf(buildSpec('CATEGORIZE', categorize, subject, true));
            const view = learnerContent(cat, { context: null, revealed: false, placeholders: true });
            expect(view.type === 'CATEGORIZE' && view.content.categories.map(group => group.label)).toEqual(['Название группы 1', 'Название группы 2']);
            expect(view.type === 'CATEGORIZE' && view.content.categories.map(group => group.categoryId)).toEqual(groups.map(group => group.categoryId));
            expect(JSON.stringify(view)).not.toContain('assignments');
        });

        it('never builds an answer key entry for an unassigned item, and only the picked group counts as assigned', () => {
            const base = emptyDrafts().CATEGORIZE;
            const draft = { ...base, items: base.items.map((item, index) => index === 0 ? { ...item, categoryId: base.categories[1].categoryId } : item) };
            const spec = buildSpec('CATEGORIZE', { ...emptyDrafts(), CATEGORIZE: draft }, subject, true);
            expect(spec.type === 'CATEGORIZE' && spec.answerKey.assignments).toEqual([{ itemId: draft.items[0].itemId, categoryId: base.categories[1].categoryId }]);
            expect(isPristine('CATEGORIZE', { ...emptyDrafts(), CATEGORIZE: draft })).toBe(false);
            expect(isPristine('CATEGORIZE', { ...emptyDrafts(), CATEGORIZE: emptyDrafts().CATEGORIZE })).toBe(true);
            expect(isPristine('ORDER', { ...emptyDrafts(), ORDER: withItems('x', '') })).toBe(false);
            expect(isPristine('ORDER', emptyDrafts())).toBe(true);
        });

        it('lists what a switch would lose', () => {
            const base = emptyDrafts();
            expect(mechanicSpecificData('ORDER', base)).toEqual([]);
            expect(mechanicSpecificData('ORDER', { ...base, ORDER: withItems('а', 'б') })).toEqual(['элементы и их порядок']);
            expect(mechanicSpecificData('CATEGORIZE', base)).toEqual([]);
            expect(mechanicSpecificData('CATEGORIZE', { ...base, CATEGORIZE: { ...base.CATEGORIZE,
                    categories: [{ categoryId: 'x', label: 'Глагол' }, base.CATEGORIZE.categories[1]] } })).toEqual(['группы, элементы и их распределение']);
        });
    });
});
