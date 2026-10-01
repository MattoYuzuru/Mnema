import {
    AiRubric, AuthoringBlock, ChoiceOption, ClozeBlankKey, ClozeSegment, ClozeSize, COMPACT_SLOT, ExerciseSpec,
    ExerciseSubject, LIMITS, LearnerBlock, LearnerClozeSegment, LearnerContent, MatchingMode, MatchItem, Mechanic, NORMALIZATION_RULES,
    NormalizationRule, PROMPT_SLOTS, REFERENCE_SLOTS, ResponseInput, SLOT_PROFILES, SelectionMode, SlotSpec, authoringSlots,
    codePointLength, isBlank, mediaBlockCount
} from '../../content/exercise/exercise-content.models';
import { blockProblem, isEntityId, slotProblem } from '../../content/exercise/exercise-content.parse';
import { NativeDocument } from '../../content/native-document';
import { ExerciseDetail, ExerciseProjection } from './exercise.models';

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
    /** Present only when an existing exercise was authored with AI checking; this editor cannot create one. */
    readonly aiRubric: AiRubric | null;
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

export interface ExerciseDrafts {
    readonly SELF_CHECK: SelfCheckDraft;
    readonly FREE_RESPONSE: FreeResponseDraft;
    readonly CLOZE: ClozeDraft;
    readonly CHOICE: ChoiceDraft;
    readonly MATCH: MatchDraft;
}

/** What slot editors and validation need to know about the material the exercise is attached to. */
export interface SlotContext {
    readonly document: NativeDocument;
    readonly memberKey: string;
    readonly itemRevisionId: string;
    readonly projections: readonly ExerciseProjection[];
}

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

export function emptyDrafts(): ExerciseDrafts {
    return {
        SELF_CHECK: { prompt: [textBlock()], reference: [textBlock()] },
        FREE_RESPONSE: { prompt: [textBlock()], reference: [], answer: newAnswer(), responseInput: 'TEXT', aiRubric: null },
        CLOZE: { prompt: [], texts: [''], blanks: [] },
        CHOICE: { prompt: [textBlock()], selectionMode: 'SINGLE', options: [newOption(), newOption()], correctIds: [] },
        MATCH: { prompt: [], pairs: [newPair(), newPair()] }
    };
}

// ---------------------------------------------------------------------------------------------
// Draft <-> specification
// ---------------------------------------------------------------------------------------------

function answerDraft(answer: { readonly accepted: readonly string[]; readonly normalization: readonly NormalizationRule[];
    readonly matchingMode: MatchingMode }): TextAnswerDraft {
    return { rows: answer.accepted.map(value => ({ id: newId(), value })), normalization: answer.normalization, matchingMode: answer.matchingMode };
}

/** Loads a persisted exercise into the drafts, leaving the other mechanics at their defaults. */
export function draftsFromDetail(detail: ExerciseDetail): ExerciseDrafts {
    const drafts = emptyDrafts();
    switch (detail.type) {
        case 'SELF_CHECK': return { ...drafts, SELF_CHECK: { prompt: detail.content.prompt, reference: detail.content.reference } };
        case 'FREE_RESPONSE': return { ...drafts, FREE_RESPONSE: { prompt: detail.content.prompt, reference: detail.content.reference,
            answer: answerDraft(detail.answerKey), responseInput: detail.content.responseInput,
            aiRubric: detail.evaluatorPolicy.id === 'ai-semantic' ? detail.evaluatorPolicy.rubric : null } };
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

/** Builds the publication payload for the selected mechanic. It may be invalid until `validateDraft` passes. */
export function buildSpec(type: Mechanic, drafts: ExerciseDrafts, subject: ExerciseSubject, enabled: boolean): ExerciseSpec {
    const base = { schemaVersion: 2 as const, enabled, subject };
    switch (type) {
        case 'SELF_CHECK': return { ...base, type, content: drafts.SELF_CHECK, answerKey: { kind: 'SELF_REPORT' },
            evaluatorPolicy: { id: 'self-check', version: '1' } };
        case 'FREE_RESPONSE': {
            const draft = drafts.FREE_RESPONSE;
            return { ...base, type, content: { prompt: draft.prompt, reference: draft.reference, responseInput: draft.responseInput },
                answerKey: { kind: 'TEXT', ...textKey(draft.answer) },
                evaluatorPolicy: draft.aiRubric === null ? { id: 'deterministic-text', version: '1' }
                    : { id: 'ai-semantic', version: '1', rubric: draft.aiRubric } };
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
    }
}

// ---------------------------------------------------------------------------------------------
// Validation: author-facing, one message per field key
// ---------------------------------------------------------------------------------------------

/** Resolved text of a MATERIAL block, or null when the fragment is not in the loaded revision. */
export function materialText(block: AuthoringBlock, context: SlotContext): string | null {
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
            const message = answerErrorMessage(draft.answer, LIMITS.freeResponseAccepted);
            if (message !== null) errors['accepted'] = message;
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
// Local preview: draft -> learner content, with no server involved
// ---------------------------------------------------------------------------------------------

function learnerBlocks(blocks: readonly AuthoringBlock[], context: SlotContext, revealed: boolean): readonly LearnerBlock[] {
    return blocks.flatMap((block): LearnerBlock[] => {
        switch (block.kind) {
            case 'TEXT': return isBlank(block.text) ? [] : [{ kind: 'TEXT', text: block.text }];
            case 'MATERIAL': {
                const text = materialText(block, context);
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

function compactBlocks(blocks: readonly AuthoringBlock[], context: SlotContext, revealed: boolean): readonly LearnerBlock[] {
    const result = learnerBlocks(blocks, context, revealed);
    return result.length > 0 ? result : [{ kind: 'TEXT', text: 'Пустой элемент' }];
}

/** Learner view of the current draft. Incomplete parts are skipped so the preview never blocks editing. */
export function previewContent(type: Mechanic, drafts: ExerciseDrafts, context: SlotContext, revealed: boolean): LearnerContent {
    switch (type) {
        case 'SELF_CHECK': return { type, content: { prompt: learnerBlocks(drafts.SELF_CHECK.prompt, context, revealed),
            reference: learnerBlocks(drafts.SELF_CHECK.reference, context, revealed) } };
        case 'FREE_RESPONSE': return { type, content: { prompt: learnerBlocks(drafts.FREE_RESPONSE.prompt, context, revealed),
            responseInput: drafts.FREE_RESPONSE.responseInput } };
        case 'CLOZE': {
            const draft = drafts.CLOZE;
            const passage = clozePassage(draft).map((segment): LearnerClozeSegment => {
                if (segment.kind === 'TEXT') return segment;
                const blank = draft.blanks.find(entry => entry.blankId === segment.blankId)!;
                const first = blank.answer.rows[0]?.value ?? '';
                const length = segment.size.mode === 'FIXED' ? segment.size.length : Math.max(1, codePointLength(first));
                return { kind: 'BLANK', blankId: segment.blankId, size: { mode: segment.size.mode, length },
                    firstLetterHint: segment.firstLetterHint };
            });
            return { type, content: { prompt: learnerBlocks(draft.prompt, context, revealed), passage } };
        }
        case 'CHOICE': return { type, content: { prompt: learnerBlocks(drafts.CHOICE.prompt, context, revealed),
            selectionMode: drafts.CHOICE.selectionMode,
            options: drafts.CHOICE.options.map(option => ({ optionId: option.optionId, blocks: compactBlocks(option.blocks, context, revealed) })) } };
        case 'MATCH': {
            const pairs = drafts.MATCH.pairs;
            const left = pairs.map(pair => ({ itemId: pair.left.itemId, blocks: compactBlocks(pair.left.blocks, context, revealed) }));
            const right = pairs.map(pair => ({ itemId: pair.right.itemId, blocks: compactBlocks(pair.right.blocks, context, revealed) }));
            // Rotate the right column so the preview does not show every pair aligned.
            const rotated = right.length > 1 ? [...right.slice(1), right[0]] : right;
            return { type, content: { prompt: learnerBlocks(drafts.MATCH.prompt, context, revealed), left, right: rotated } };
        }
    }
}

/** Whether the draft's pair is a correct pairing; used by the local preview pair check. */
export function isPreviewPair(drafts: ExerciseDrafts, leftId: string, rightId: string): boolean {
    return drafts.MATCH.pairs.some(pair => pair.left.itemId === leftId && pair.right.itemId === rightId);
}
