import { isCanonicalEntityId } from '../../features/own-decks/own-deck.models';
import {
    AI_LEVELS, AiRubric, AuthoringBlock, ChoiceOption, ClozeBlankKey, ClozeBlankSegment, ClozeSegment, ClozeSize,
    COMPACT_SLOT, Category, CategorizeItem, ExerciseSpec, LIMITS, LearnerBlock, LearnerClozeSegment, LearnerContent, MatchItem,
    MECHANICS, Mechanic, NORMALIZATION_RULES, NormalizationRule, ObjectiveCommand, OrderItem, PROMPT_SLOTS, REFERENCE_SLOTS,
    SEQUENCE_SLOT, SLOT_PROFILES, SlotProfile, SlotSpec, authoringSlots, categoryLabelKey, codePointLength, distinguishableItems, isBlank,
    mediaBlockCount
} from './exercise-content.models';

/** Thrown for any shape or invariant violation; services translate it into their own protocol error. */
export class ExerciseContentError extends Error {
    constructor(message: string) {
        super(message);
        this.name = 'ExerciseContentError';
    }
}

const VIDEO_ID = /^[A-Za-z0-9_-]{11}$/u;

export function isEntityId(value: unknown): value is string {
    return typeof value === 'string' && isCanonicalEntityId(value);
}

function isYoutubeId(value: unknown): value is string {
    return typeof value === 'string' && VIDEO_ID.test(value);
}

// ---------------------------------------------------------------------------------------------
// Block and slot rules. The same functions produce author-facing messages and parser errors.
// ---------------------------------------------------------------------------------------------

/** Returns an author-facing problem for one typed block, or null when the block is valid in the profile. */
export function blockProblem(block: AuthoringBlock, profile: SlotProfile): string | null {
    const rules = SLOT_PROFILES[profile];
    if (!rules.kinds.includes(block.kind)) return 'Этот вид блока здесь недоступен.';
    switch (block.kind) {
        case 'TEXT':
            if (isBlank(block.text)) return 'Введите текст блока или удалите его.';
            if (block.text.length > rules.textLimit) {
                return `Текст длиннее ${rules.textLimit} знаков (сейчас ${block.text.length}). Сократите его: Mnema ничего не обрезает.`;
            }
            return null;
        case 'MATERIAL':
            return isEntityId(block.memberKey) && isEntityId(block.itemRevisionId) && isEntityId(block.nodeId)
                ? null : 'Выберите фрагмент материала.';
        case 'IMAGE':
            if (!isEntityId(block.assetId)) return 'Выберите изображение.';
            if (isBlank(block.alt)) return 'Опишите изображение: альтернативный текст обязателен.';
            return block.alt.length > LIMITS.mediaLabel ? `Описание изображения не длиннее ${LIMITS.mediaLabel} знаков.` : null;
        case 'AUDIO':
        case 'VIDEO':
            if (!isEntityId(block.assetId)) return block.kind === 'AUDIO' ? 'Выберите аудио.' : 'Выберите видео.';
            if (isBlank(block.title)) return 'Укажите название. Его видит только автор.';
            if (block.title.length > LIMITS.mediaLabel) return `Название не длиннее ${LIMITS.mediaLabel} знаков.`;
            if (block.transcript !== undefined && isBlank(block.transcript)) {
                return 'Расшифровка не может быть пустой: заполните её или очистите поле.';
            }
            return block.transcript !== undefined && block.transcript.length > LIMITS.transcript
                ? `Расшифровка длиннее ${LIMITS.transcript} знаков.` : null;
        case 'YOUTUBE':
            if (!isYoutubeId(block.videoId)) return 'Укажите корректную ссылку или идентификатор YouTube.';
            if (isBlank(block.title)) return 'Укажите название ролика.';
            return block.title.length > LIMITS.mediaLabel ? `Название не длиннее ${LIMITS.mediaLabel} знаков.` : null;
    }
}

const isMedia = (kind: string): boolean => kind === 'IMAGE' || kind === 'AUDIO' || kind === 'VIDEO';

/** Count and composition rules of one slot (not the per-block rules). */
export function slotProblem(blocks: readonly { readonly kind: string }[], spec: SlotSpec): string | null {
    if (blocks.length < spec.min || blocks.length > spec.max) {
        return spec.min === spec.max ? `Нужно блоков: ${spec.min}.`
            : `Нужно блоков: от ${spec.min} до ${spec.max} (сейчас ${blocks.length}).`;
    }
    if (SLOT_PROFILES[spec.profile].oneTextAndOneMedia) {
        const text = blocks.filter(block => block.kind === 'TEXT' || block.kind === 'MATERIAL').length;
        const media = blocks.filter(block => isMedia(block.kind)).length;
        if (text > 1 || media > 1) return 'В компактном поле — не больше одного текста и одного изображения, аудио или видео.';
    }
    return null;
}

// ---------------------------------------------------------------------------------------------
// Strict structural helpers
// ---------------------------------------------------------------------------------------------

function fail(message: string): never { throw new ExerciseContentError(message); }

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** Exactly the required keys plus any subset of the optional keys; nothing else. */
export function exactObject(value: unknown, required: readonly string[], optional: readonly string[] = []): Record<string, unknown> {
    if (!isRecord(value)) fail('Expected object.');
    const keys = Object.keys(value);
    if (required.some(key => !keys.includes(key)) || keys.some(key => !required.includes(key) && !optional.includes(key))) {
        fail('Unexpected object shape.');
    }
    return value;
}

function array(value: unknown, min: number, max: number): readonly unknown[] {
    if (!Array.isArray(value) || value.length < min || value.length > max) fail('Invalid array size.');
    return value;
}

function id(value: unknown): string {
    if (!isEntityId(value)) fail('Invalid identifier.');
    return value;
}

function text(value: unknown, minLength: number, maxLength: number): string {
    if (typeof value !== 'string' || value.length < minLength || value.length > maxLength) fail('Invalid text value.');
    return value;
}

function nonblank(value: unknown, maxLength: number): string {
    const result = text(value, 1, maxLength);
    if (isBlank(result)) fail('Blank text.');
    return result;
}

function bool(value: unknown): boolean {
    if (typeof value !== 'boolean') fail('Expected boolean.');
    return value;
}

function unique(values: readonly string[]): void {
    if (new Set(values).size !== values.length) fail('Duplicate value.');
}

function oneOf<T extends string>(value: unknown, allowed: readonly T[]): T {
    if (typeof value !== 'string' || !allowed.includes(value as T)) fail('Unexpected value.');
    return value as T;
}

// ---------------------------------------------------------------------------------------------
// Authoring blocks and exercises
// ---------------------------------------------------------------------------------------------

function authoringBlock(value: unknown, profile: SlotProfile): AuthoringBlock {
    if (!isRecord(value)) fail('Expected block.');
    const kind = oneOf(value['kind'], ['TEXT', 'MATERIAL', 'IMAGE', 'AUDIO', 'VIDEO', 'YOUTUBE'] as const);
    let block: AuthoringBlock;
    switch (kind) {
        case 'TEXT': {
            const object = exactObject(value, ['kind', 'text']);
            block = { kind, text: text(object['text'], 0, Number.MAX_SAFE_INTEGER) };
            break;
        }
        case 'MATERIAL': {
            const object = exactObject(value, ['kind', 'memberKey', 'itemRevisionId', 'nodeId']);
            block = { kind, memberKey: id(object['memberKey']), itemRevisionId: id(object['itemRevisionId']),
                nodeId: id(object['nodeId']) };
            break;
        }
        case 'IMAGE': {
            const object = exactObject(value, ['kind', 'assetId', 'alt']);
            block = { kind, assetId: id(object['assetId']), alt: text(object['alt'], 0, Number.MAX_SAFE_INTEGER) };
            break;
        }
        case 'AUDIO':
        case 'VIDEO': {
            const object = exactObject(value, ['kind', 'assetId', 'title'], ['transcript']);
            block = { kind, assetId: id(object['assetId']), title: text(object['title'], 0, Number.MAX_SAFE_INTEGER),
                ...(object['transcript'] === undefined ? {} : { transcript: text(object['transcript'], 0, Number.MAX_SAFE_INTEGER) }) };
            break;
        }
        case 'YOUTUBE': {
            const object = exactObject(value, ['kind', 'videoId', 'title']);
            block = { kind, videoId: text(object['videoId'], 0, 64), title: text(object['title'], 0, Number.MAX_SAFE_INTEGER) };
            break;
        }
    }
    if (blockProblem(block, profile) !== null) fail('Invalid block.');
    return block;
}

function authoringSlot(value: unknown, spec: SlotSpec): readonly AuthoringBlock[] {
    const blocks = array(value, spec.min, spec.max).map(entry => authoringBlock(entry, spec.profile));
    if (slotProblem(blocks, spec) !== null) fail('Invalid slot composition.');
    return blocks;
}

function normalizationRules(value: unknown): readonly NormalizationRule[] {
    const rules = array(value, 1, NORMALIZATION_RULES.length).map(rule => oneOf(rule, NORMALIZATION_RULES));
    unique(rules);
    return rules;
}

function textAnswer(object: Record<string, unknown>, limits: { readonly min: number; readonly max: number; readonly length: number }) {
    const accepted = array(object['accepted'], limits.min, limits.max).map(entry => nonblank(entry, limits.length));
    unique(accepted);
    const matchingMode = oneOf(object['matchingMode'], ['STRICT', 'SOFT'] as const);
    if (matchingMode === 'SOFT' && accepted.some(entry => !/[\p{L}\p{N}]/u.test(entry))) fail('Soft answer without letters.');
    return { accepted, normalization: normalizationRules(object['normalization']), matchingMode };
}

function aiRubric(value: unknown): AiRubric {
    const object = exactObject(value, ['referenceAnswer', 'criteria', 'levels']);
    const criteria = array(object['criteria'], LIMITS.aiCriteria.min, LIMITS.aiCriteria.max).map(entry => {
        const criterion = exactObject(entry, ['criterionId', 'description', 'critical']);
        return { criterionId: id(criterion['criterionId']),
            description: nonblank(criterion['description'], LIMITS.aiCriteria.description), critical: bool(criterion['critical']) };
    });
    unique(criteria.map(entry => entry.criterionId));
    const levels = array(object['levels'], AI_LEVELS.length, AI_LEVELS.length).map(entry => {
        const level = exactObject(entry, ['level', 'description']);
        return { level: oneOf(level['level'], AI_LEVELS), description: nonblank(level['description'], LIMITS.aiCriteria.description) };
    });
    if (levels.some((entry, index) => entry.level !== AI_LEVELS[index])) fail('Levels must follow the contract order.');
    return { referenceAnswer: nonblank(object['referenceAnswer'], LIMITS.aiReferenceAnswer), criteria, levels };
}

function evaluator<I extends string>(value: unknown, expected: I): { readonly id: I; readonly version: '1' } {
    const object = exactObject(value, ['id', 'version']);
    if (object['id'] !== expected || object['version'] !== '1') fail('Evaluator does not match the mechanic.');
    return { id: expected, version: '1' };
}

function clozeSize(value: unknown): ClozeSize {
    const object = exactObject(value, ['mode'], ['length']);
    if (object['mode'] === 'ANSWER_LENGTH' && object['length'] === undefined) return { mode: 'ANSWER_LENGTH' };
    const length = object['length'];
    if (object['mode'] === 'FIXED' && typeof length === 'number' && Number.isInteger(length)
        && length >= LIMITS.clozeFixedLength.min && length <= LIMITS.clozeFixedLength.max) return { mode: 'FIXED', length };
    return fail('Invalid blank size.');
}

function clozePassage(value: unknown): readonly ClozeSegment[] {
    const passage = array(value, LIMITS.clozeSegments.min, LIMITS.clozeSegments.max).map((entry): ClozeSegment => {
        if (!isRecord(entry)) return fail('Expected segment.');
        if (entry['kind'] === 'TEXT') {
            const object = exactObject(entry, ['kind', 'text']);
            return { kind: 'TEXT', text: text(object['text'], 1, LIMITS.clozeTotalText) };
        }
        const object = exactObject(entry, ['kind', 'blankId', 'size', 'firstLetterHint']);
        if (object['kind'] !== 'BLANK') return fail('Unknown passage segment.');
        return { kind: 'BLANK', blankId: id(object['blankId']), size: clozeSize(object['size']),
            firstLetterHint: bool(object['firstLetterHint']) };
    });
    const blanks = passage.filter((segment): segment is ClozeBlankSegment => segment.kind === 'BLANK');
    const total = passage.reduce((sum, segment) => sum + (segment.kind === 'TEXT' ? segment.text.length : 0), 0);
    if (blanks.length < LIMITS.clozeBlanks.min || blanks.length > LIMITS.clozeBlanks.max
        || blanks.length === passage.length || total > LIMITS.clozeTotalText) fail('Invalid cloze passage.');
    unique(blanks.map(blank => blank.blankId));
    return passage;
}

function choiceOptions(value: unknown): readonly ChoiceOption[] {
    const options = array(value, LIMITS.choiceOptions.min, LIMITS.choiceOptions.max).map(entry => {
        const object = exactObject(entry, ['optionId', 'blocks']);
        return { optionId: id(object['optionId']), blocks: authoringSlot(object['blocks'], COMPACT_SLOT) };
    });
    unique(options.map(option => option.optionId));
    return options;
}

function matchSide(value: unknown): readonly MatchItem[] {
    return array(value, LIMITS.matchPairs.min, LIMITS.matchPairs.max).map(entry => {
        const object = exactObject(entry, ['itemId', 'blocks']);
        return { itemId: id(object['itemId']), blocks: authoringSlot(object['blocks'], COMPACT_SLOT) };
    });
}

function orderItems(value: unknown): readonly OrderItem[] {
    const items = array(value, LIMITS.orderItems.min, LIMITS.orderItems.max).map(entry => {
        const object = exactObject(entry, ['itemId', 'blocks']);
        return { itemId: id(object['itemId']), blocks: authoringSlot(object['blocks'], SEQUENCE_SLOT) };
    });
    unique(items.map(item => item.itemId));
    return items;
}

function categories(value: unknown): readonly Category[] {
    const result = array(value, LIMITS.categories.min, LIMITS.categories.max).map(entry => {
        const object = exactObject(entry, ['categoryId', 'label']);
        const label = nonblank(object['label'], LIMITS.categories.label);
        if (categoryLabelKey(label) === '') fail('Label without a visible character.');
        return { categoryId: id(object['categoryId']), label };
    });
    unique(result.map(category => category.categoryId));
    unique(result.map(category => categoryLabelKey(category.label)));
    return result;
}

function categorizeItems(value: unknown): readonly CategorizeItem[] {
    const items = array(value, LIMITS.categorizeItems.min, LIMITS.categorizeItems.max).map(entry => {
        const object = exactObject(entry, ['itemId', 'blocks']);
        return { itemId: id(object['itemId']), blocks: authoringSlot(object['blocks'], COMPACT_SLOT) };
    });
    unique(items.map(item => item.itemId));
    return items;
}

/** Strictly parses the exercise part of a publication command or of an exercise detail. */
export function parseExerciseSpec(value: unknown): ExerciseSpec {
    const object = exactObject(value, ['type', 'schemaVersion', 'enabled', 'subject', 'content', 'answerKey', 'evaluatorPolicy']);
    const type: Mechanic = oneOf(object['type'], MECHANICS);
    if (object['schemaVersion'] !== 2) fail('Unsupported exercise schema.');
    const subject = exactObject(object['subject'], ['memberKey', 'itemRevisionId']);
    const base = { schemaVersion: 2 as const, enabled: bool(object['enabled']),
        subject: { memberKey: id(subject['memberKey']), itemRevisionId: id(subject['itemRevisionId']) } };
    const spec = parseByType(type, base, object);
    const slots = authoringSlots(spec);
    if (slots.reduce((sum, blocks) => sum + mediaBlockCount(blocks), 0) > LIMITS.mediaBlocksPerExercise) {
        fail('Too many media blocks.');
    }
    const kinds = new Map<string, string>();
    for (const block of slots.flat()) {
        if (block.kind !== 'IMAGE' && block.kind !== 'AUDIO' && block.kind !== 'VIDEO') continue;
        if (kinds.get(block.assetId) !== undefined && kinds.get(block.assetId) !== block.kind) fail('Asset used as two media kinds.');
        kinds.set(block.assetId, block.kind);
    }
    return spec;
}

function parseByType(type: Mechanic, base: { readonly schemaVersion: 2; readonly enabled: boolean;
    readonly subject: { readonly memberKey: string; readonly itemRevisionId: string } },
    object: Record<string, unknown>): ExerciseSpec {
    switch (type) {
        case 'SELF_CHECK': {
            const content = exactObject(object['content'], ['prompt', 'reference']);
            exactObject(object['answerKey'], ['kind']);
            if ((object['answerKey'] as Record<string, unknown>)['kind'] !== 'SELF_REPORT') fail('Unexpected answer key.');
            return { ...base, type, content: { prompt: authoringSlot(content['prompt'], PROMPT_SLOTS[type]),
                reference: authoringSlot(content['reference'], REFERENCE_SLOTS[type]) },
                answerKey: { kind: 'SELF_REPORT' }, evaluatorPolicy: evaluator(object['evaluatorPolicy'], 'self-check') };
        }
        case 'FREE_RESPONSE': {
            const content = exactObject(object['content'], ['prompt', 'reference', 'responseInput']);
            const key = exactObject(object['answerKey'], ['kind', 'accepted', 'normalization', 'matchingMode']);
            if (key['kind'] !== 'TEXT') fail('Unexpected answer key.');
            const policy = isRecord(object['evaluatorPolicy']) && object['evaluatorPolicy']['id'] === 'ai-semantic'
                ? aiPolicy(object['evaluatorPolicy']) : evaluator(object['evaluatorPolicy'], 'deterministic-text');
            return { ...base, type, content: { prompt: authoringSlot(content['prompt'], PROMPT_SLOTS[type]),
                reference: authoringSlot(content['reference'], REFERENCE_SLOTS[type]),
                responseInput: oneOf(content['responseInput'], ['TEXT', 'TEXT_OR_SPEECH'] as const) },
                answerKey: { kind: 'TEXT', ...textAnswer(key, LIMITS.freeResponseAccepted) }, evaluatorPolicy: policy };
        }
        case 'CLOZE': {
            const content = exactObject(object['content'], ['prompt', 'passage']);
            const key = exactObject(object['answerKey'], ['kind', 'blanks']);
            if (key['kind'] !== 'CLOZE') fail('Unexpected answer key.');
            const passage = clozePassage(content['passage']);
            const blanks = passage.filter((segment): segment is ClozeBlankSegment => segment.kind === 'BLANK');
            const keys: ClozeBlankKey[] = array(key['blanks'], blanks.length, blanks.length).map(entry => {
                const blank = exactObject(entry, ['blankId', 'accepted', 'normalization', 'matchingMode']);
                return { blankId: id(blank['blankId']), ...textAnswer(blank, LIMITS.clozeAccepted) };
            });
            unique(keys.map(entry => entry.blankId));
            for (const blank of blanks) {
                const answer = keys.find(entry => entry.blankId === blank.blankId);
                if (answer === undefined) fail('Missing blank answer.');
                if (blank.size.mode === 'ANSWER_LENGTH') {
                    const lengths = new Set(answer.accepted.map(codePointLength));
                    const [length] = [...lengths];
                    if (lengths.size !== 1 || length < LIMITS.clozeAnswerLength.min || length > LIMITS.clozeAnswerLength.max) {
                        fail('Blank answers must have one length.');
                    }
                }
            }
            return { ...base, type, content: { prompt: authoringSlot(content['prompt'], PROMPT_SLOTS[type]), passage },
                answerKey: { kind: 'CLOZE', blanks: keys }, evaluatorPolicy: evaluator(object['evaluatorPolicy'], 'deterministic-cloze') };
        }
        case 'CHOICE': {
            const content = exactObject(object['content'], ['prompt', 'selectionMode', 'options']);
            const key = exactObject(object['answerKey'], ['kind', 'correctOptionIds']);
            if (key['kind'] !== 'CHOICE') fail('Unexpected answer key.');
            const selectionMode = oneOf(content['selectionMode'], ['SINGLE', 'MULTIPLE'] as const);
            const options = choiceOptions(content['options']);
            const correct = array(key['correctOptionIds'], 1, options.length).map(id);
            unique(correct);
            if (correct.some(optionId => !options.some(option => option.optionId === optionId))
                || selectionMode === 'SINGLE' && correct.length !== 1) fail('Invalid correct options.');
            return { ...base, type, content: { prompt: authoringSlot(content['prompt'], PROMPT_SLOTS[type]), selectionMode, options },
                answerKey: { kind: 'CHOICE', correctOptionIds: correct },
                evaluatorPolicy: evaluator(object['evaluatorPolicy'], 'deterministic-choice') };
        }
        case 'MATCH': {
            const content = exactObject(object['content'], ['prompt', 'left', 'right']);
            const key = exactObject(object['answerKey'], ['kind', 'pairs']);
            if (key['kind'] !== 'MATCH') fail('Unexpected answer key.');
            const left = matchSide(content['left']);
            const right = matchSide(content['right']);
            if (left.length !== right.length) fail('Sides must have equal sizes.');
            unique([...left, ...right].map(item => item.itemId));
            const pairs = array(key['pairs'], left.length, left.length).map(entry => {
                const pair = exactObject(entry, ['leftId', 'rightId']);
                return { leftId: id(pair['leftId']), rightId: id(pair['rightId']) };
            });
            unique(pairs.map(pair => pair.leftId));
            unique(pairs.map(pair => pair.rightId));
            if (pairs.some(pair => !left.some(item => item.itemId === pair.leftId)
                || !right.some(item => item.itemId === pair.rightId))) fail('Pairs must use issued ids.');
            return { ...base, type, content: { prompt: authoringSlot(content['prompt'], PROMPT_SLOTS[type]), left, right },
                answerKey: { kind: 'MATCH', pairs }, evaluatorPolicy: evaluator(object['evaluatorPolicy'], 'deterministic-match') };
        }
        case 'ORDER': {
            const content = exactObject(object['content'], ['prompt', 'items']);
            const key = exactObject(object['answerKey'], ['kind', 'sequence']);
            if (key['kind'] !== 'ORDER') fail('Unexpected answer key.');
            const items = orderItems(content['items']);
            if (distinguishableItems(items) < 2) fail('An order needs two distinguishable items.');
            const sequence = array(key['sequence'], items.length, items.length).map(id);
            unique(sequence);
            if (sequence.some(itemId => !items.some(item => item.itemId === itemId))) fail('Sequence must use item ids.');
            return { ...base, type, content: { prompt: authoringSlot(content['prompt'], PROMPT_SLOTS[type]), items },
                answerKey: { kind: 'ORDER', sequence }, evaluatorPolicy: evaluator(object['evaluatorPolicy'], 'deterministic-order') };
        }
        case 'CATEGORIZE': {
            const content = exactObject(object['content'], ['prompt', 'categories', 'items']);
            const key = exactObject(object['answerKey'], ['kind', 'assignments']);
            if (key['kind'] !== 'CATEGORIZE') fail('Unexpected answer key.');
            const groups = categories(content['categories']);
            const items = categorizeItems(content['items']);
            const assignments = array(key['assignments'], items.length, items.length).map(entry => {
                const assignment = exactObject(entry, ['itemId', 'categoryId']);
                return { itemId: id(assignment['itemId']), categoryId: id(assignment['categoryId']) };
            });
            unique(assignments.map(assignment => assignment.itemId));
            if (assignments.some(assignment => !items.some(item => item.itemId === assignment.itemId)
                || !groups.some(group => group.categoryId === assignment.categoryId))) fail('Assignments must use issued ids.');
            return { ...base, type, content: { prompt: authoringSlot(content['prompt'], PROMPT_SLOTS[type]), categories: groups, items },
                answerKey: { kind: 'CATEGORIZE', assignments },
                evaluatorPolicy: evaluator(object['evaluatorPolicy'], 'deterministic-categorize') };
        }
    }
}

function aiPolicy(value: unknown) {
    const object = exactObject(value, ['id', 'version', 'rubric']);
    if (object['id'] !== 'ai-semantic' || object['version'] !== '1') fail('Unexpected evaluator.');
    return { id: 'ai-semantic' as const, version: '1' as const, rubric: aiRubric(object['rubric']) };
}

/** Strictly parses the objective part of a publication command. */
export function parseObjectiveCommand(value: unknown): ObjectiveCommand {
    if (!isRecord(value)) fail('Expected objective.');
    switch (value['operation']) {
        case 'create': {
            const object = exactObject(value, ['operation', 'title']);
            return { operation: 'create', title: nonblank(object['title'], LIMITS.objectiveTitle) };
        }
        case 'reuse': {
            const object = exactObject(value, ['operation', 'objectiveId', 'objectiveRevisionId']);
            return { operation: 'reuse', objectiveId: id(object['objectiveId']), objectiveRevisionId: id(object['objectiveRevisionId']) };
        }
        case 'revise': {
            const object = exactObject(value, ['operation', 'objectiveId', 'expectedObjectiveRevisionId', 'title']);
            return { operation: 'revise', objectiveId: id(object['objectiveId']),
                expectedObjectiveRevisionId: id(object['expectedObjectiveRevisionId']),
                title: nonblank(object['title'], LIMITS.objectiveTitle) };
        }
        default: return fail('Unknown objective operation.');
    }
}

// ---------------------------------------------------------------------------------------------
// Learner blocks and content (Study presentations, transcript reveal, feedback reference content)
// ---------------------------------------------------------------------------------------------

/**
 * Parses one learner block. `revealed` tells whether transcripts are permitted: `false` rejects any
 * transcript, `true` requires one on every block that advertises it, `null` only checks consistency.
 */
export function parseLearnerBlock(value: unknown, profile: SlotProfile, revealed: boolean | null): LearnerBlock {
    if (!isRecord(value)) fail('Expected learner block.');
    const kind = oneOf(value['kind'], ['TEXT', 'IMAGE', 'AUDIO', 'VIDEO', 'YOUTUBE'] as const);
    if (!SLOT_PROFILES[profile].kinds.includes(kind)) fail('Block kind is not allowed here.');
    const limit = SLOT_PROFILES[profile].textLimit;
    switch (kind) {
        case 'TEXT': {
            const object = exactObject(value, ['kind', 'text']);
            return { kind, text: nonblank(object['text'], limit) };
        }
        case 'IMAGE': {
            const object = exactObject(value, ['kind', 'assetId', 'alt']);
            return { kind, assetId: id(object['assetId']), alt: nonblank(object['alt'], LIMITS.mediaLabel) };
        }
        case 'AUDIO':
        case 'VIDEO': {
            const object = exactObject(value, ['kind', 'assetId', 'transcriptAvailable'], ['transcript']);
            const transcriptAvailable = bool(object['transcriptAvailable']);
            const hasTranscript = object['transcript'] !== undefined;
            if (hasTranscript && (!transcriptAvailable || revealed === false)) fail('Transcript was not revealed.');
            if (revealed === true && transcriptAvailable && !hasTranscript) fail('Revealed transcript is missing.');
            return { kind, assetId: id(object['assetId']), transcriptAvailable,
                ...(hasTranscript ? { transcript: nonblank(object['transcript'], LIMITS.transcript) } : {}) };
        }
        case 'YOUTUBE': {
            const object = exactObject(value, ['kind', 'videoId', 'title']);
            if (!isYoutubeId(object['videoId'])) fail('Invalid YouTube video.');
            return { kind, videoId: object['videoId'], title: nonblank(object['title'], LIMITS.mediaLabel) };
        }
    }
}

function parseLearnerSlot(value: unknown, spec: SlotSpec, revealed: boolean | null): readonly LearnerBlock[] {
    const blocks = array(value, spec.min, spec.max).map(entry => parseLearnerBlock(entry, spec.profile, revealed));
    if (slotProblem(blocks, spec) !== null) fail('Invalid slot composition.');
    return blocks;
}

function learnerItems(value: unknown, limits: { readonly min: number; readonly max: number }, slot: SlotSpec,
    revealed: boolean): readonly { readonly itemId: string; readonly blocks: readonly LearnerBlock[] }[] {
    const items = array(value, limits.min, limits.max).map(entry => {
        const object = exactObject(entry, ['itemId', 'blocks']);
        return { itemId: id(object['itemId']), blocks: parseLearnerSlot(object['blocks'], slot, revealed) };
    });
    unique(items.map(item => item.itemId));
    return items;
}

const learnerSide = (value: unknown, revealed: boolean) => learnerItems(value, LIMITS.matchPairs, COMPACT_SLOT, revealed);

/** Parses the learner-resolved content of one presentation; `type` selects the exact content shape. */
export function parseLearnerContent(type: Mechanic, value: unknown, revealed: boolean): LearnerContent {
    switch (type) {
        case 'SELF_CHECK': {
            const object = exactObject(value, ['prompt', 'reference']);
            return { type, content: { prompt: parseLearnerSlot(object['prompt'], PROMPT_SLOTS[type], revealed),
                reference: parseLearnerSlot(object['reference'], REFERENCE_SLOTS[type], revealed) } };
        }
        case 'FREE_RESPONSE': {
            const object = exactObject(value, ['prompt', 'responseInput']);
            return { type, content: { prompt: parseLearnerSlot(object['prompt'], PROMPT_SLOTS[type], revealed),
                responseInput: oneOf(object['responseInput'], ['TEXT', 'TEXT_OR_SPEECH'] as const) } };
        }
        case 'CLOZE': {
            const object = exactObject(value, ['prompt', 'passage']);
            const passage = array(object['passage'], LIMITS.clozeSegments.min, LIMITS.clozeSegments.max)
                .map((entry): LearnerClozeSegment => {
                    if (!isRecord(entry)) return fail('Expected segment.');
                    if (entry['kind'] === 'TEXT') {
                        return { kind: 'TEXT', text: text(exactObject(entry, ['kind', 'text'])['text'], 1, LIMITS.clozeTotalText) };
                    }
                    const blank = exactObject(entry, ['kind', 'blankId', 'size', 'firstLetterHint']);
                    if (blank['kind'] !== 'BLANK') return fail('Unknown passage segment.');
                    const size = exactObject(blank['size'], ['mode', 'length']);
                    const mode = oneOf(size['mode'], ['FIXED', 'ANSWER_LENGTH'] as const);
                    const length = size['length'];
                    const range = mode === 'FIXED' ? LIMITS.clozeFixedLength : LIMITS.clozeAnswerLength;
                    if (typeof length !== 'number' || !Number.isInteger(length) || length < range.min || length > range.max) {
                        fail('Invalid blank length.');
                    }
                    return { kind: 'BLANK', blankId: id(blank['blankId']), size: { mode, length },
                        firstLetterHint: bool(blank['firstLetterHint']) };
                });
            const blanks = passage.filter(segment => segment.kind === 'BLANK');
            if (blanks.length < LIMITS.clozeBlanks.min || blanks.length > LIMITS.clozeBlanks.max || blanks.length === passage.length) {
                fail('Invalid cloze passage.');
            }
            unique(blanks.map(blank => blank.blankId));
            return { type, content: { prompt: parseLearnerSlot(object['prompt'], PROMPT_SLOTS[type], revealed), passage } };
        }
        case 'CHOICE': {
            const object = exactObject(value, ['prompt', 'selectionMode', 'options']);
            const options = array(object['options'], LIMITS.choiceOptions.min, LIMITS.choiceOptions.max).map(entry => {
                const option = exactObject(entry, ['optionId', 'blocks']);
                return { optionId: id(option['optionId']), blocks: parseLearnerSlot(option['blocks'], COMPACT_SLOT, revealed) };
            });
            unique(options.map(option => option.optionId));
            return { type, content: { prompt: parseLearnerSlot(object['prompt'], PROMPT_SLOTS[type], revealed),
                selectionMode: oneOf(object['selectionMode'], ['SINGLE', 'MULTIPLE'] as const), options } };
        }
        case 'MATCH': {
            const object = exactObject(value, ['prompt', 'left', 'right']);
            const left = learnerSide(object['left'], revealed);
            const right = learnerSide(object['right'], revealed);
            if (left.length !== right.length) fail('Sides must have equal sizes.');
            unique([...left, ...right].map(item => item.itemId));
            return { type, content: { prompt: parseLearnerSlot(object['prompt'], PROMPT_SLOTS[type], revealed), left, right } };
        }
        case 'ORDER': {
            const object = exactObject(value, ['prompt', 'items']);
            return { type, content: { prompt: parseLearnerSlot(object['prompt'], PROMPT_SLOTS[type], revealed),
                items: learnerItems(object['items'], LIMITS.orderItems, SEQUENCE_SLOT, revealed) } };
        }
        case 'CATEGORIZE': {
            const object = exactObject(value, ['prompt', 'categories', 'items']);
            return { type, content: { prompt: parseLearnerSlot(object['prompt'], PROMPT_SLOTS[type], revealed),
                categories: categories(object['categories']), items: learnerItems(object['items'], LIMITS.categorizeItems, COMPACT_SLOT, revealed) } };
        }
    }
}
