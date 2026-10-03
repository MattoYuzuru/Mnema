import {
    AuthoringBlock, Category, ChoiceOption, ClozeBlankKey, ClozeSegment, ClozeSize, COMPACT_SLOT, ExerciseSpec,
    ExerciseSubject, LIMITS, LearnerBlock, LearnerClozeSegment, LearnerContent, MatchingMode, MatchItem, Mechanic, NORMALIZATION_RULES,
    NormalizationRule, OrderItem, PROMPT_SLOTS, PreviewExercise, REFERENCE_SLOTS, ResponseInput, SEQUENCE_SLOT, SLOT_PROFILES,
    SelectionMode, SlotSpec, authoringSlots, categoryLabelKey, codePointLength, distinguishableItems, isBlank, mediaBlockCount
} from '../../content/exercise/exercise-content.models';
import { blockProblem, isEntityId, slotProblem } from '../../content/exercise/exercise-content.parse';
import { NativeDocument } from '../../content/native-document';
import { ExerciseProjection } from './exercise.models';
import { AiRubricDraft, rubricDraft, rubricErrors, rubricSpec } from './ai-rubric-draft';

/**
 * Editable state of one exercise per mechanic. Drafts are plain immutable values: switching the mechanic
 * only changes which draft is shown, so nothing typed into another mechanic is ever lost.
 */

export interface AliasRow { readonly id: string; readonly value: string; }

export interface TextAnswerDraft {
    readonly rows: readonly AliasRow[];
    readonly normalization: readonly NormalizationRule[];
    readonly matchingMode: MatchingMode;
}

export interface SelfCheckDraft { readonly prompt: readonly AuthoringBlock[]; readonly reference: readonly AuthoringBlock[]; }
export interface FreeResponseDraft {
    readonly prompt: readonly AuthoringBlock[];
    readonly reference: readonly AuthoringBlock[];
    readonly answer: TextAnswerDraft;
    readonly responseInput: ResponseInput;
    /** Rubric v1 while «Проверять смысл ответа с ИИ» is on; `null` means the deterministic list of accepted answers. */
    readonly aiRubric: AiRubricDraft | null;
}
export interface ClozeBlankDraft {
    readonly blankId: string;
    readonly size: ClozeSize;
    readonly firstLetterHint: boolean;
    readonly answer: TextAnswerDraft;
}
/** `texts.length === blanks.length + 1`: text, blank, text, blank, ..., text. Empty texts are dropped on save. */
export interface ClozeDraft {
    readonly prompt: readonly AuthoringBlock[];
    readonly texts: readonly string[];
    readonly blanks: readonly ClozeBlankDraft[];
}
export interface ChoiceOptionDraft { readonly optionId: string; readonly blocks: readonly AuthoringBlock[]; }
export interface ChoiceDraft {
    readonly prompt: readonly AuthoringBlock[];
    readonly selectionMode: SelectionMode;
    readonly options: readonly ChoiceOptionDraft[];
    /** Correct marks survive a mode switch; the mismatch is reported instead of dropped. */
    readonly correctIds: readonly string[];
}
export interface MatchSideDraft { readonly itemId: string; readonly blocks: readonly AuthoringBlock[]; }
export interface MatchPairDraft { readonly pairId: string; readonly left: MatchSideDraft; readonly right: MatchSideDraft; }
export interface MatchDraft { readonly prompt: readonly AuthoringBlock[]; readonly pairs: readonly MatchPairDraft[]; }

/** One ORDER item. The authored list order IS the answer key: nothing about the sequence is inferred. */
export interface OrderItemDraft { readonly itemId: string; readonly blocks: readonly AuthoringBlock[]; }
export interface OrderDraft { readonly prompt: readonly AuthoringBlock[]; readonly items: readonly OrderItemDraft[]; }
export interface CategoryDraft { readonly categoryId: string; readonly label: string; }
/** `categoryId` is null until the author picks a group; it can never name a group that was removed. */
export interface CategorizeItemDraft {
    readonly itemId: string;
    readonly blocks: readonly AuthoringBlock[];
    readonly categoryId: string | null;
}
export interface CategorizeDraft {
    readonly prompt: readonly AuthoringBlock[];
    readonly categories: readonly CategoryDraft[];
    readonly items: readonly CategorizeItemDraft[];
}

export interface ExerciseDrafts {
    readonly SELF_CHECK: SelfCheckDraft;
    readonly FREE_RESPONSE: FreeResponseDraft;
    readonly CLOZE: ClozeDraft;
    readonly CHOICE: ChoiceDraft;
    readonly MATCH: MatchDraft;
    readonly ORDER: OrderDraft;
    readonly CATEGORIZE: CategorizeDraft;
}

/** What slot editors and validation need to know about the material the exercise is attached to. */
export interface SlotContext {
    readonly document: NativeDocument;
    readonly memberKey: string;
    readonly itemRevisionId: string;
    readonly projections: readonly ExerciseProjection[];
}

/** All that is needed to show the quoted text of a `MATERIAL` block: a proposal has no document, only the quotes the server sent. */
export type MaterialContext = Pick<SlotContext, 'projections'>;

export type DraftErrors = Readonly<Partial<Record<string, string>>>;

export function newId(): string { return crypto.randomUUID(); }

function textBlock(text = ''): AuthoringBlock { return { kind: 'TEXT', text }; }

export function newAnswer(values: readonly string[] = ['']): TextAnswerDraft {
    return { rows: values.map(value => ({ id: newId(), value })), normalization: [...NORMALIZATION_RULES], matchingMode: 'STRICT' };
}

export function newBlank(accepted = ''): ClozeBlankDraft {
    return { blankId: newId(), size: { mode: 'FIXED', length: 12 }, firstLetterHint: false, answer: newAnswer([accepted]) };
}

export function newOption(): ChoiceOptionDraft { return { optionId: newId(), blocks: [textBlock()] }; }

export function newPair(): MatchPairDraft {
    return { pairId: newId(), left: { itemId: newId(), blocks: [textBlock()] }, right: { itemId: newId(), blocks: [textBlock()] } };
}

export function newOrderItem(text = ''): OrderItemDraft { return { itemId: newId(), blocks: [textBlock(text)] }; }

export function newCategory(label = ''): CategoryDraft { return { categoryId: newId(), label }; }

export function newCategorizeItem(categoryId: string | null = null): CategorizeItemDraft {
    return { itemId: newId(), blocks: [textBlock()], categoryId };
}

export function emptyDrafts(): ExerciseDrafts {
    return {
        SELF_CHECK: { prompt: [textBlock()], reference: [textBlock()] },
        FREE_RESPONSE: { prompt: [textBlock()], reference: [], answer: newAnswer(), responseInput: 'TEXT', aiRubric: null },
        CLOZE: { prompt: [], texts: [''], blanks: [] },
        CHOICE: { prompt: [textBlock()], selectionMode: 'SINGLE', options: [newOption(), newOption()], correctIds: [] },
        MATCH: { prompt: [], pairs: [newPair(), newPair()] },
        ORDER: { prompt: [], items: [newOrderItem(), newOrderItem()] },
        CATEGORIZE: { prompt: [], categories: [newCategory(), newCategory()], items: [newCategorizeItem(), newCategorizeItem()] }
    };
}

// ---------------------------------------------------------------------------------------------
// Draft <-> specification
// ---------------------------------------------------------------------------------------------

function answerDraft(answer: { readonly accepted: readonly string[]; readonly normalization: readonly NormalizationRule[];
    readonly matchingMode: MatchingMode }): TextAnswerDraft {
    return { rows: answer.accepted.map(value => ({ id: newId(), value })), normalization: answer.normalization, matchingMode: answer.matchingMode };
}

/**
 * Loads a persisted exercise, or a proposal of the same shape, into the drafts, leaving the other mechanics at their defaults.
 * A persisted exercise is an `ExerciseSpec` with an envelope, so it fits.
 */
export function draftsFromDetail(detail: ExerciseSpec): ExerciseDrafts {
    const drafts = emptyDrafts();
    switch (detail.type) {
        case 'SELF_CHECK': return { ...drafts, SELF_CHECK: { prompt: detail.content.prompt, reference: detail.content.reference } };
        case 'FREE_RESPONSE': return { ...drafts, FREE_RESPONSE: { prompt: detail.content.prompt, reference: detail.content.reference,
            answer: answerDraft(detail.answerKey), responseInput: detail.content.responseInput,
            aiRubric: detail.evaluatorPolicy.id === 'ai-semantic' ? rubricDraft(detail.evaluatorPolicy.rubric) : null } };
        case 'CLOZE': {
            const texts: string[] = [''];
            const blanks: ClozeBlankDraft[] = [];
            for (const segment of detail.content.passage) {
                if (segment.kind === 'TEXT') texts[texts.length - 1] += segment.text;
                else {
                    const key = detail.answerKey.blanks.find(entry => entry.blankId === segment.blankId);
                    blanks.push({ blankId: segment.blankId, size: segment.size, firstLetterHint: segment.firstLetterHint,
                        answer: answerDraft(key ?? { accepted: [''], normalization: NORMALIZATION_RULES, matchingMode: 'STRICT' }) });
                    texts.push('');
                }
            }
            return { ...drafts, CLOZE: { prompt: detail.content.prompt, texts, blanks } };
        }
        case 'CHOICE': return { ...drafts, CHOICE: { prompt: detail.content.prompt, selectionMode: detail.content.selectionMode,
            options: detail.content.options, correctIds: detail.answerKey.correctOptionIds } };
        case 'MATCH': return { ...drafts, MATCH: { prompt: detail.content.prompt, pairs: detail.content.left.map((left, index) => ({
            pairId: newId(), left,
            right: detail.content.right.find(item => item.itemId === detail.answerKey.pairs
                .find(pair => pair.leftId === left.itemId)?.rightId) ?? detail.content.right[index] })) } };
        case 'ORDER': return { ...drafts, ORDER: { prompt: detail.content.prompt,
            // The key is the authored order; list it in that order whatever order the content array has.
            items: detail.answerKey.sequence.flatMap(itemId => detail.content.items.filter(item => item.itemId === itemId)) } };
        case 'CATEGORIZE': return { ...drafts, CATEGORIZE: { prompt: detail.content.prompt, categories: detail.content.categories,
            items: detail.content.items.map(item => ({ ...item, categoryId: detail.answerKey.assignments
                .find(assignment => assignment.itemId === item.itemId)?.categoryId ?? null })) } };
    }
}

/** Cloze passage as contract segments: empty text parts are dropped, nothing else is trimmed or altered. */
export function clozePassage(draft: ClozeDraft): readonly ClozeSegment[] {
    const segments: ClozeSegment[] = [];
    draft.texts.forEach((text, index) => {
        if (text !== '') segments.push({ kind: 'TEXT', text });
        const blank = draft.blanks[index];
        if (blank !== undefined) segments.push({ kind: 'BLANK', blankId: blank.blankId, size: blank.size,
            firstLetterHint: blank.firstLetterHint });
    });
    return segments;
}

function textKey(answer: TextAnswerDraft) {
    return { accepted: answer.rows.map(row => row.value), normalization: answer.normalization, matchingMode: answer.matchingMode };
}

/**
 * The server still requires a TEXT answer key for an AI-checked exercise although the grader never reads it. The
 * author is not asked for it: the typed list is kept when it is valid (an existing exercise round-trips unchanged),
 * otherwise the key is the first characters of the reference answer, compared strictly.
 */
function aiAnswerKey(draft: FreeResponseDraft, rubric: AiRubricDraft) {
    if (answerErrorMessage(draft.answer, LIMITS.freeResponseAccepted) === null) return textKey(draft.answer);
    const first = Array.from(rubric.referenceAnswer.trim()).slice(0, LIMITS.freeResponseAccepted.length).join('').trim();
    return { accepted: [first], normalization: [...NORMALIZATION_RULES], matchingMode: 'STRICT' as const };
}

/** Builds the publication payload for the selected mechanic. It may be invalid until `validateDraft` passes. */
export function buildSpec(type: Mechanic, drafts: ExerciseDrafts, subject: ExerciseSubject, enabled: boolean): ExerciseSpec {
    const base = { schemaVersion: 2 as const, enabled, subject };
    switch (type) {
        case 'SELF_CHECK': return { ...base, type, content: drafts.SELF_CHECK, answerKey: { kind: 'SELF_REPORT' },
            evaluatorPolicy: { id: 'self-check', version: '1' } };
        case 'FREE_RESPONSE': {
            const draft = drafts.FREE_RESPONSE;
            return { ...base, type, content: { prompt: draft.prompt, reference: draft.reference, responseInput: draft.responseInput },
                answerKey: { kind: 'TEXT', ...(draft.aiRubric === null ? textKey(draft.answer) : aiAnswerKey(draft, draft.aiRubric)) },
                evaluatorPolicy: draft.aiRubric === null ? { id: 'deterministic-text', version: '1' }
                    : { id: 'ai-semantic', version: '1', rubric: rubricSpec(draft.aiRubric) } };
        }
        case 'CLOZE': {
            const draft = drafts.CLOZE;
            return { ...base, type, content: { prompt: draft.prompt, passage: clozePassage(draft) },
                answerKey: { kind: 'CLOZE', blanks: draft.blanks.map((blank): ClozeBlankKey => ({ blankId: blank.blankId, ...textKey(blank.answer) })) },
                evaluatorPolicy: { id: 'deterministic-cloze', version: '1' } };
        }
        case 'CHOICE': {
            const draft = drafts.CHOICE;
            const options: readonly ChoiceOption[] = draft.options;
            return { ...base, type, content: { prompt: draft.prompt, selectionMode: draft.selectionMode, options },
                answerKey: { kind: 'CHOICE', correctOptionIds: options.map(option => option.optionId)
                    .filter(optionId => draft.correctIds.includes(optionId)) },
                evaluatorPolicy: { id: 'deterministic-choice', version: '1' } };
        }
        case 'MATCH': {
            const draft = drafts.MATCH;
            const left: readonly MatchItem[] = draft.pairs.map(pair => pair.left);
            const right: readonly MatchItem[] = draft.pairs.map(pair => pair.right);
            return { ...base, type, content: { prompt: draft.prompt, left, right },
                answerKey: { kind: 'MATCH', pairs: draft.pairs.map(pair => ({ leftId: pair.left.itemId, rightId: pair.right.itemId })) },
                evaluatorPolicy: { id: 'deterministic-match', version: '1' } };
        }
        case 'ORDER': {
            const draft = drafts.ORDER;
            const items: readonly OrderItem[] = draft.items;
            return { ...base, type, content: { prompt: draft.prompt, items },
                answerKey: { kind: 'ORDER', sequence: items.map(item => item.itemId) },
                evaluatorPolicy: { id: 'deterministic-order', version: '1' } };
        }
        case 'CATEGORIZE': {
            const draft = drafts.CATEGORIZE;
            const categories: readonly Category[] = draft.categories;
            return { ...base, type, content: { prompt: draft.prompt, categories,
                items: draft.items.map(item => ({ itemId: item.itemId, blocks: item.blocks })) },
                answerKey: { kind: 'CATEGORIZE', assignments: draft.items.flatMap(item => item.categoryId === null ? []
                    : [{ itemId: item.itemId, categoryId: item.categoryId }]) },
                evaluatorPolicy: { id: 'deterministic-categorize', version: '1' } };
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Validation: author-facing, one message per field key
// ---------------------------------------------------------------------------------------------

/** Resolved text of a MATERIAL block, or null when the fragment is not in the loaded revision. */
export function materialText(block: AuthoringBlock, context: MaterialContext): string | null {
    if (block.kind !== 'MATERIAL') return null;
    return context.projections.find(projection => projection.nodeId === block.nodeId)?.text ?? null;
}

/** First problem of one slot (counts, composition, every block, material text length), or null. */
export function slotErrorMessage(blocks: readonly AuthoringBlock[], spec: SlotSpec, context: SlotContext): string | null {
    const composition = slotProblem(blocks, spec);
    if (composition !== null) return composition;
    for (const [index, block] of blocks.entries()) {
        const problem = blockProblem(block, spec.profile);
        if (problem !== null) return `Блок ${index + 1}: ${problem}`;
        const resolved = materialText(block, context);
        const limit = SLOT_PROFILES[spec.profile].textLimit;
        if (resolved !== null && resolved.length > limit) {
            return `Блок ${index + 1}: фрагмент материала длиннее ${limit} знаков (${resolved.length}). Выберите более короткий.`;
        }
    }
    return null;
}

function answerErrorMessage(answer: TextAnswerDraft, limits: { readonly min: number; readonly max: number; readonly length: number }): string | null {
    const values = answer.rows.map(row => row.value);
    if (values.length < limits.min || values.length > limits.max) return `Нужно от ${limits.min} до ${limits.max} допустимых ответов.`;
    if (values.some(isBlank)) return 'Заполните или удалите пустые допустимые ответы.';
    if (values.some(value => value.length > limits.length)) return `Один из ответов длиннее ${limits.length} знаков.`;
    if (new Set(values).size !== values.length) return 'Уберите повторяющиеся допустимые ответы.';
    if (answer.normalization.length === 0) return 'Выберите хотя бы одно правило сравнения.';
    if (answer.matchingMode === 'SOFT' && values.some(value => !/[\p{L}\p{N}]/u.test(value))) {
        return 'В мягком режиме каждый ответ должен содержать букву или цифру.';
    }
    return null;
}

/** Messages by field key; an empty map means the draft can be published. */
export function validateDraft(type: Mechanic, drafts: ExerciseDrafts, context: SlotContext): DraftErrors {
    const errors: Record<string, string> = {};
    const check = (key: string, blocks: readonly AuthoringBlock[], spec: SlotSpec): void => {
        const message = slotErrorMessage(blocks, spec, context);
        if (message !== null) errors[key] = message;
    };
    switch (type) {
        case 'SELF_CHECK':
            check('prompt', drafts.SELF_CHECK.prompt, PROMPT_SLOTS.SELF_CHECK);
            check('reference', drafts.SELF_CHECK.reference, REFERENCE_SLOTS.SELF_CHECK);
            break;
        case 'FREE_RESPONSE': {
            const draft = drafts.FREE_RESPONSE;
            check('prompt', draft.prompt, PROMPT_SLOTS.FREE_RESPONSE);
            check('reference', draft.reference, REFERENCE_SLOTS.FREE_RESPONSE);
            if (draft.aiRubric !== null) Object.assign(errors, rubricErrors(draft.aiRubric));
            else {
                const message = answerErrorMessage(draft.answer, LIMITS.freeResponseAccepted);
                if (message !== null) errors['accepted'] = message;
            }
            break;
        }
        case 'CLOZE': {
            const draft = drafts.CLOZE;
            check('prompt', draft.prompt, PROMPT_SLOTS.CLOZE);
            const passage = clozePassage(draft);
            const total = draft.texts.reduce((sum, text) => sum + text.length, 0);
            if (draft.blanks.length < LIMITS.clozeBlanks.min || draft.blanks.length > LIMITS.clozeBlanks.max) {
                errors['passage'] = `Добавьте от ${LIMITS.clozeBlanks.min} до ${LIMITS.clozeBlanks.max} пропусков: выделите часть текста и сделайте её пропуском.`;
            } else if (!passage.some(segment => segment.kind === 'TEXT')) {
                errors['passage'] = 'Кроме пропусков нужен хотя бы один знак текста.';
            } else if (total > LIMITS.clozeTotalText) {
                errors['passage'] = `Текст длиннее ${LIMITS.clozeTotalText} знаков (сейчас ${total}). Сократите его: Mnema ничего не обрезает.`;
            } else if (passage.length > LIMITS.clozeSegments.max) {
                errors['passage'] = `Слишком много частей текста: не больше ${LIMITS.clozeSegments.max}.`;
            }
            for (const blank of draft.blanks) {
                const message = answerErrorMessage(blank.answer, LIMITS.clozeAccepted) ?? blankSizeMessage(blank);
                if (message !== null) errors[`blank:${blank.blankId}`] = message;
            }
            break;
        }
        case 'CHOICE': {
            const draft = drafts.CHOICE;
            check('prompt', draft.prompt, PROMPT_SLOTS.CHOICE);
            if (draft.options.length < LIMITS.choiceOptions.min || draft.options.length > LIMITS.choiceOptions.max) {
                errors['options'] = `Нужно от ${LIMITS.choiceOptions.min} до ${LIMITS.choiceOptions.max} вариантов.`;
            }
            draft.options.forEach(option => check(`option:${option.optionId}`, option.blocks, COMPACT_SLOT));
            const selection = choiceSelectionProblem(draft);
            if (selection !== null) errors['selection'] = selection;
            break;
        }
        case 'MATCH': {
            const draft = drafts.MATCH;
            check('prompt', draft.prompt, PROMPT_SLOTS.MATCH);
            if (draft.pairs.length < LIMITS.matchPairs.min || draft.pairs.length > LIMITS.matchPairs.max) {
                errors['pairs'] = `Нужно от ${LIMITS.matchPairs.min} до ${LIMITS.matchPairs.max} пар.`;
            }
            for (const pair of draft.pairs) {
                check(`left:${pair.left.itemId}`, pair.left.blocks, COMPACT_SLOT);
                check(`right:${pair.right.itemId}`, pair.right.blocks, COMPACT_SLOT);
            }
            break;
        }
        case 'ORDER': {
            const draft = drafts.ORDER;
            check('prompt', draft.prompt, PROMPT_SLOTS.ORDER);
            if (draft.items.length < LIMITS.orderItems.min || draft.items.length > LIMITS.orderItems.max) {
                errors['items'] = `Нужно от ${LIMITS.orderItems.min} до ${LIMITS.orderItems.max} элементов. Если частей больше, разделите упражнение на несколько.`;
            }
            draft.items.forEach(item => check(`item:${item.itemId}`, item.blocks, SEQUENCE_SLOT));
            // Identical tiles are interchangeable, so a key made of copies of one tile asks the learner for nothing.
            if (errors['items'] === undefined && !draft.items.some(item => errors[`item:${item.itemId}`] !== undefined)
                && distinguishableItems(draft.items, block => materialText(block, context)) < 2) {
                errors['items'] = 'Добавьте хотя бы два разных элемента — одинаковые плитки взаимозаменяемы.';
            }
            break;
        }
        case 'CATEGORIZE': {
            const draft = drafts.CATEGORIZE;
            check('prompt', draft.prompt, PROMPT_SLOTS.CATEGORIZE);
            if (draft.categories.length < LIMITS.categories.min || draft.categories.length > LIMITS.categories.max) {
                errors['categories'] = `Нужно от ${LIMITS.categories.min} до ${LIMITS.categories.max} групп.`;
            }
            const seen = new Set<string>();
            for (const category of draft.categories) {
                const message = categoryProblem(category, seen);
                if (message !== null) errors[`category:${category.categoryId}`] = message;
            }
            if (draft.items.length < LIMITS.categorizeItems.min || draft.items.length > LIMITS.categorizeItems.max) {
                errors['items'] = `Нужно от ${LIMITS.categorizeItems.min} до ${LIMITS.categorizeItems.max} элементов. Если их больше, разделите упражнение на несколько.`;
            }
            for (const item of draft.items) {
                check(`item:${item.itemId}`, item.blocks, COMPACT_SLOT);
                if (item.categoryId === null || !draft.categories.some(category => category.categoryId === item.categoryId)) {
                    errors[`assignment:${item.itemId}`] = 'Выберите группу для этого элемента.';
                }
            }
            break;
        }
    }
    const spec = buildSpec(type, drafts, { memberKey: context.memberKey, itemRevisionId: context.itemRevisionId }, true);
    const slots = authoringSlots(spec);
    if (slots.reduce((sum, blocks) => sum + mediaBlockCount(blocks), 0) > LIMITS.mediaBlocksPerExercise) {
        errors['media'] = `В упражнении не больше ${LIMITS.mediaBlocksPerExercise} медиафайлов.`;
    }
    const kinds = new Map<string, string>();
    for (const block of slots.flat()) {
        if (block.kind !== 'IMAGE' && block.kind !== 'AUDIO' && block.kind !== 'VIDEO') continue;
        if (isEntityId(block.assetId) && kinds.get(block.assetId) !== undefined && kinds.get(block.assetId) !== block.kind) {
            errors['media'] = 'Один и тот же файл нельзя использовать как изображение, аудио и видео одновременно.';
        }
        kinds.set(block.assetId, block.kind);
    }
    return errors;
}

/** A group label is short plain text and unique after trim and case folding; `seen` collects the folded labels so far. */
function categoryProblem(category: CategoryDraft, seen: Set<string>): string | null {
    if (categoryLabelKey(category.label) === '') return 'Назовите группу: в названии должен быть хотя бы один видимый знак.';
    if (category.label.length > LIMITS.categories.label) {
        return `Название группы не длиннее ${LIMITS.categories.label} знаков (сейчас ${category.label.length}).`;
    }
    const key = categoryLabelKey(category.label);
    if (seen.has(key)) return 'Это название уже занято другой группой: у групп должны быть разные названия.';
    seen.add(key);
    return null;
}

function blankSizeMessage(blank: ClozeBlankDraft): string | null {
    if (blank.size.mode === 'FIXED') {
        const length = blank.size.length;
        return Number.isInteger(length) && length >= LIMITS.clozeFixedLength.min && length <= LIMITS.clozeFixedLength.max
            ? null : `Укажите ширину пропуска от ${LIMITS.clozeFixedLength.min} до ${LIMITS.clozeFixedLength.max} знаков.`;
    }
    const lengths = new Set(blank.answer.rows.map(row => codePointLength(row.value)));
    const [length] = [...lengths];
    return lengths.size === 1 && length >= LIMITS.clozeAnswerLength.min && length <= LIMITS.clozeAnswerLength.max
        ? null : 'Для ширины «по длине ответа» все допустимые ответы должны быть одной длины (до 80 знаков).';
}

/** SINGLE needs exactly one correct option, MULTIPLE at least one. Marks are never dropped to satisfy this. */
export function choiceSelectionProblem(draft: ChoiceDraft): string | null {
    const marked = draft.options.filter(option => draft.correctIds.includes(option.optionId)).length;
    if (draft.selectionMode === 'SINGLE' && marked !== 1) {
        return marked === 0 ? 'Отметьте один правильный вариант.'
            : `В режиме «Один ответ» правильным может быть только один вариант, сейчас отмечено ${marked}. Снимите лишние отметки или выберите «Несколько ответов».`;
    }
    return marked === 0 ? 'Отметьте хотя бы один правильный вариант.' : null;
}

// ---------------------------------------------------------------------------------------------
// Pristine drafts and mechanic switching
// ---------------------------------------------------------------------------------------------

const ID_KEYS: ReadonlySet<string> = new Set(['id', 'optionId', 'itemId', 'pairId', 'blankId']);

/**
 * Draft structure without the random ids that `emptyDrafts` generates on every call. A group assignment keeps
 * its meaning (assigned or not) because choosing a group is an authored value, only the random id is dropped.
 */
function shape(value: unknown): string {
    return JSON.stringify(value, (key, entry: unknown) => key === 'categoryId' ? (entry === null ? null : 'assigned')
        : ID_KEYS.has(key) ? undefined : entry);
}

/** True while the draft of this mechanic still equals a freshly created one: nothing authored yet. */
export function isPristine(type: Mechanic, drafts: ExerciseDrafts): boolean {
    return shape(drafts[type]) === shape(emptyDrafts()[type]);
}

function filled(blocks: readonly AuthoringBlock[]): boolean {
    return blocks.some(block => block.kind !== 'TEXT' || !isBlank(block.text));
}

/**
 * Names of the authored parts that only this mechanic can show (answers, options and marks, pairs, blanks,
 * the reference). Empty means switching loses nothing from view; the question itself is carried over.
 */
export function mechanicSpecificData(type: Mechanic, drafts: ExerciseDrafts): readonly string[] {
    const found: string[] = [];
    switch (type) {
        case 'SELF_CHECK':
            if (filled(drafts.SELF_CHECK.reference)) found.push('эталон ответа');
            break;
        case 'FREE_RESPONSE':
            if (drafts.FREE_RESPONSE.answer.rows.some(row => !isBlank(row.value))) found.push('допустимые ответы');
            if (filled(drafts.FREE_RESPONSE.reference)) found.push('эталон ответа');
            break;
        case 'CLOZE':
            if (drafts.CLOZE.blanks.length > 0 || drafts.CLOZE.texts.some(text => !isBlank(text))) found.push('текст с пропусками и ответы к ним');
            break;
        case 'CHOICE':
            if (drafts.CHOICE.options.some(option => filled(option.blocks)) || drafts.CHOICE.correctIds.length > 0) {
                found.push('варианты ответа и отметки правильных');
            }
            break;
        case 'MATCH':
            if (drafts.MATCH.pairs.some(pair => filled(pair.left.blocks) || filled(pair.right.blocks))) found.push('пары');
            break;
        case 'ORDER':
            if (drafts.ORDER.items.some(item => filled(item.blocks))) found.push('элементы и их порядок');
            break;
        case 'CATEGORIZE':
            if (drafts.CATEGORIZE.categories.some(category => !isBlank(category.label))
                || drafts.CATEGORIZE.items.some(item => filled(item.blocks) || item.categoryId !== null)) {
                found.push('группы, элементы и их распределение');
            }
            break;
    }
    return found;
}

/** Copies the question into the target mechanic when the target has none yet; never overwrites authored text. */
export function carryPrompt(drafts: ExerciseDrafts, from: Mechanic, to: Mechanic): ExerciseDrafts {
    if (from === to || !filled(drafts[from].prompt) || filled(drafts[to].prompt)) return drafts;
    return { ...drafts, [to]: { ...drafts[to], prompt: drafts[from].prompt } };
}

// ---------------------------------------------------------------------------------------------
// Preview: authoring content -> what the learner sees. Evaluation is always the server's.
// ---------------------------------------------------------------------------------------------

/** Neutral placeholders shown in place of the parts an author has not written yet. */
export const PREVIEW_PLACEHOLDERS = {
    prompt: 'Добавьте вопрос',
    reference: 'Укажите правильный ответ',
    passage: 'Добавьте текст и сделайте в нём пропуски',
    firstOption: 'Добавьте вариант',
    nextOption: 'Добавьте ещё один вариант',
    item: 'Добавьте элемент пары',
    orderItem: 'Добавьте элемент',
    group: 'Название группы'
} as const;

export interface LearnerProjection {
    /** Resolves MATERIAL blocks; a demo has no material. */
    readonly context: MaterialContext | null;
    readonly revealed: boolean;
    /** Show placeholders for unfinished parts instead of dropping them (an incomplete author draft). */
    readonly placeholders: boolean;
}

function learnerBlocks(blocks: readonly AuthoringBlock[], context: MaterialContext | null, revealed: boolean): readonly LearnerBlock[] {
    return blocks.flatMap((block): LearnerBlock[] => {
        switch (block.kind) {
            case 'TEXT': return isBlank(block.text) ? [] : [{ kind: 'TEXT', text: block.text }];
            case 'MATERIAL': {
                const text = context === null ? null : materialText(block, context);
                return text === null ? [] : [{ kind: 'TEXT', text }];
            }
            case 'IMAGE': return isEntityId(block.assetId) ? [{ kind: 'IMAGE', assetId: block.assetId, alt: block.alt || 'Изображение' }] : [];
            case 'AUDIO':
            case 'VIDEO': {
                if (!isEntityId(block.assetId)) return [];
                const transcript = block.transcript !== undefined && !isBlank(block.transcript) ? block.transcript : null;
                return [{ kind: block.kind, assetId: block.assetId, transcriptAvailable: transcript !== null,
                    ...(revealed && transcript !== null ? { transcript } : {}) }];
            }
            case 'YOUTUBE': return /^[A-Za-z0-9_-]{11}$/u.test(block.videoId) ? [{ kind: 'YOUTUBE', videoId: block.videoId, title: block.title || 'Видео' }] : [];
        }
    });
}

function placeholderText(text: string): readonly LearnerBlock[] { return [{ kind: 'TEXT', text }]; }

function promptBlocks(blocks: readonly AuthoringBlock[], projection: LearnerProjection, required: boolean): readonly LearnerBlock[] {
    const result = learnerBlocks(blocks, projection.context, projection.revealed);
    return result.length === 0 && required && projection.placeholders ? placeholderText(PREVIEW_PLACEHOLDERS.prompt) : result;
}

/**
 * The learner view of an authoring exercise (a draft built with `buildSpec` or a demo). Incomplete parts are
 * skipped, or replaced by neutral placeholders in a draft preview, so the preview never blocks editing. It only
 * mirrors what Study would issue; no key is read and nothing is evaluated here.
 */
export function learnerContent(exercise: PreviewExercise, projection: LearnerProjection): LearnerContent {
    const { context, revealed, placeholders } = projection;
    switch (exercise.type) {
        case 'SELF_CHECK': {
            const reference = learnerBlocks(exercise.content.reference, context, revealed);
            return { type: exercise.type, content: { prompt: promptBlocks(exercise.content.prompt, projection, true),
                reference: reference.length === 0 && placeholders ? placeholderText(PREVIEW_PLACEHOLDERS.reference) : reference } };
        }
        case 'FREE_RESPONSE': return { type: exercise.type, content: { prompt: promptBlocks(exercise.content.prompt, projection, true),
            responseInput: exercise.content.responseInput } };
        case 'CLOZE': {
            const passage = exercise.content.passage.map((segment): LearnerClozeSegment => {
                if (segment.kind === 'TEXT') return segment;
                const first = exercise.answerKey.blanks.find(entry => entry.blankId === segment.blankId)?.accepted[0] ?? '';
                const length = segment.size.mode === 'FIXED' ? segment.size.length : Math.max(1, codePointLength(first));
                return { kind: 'BLANK', blankId: segment.blankId, size: { mode: segment.size.mode, length },
                    firstLetterHint: segment.firstLetterHint };
            });
            return { type: exercise.type, content: { prompt: promptBlocks(exercise.content.prompt, projection, false),
                passage: passage.length === 0 && placeholders ? placeholderText(PREVIEW_PLACEHOLDERS.passage) as LearnerClozeSegment[] : passage } };
        }
        case 'CHOICE': {
            const anyFilled = exercise.content.options.some(option => learnerBlocks(option.blocks, context, revealed).length > 0);
            const empty = placeholders ? placeholderText(anyFilled ? PREVIEW_PLACEHOLDERS.nextOption : PREVIEW_PLACEHOLDERS.firstOption)
                : placeholderText('Пустой элемент');
            return { type: exercise.type, content: { prompt: promptBlocks(exercise.content.prompt, projection, true),
                selectionMode: exercise.content.selectionMode,
                options: exercise.content.options.map(option => {
                    const blocks = learnerBlocks(option.blocks, context, revealed);
                    return { optionId: option.optionId, blocks: blocks.length > 0 ? blocks : empty };
                }) } };
        }
        case 'MATCH': {
            const side = (items: readonly MatchItem[]) => items.map(item => {
                const blocks = learnerBlocks(item.blocks, context, revealed);
                return { itemId: item.itemId, blocks: blocks.length > 0 ? blocks
                    : placeholderText(placeholders ? PREVIEW_PLACEHOLDERS.item : 'Пустой элемент') };
            });
            const right = side(exercise.content.right);
            // Rotate the right column so the preview does not show every pair aligned.
            const rotated = right.length > 1 ? [...right.slice(1), right[0]] : right;
            return { type: exercise.type, content: { prompt: promptBlocks(exercise.content.prompt, projection, false),
                left: side(exercise.content.left), right: rotated } };
        }
        case 'ORDER': {
            const items = exercise.content.items.map(item => {
                const blocks = learnerBlocks(item.blocks, context, revealed);
                return { itemId: item.itemId, blocks: blocks.length > 0 ? blocks
                    : placeholderText(placeholders ? PREVIEW_PLACEHOLDERS.orderItem : 'Пустой элемент') };
            });
            // Rotate by one so the preview never opens in the authored (correct) order; the real order is Study's secure shuffle.
            const shown = items.length > 1 ? [...items.slice(1), items[0]] : items;
            return { type: exercise.type, content: { prompt: promptBlocks(exercise.content.prompt, projection, false), items: shown } };
        }
        case 'CATEGORIZE': {
            const items = exercise.content.items.map(item => {
                const blocks = learnerBlocks(item.blocks, context, revealed);
                return { itemId: item.itemId, blocks: blocks.length > 0 ? blocks
                    : placeholderText(placeholders ? PREVIEW_PLACEHOLDERS.item : 'Пустой элемент') };
            });
            const shown = items.length > 1 ? [...items.slice(1), items[0]] : items;
            return { type: exercise.type, content: { prompt: promptBlocks(exercise.content.prompt, projection, false),
                categories: exercise.content.categories.map((category, index) => ({ categoryId: category.categoryId,
                    label: isBlank(category.label) ? (placeholders ? `${PREVIEW_PLACEHOLDERS.group} ${index + 1}` : `Группа ${index + 1}`)
                        : category.label })),
                items: shown } };
        }
    }
}
