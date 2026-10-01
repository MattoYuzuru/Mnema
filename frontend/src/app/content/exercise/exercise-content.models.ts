/**
 * Canonical exercise content contract shared by authoring and Study (contracts/study/mechanics.json).
 *
 * Content (what the learner sees) is separate from interaction (mechanic + settings) and from the
 * evaluation policy and answer key. Media kind is content, never a mechanic.
 * Every length below is measured in UTF-16 code units, exactly like the backend.
 */

export const MECHANICS = ['SELF_CHECK', 'FREE_RESPONSE', 'CLOZE', 'CHOICE', 'MATCH', 'ORDER', 'CATEGORIZE'] as const;
export type Mechanic = (typeof MECHANICS)[number];

// ---------------------------------------------------------------------------------------------
// Authoring blocks (what an author stores in an exercise revision)
// ---------------------------------------------------------------------------------------------

export interface TextBlock { readonly kind: 'TEXT'; readonly text: string; }
export interface MaterialBlock {
    readonly kind: 'MATERIAL';
    readonly memberKey: string;
    readonly itemRevisionId: string;
    readonly nodeId: string;
}
export interface ImageBlock { readonly kind: 'IMAGE'; readonly assetId: string; readonly alt: string; }
/** `title` is an author-only label and is never sent to the learner. */
export interface AudioBlock {
    readonly kind: 'AUDIO';
    readonly assetId: string;
    readonly title: string;
    readonly transcript?: string;
}
export interface VideoBlock {
    readonly kind: 'VIDEO';
    readonly assetId: string;
    readonly title: string;
    readonly transcript?: string;
}
export interface YoutubeBlock { readonly kind: 'YOUTUBE'; readonly videoId: string; readonly title: string; }

export type AuthoringBlock = TextBlock | MaterialBlock | ImageBlock | AudioBlock | VideoBlock | YoutubeBlock;
export type AuthoringBlockKind = AuthoringBlock['kind'];
export type MediaBlockKind = 'IMAGE' | 'AUDIO' | 'VIDEO';

// ---------------------------------------------------------------------------------------------
// Slot profiles (handoff section 2.2). Frontend shows these limits next to each field.
// ---------------------------------------------------------------------------------------------

export type SlotProfile = 'PROMPT' | 'REFERENCE' | 'COMPACT' | 'SEQUENCE';

export interface SlotProfileRules {
    readonly kinds: readonly AuthoringBlockKind[];
    /** Limit for one TEXT block or one resolved MATERIAL block. */
    readonly textLimit: number;
    /** COMPACT: at most one of {TEXT, MATERIAL} and at most one of {IMAGE, AUDIO, VIDEO}. */
    readonly oneTextAndOneMedia: boolean;
}

export const SLOT_PROFILES: Readonly<Record<SlotProfile, SlotProfileRules>> = {
    PROMPT: { kinds: ['TEXT', 'MATERIAL', 'IMAGE', 'AUDIO', 'VIDEO', 'YOUTUBE'], textLimit: 4000, oneTextAndOneMedia: false },
    REFERENCE: { kinds: ['TEXT', 'MATERIAL', 'IMAGE', 'AUDIO', 'VIDEO', 'YOUTUBE'], textLimit: 4000, oneTextAndOneMedia: false },
    COMPACT: { kinds: ['TEXT', 'MATERIAL', 'IMAGE', 'AUDIO', 'VIDEO'], textLimit: 300, oneTextAndOneMedia: true },
    /** ORDER items: like COMPACT, but longer so a code line or a short step fits; newlines are kept. */
    SEQUENCE: { kinds: ['TEXT', 'MATERIAL', 'IMAGE', 'AUDIO', 'VIDEO'], textLimit: 1000, oneTextAndOneMedia: true }
};

export interface SlotSpec { readonly profile: SlotProfile; readonly min: number; readonly max: number; }

export const PROMPT_SLOTS: Readonly<Record<Mechanic, SlotSpec>> = {
    SELF_CHECK: { profile: 'PROMPT', min: 1, max: 8 },
    FREE_RESPONSE: { profile: 'PROMPT', min: 1, max: 8 },
    CHOICE: { profile: 'PROMPT', min: 1, max: 8 },
    CLOZE: { profile: 'PROMPT', min: 0, max: 8 },
    MATCH: { profile: 'PROMPT', min: 0, max: 8 },
    ORDER: { profile: 'PROMPT', min: 0, max: 8 },
    CATEGORIZE: { profile: 'PROMPT', min: 0, max: 8 }
};
export const REFERENCE_SLOTS = {
    SELF_CHECK: { profile: 'REFERENCE', min: 1, max: 8 },
    FREE_RESPONSE: { profile: 'REFERENCE', min: 0, max: 8 }
} as const satisfies Partial<Record<Mechanic, SlotSpec>>;
export const COMPACT_SLOT: SlotSpec = { profile: 'COMPACT', min: 1, max: 2 };
export const SEQUENCE_SLOT: SlotSpec = { profile: 'SEQUENCE', min: 1, max: 2 };

export const LIMITS = {
    mediaBlocksPerExercise: 32,
    mediaLabel: 1024,
    transcript: 16384,
    objectiveTitle: 160,
    freeResponseAccepted: { min: 1, max: 20, length: 512 },
    clozeAccepted: { min: 1, max: 10, length: 200 },
    clozeBlanks: { min: 1, max: 12 },
    clozeSegments: { min: 2, max: 64 },
    clozeTotalText: 4000,
    clozeFixedLength: { min: 5, max: 20 },
    clozeAnswerLength: { min: 1, max: 80 },
    choiceOptions: { min: 2, max: 12 },
    matchPairs: { min: 2, max: 6 },
    orderItems: { min: 2, max: 12 },
    categories: { min: 2, max: 6, label: 80 },
    categorizeItems: { min: 2, max: 12 },
    aiCriteria: { min: 1, max: 10, description: 500 },
    aiReferenceAnswer: 4000
} as const;

// ---------------------------------------------------------------------------------------------
// Authoring content, answer keys and evaluator policies, one shape per mechanic
// ---------------------------------------------------------------------------------------------

export interface SelfCheckContent { readonly prompt: readonly AuthoringBlock[]; readonly reference: readonly AuthoringBlock[]; }
export type ResponseInput = 'TEXT' | 'TEXT_OR_SPEECH';
export interface FreeResponseContent {
    readonly prompt: readonly AuthoringBlock[];
    readonly reference: readonly AuthoringBlock[];
    readonly responseInput: ResponseInput;
}
export type ClozeSize = { readonly mode: 'FIXED'; readonly length: number } | { readonly mode: 'ANSWER_LENGTH' };
export interface ClozeTextSegment { readonly kind: 'TEXT'; readonly text: string; }
export interface ClozeBlankSegment {
    readonly kind: 'BLANK';
    readonly blankId: string;
    readonly size: ClozeSize;
    readonly firstLetterHint: boolean;
}
export type ClozeSegment = ClozeTextSegment | ClozeBlankSegment;
export interface ClozeContent { readonly prompt: readonly AuthoringBlock[]; readonly passage: readonly ClozeSegment[]; }
export type SelectionMode = 'SINGLE' | 'MULTIPLE';
export interface ChoiceOption { readonly optionId: string; readonly blocks: readonly AuthoringBlock[]; }
export interface ChoiceContent {
    readonly prompt: readonly AuthoringBlock[];
    readonly selectionMode: SelectionMode;
    readonly options: readonly ChoiceOption[];
}
export interface MatchItem { readonly itemId: string; readonly blocks: readonly AuthoringBlock[]; }
export interface MatchContent {
    readonly prompt: readonly AuthoringBlock[];
    readonly left: readonly MatchItem[];
    readonly right: readonly MatchItem[];
}
export interface OrderItem { readonly itemId: string; readonly blocks: readonly AuthoringBlock[]; }
export interface OrderContent { readonly prompt: readonly AuthoringBlock[]; readonly items: readonly OrderItem[]; }
export interface Category { readonly categoryId: string; readonly label: string; }
export interface CategorizeItem { readonly itemId: string; readonly blocks: readonly AuthoringBlock[]; }
export interface CategorizeContent {
    readonly prompt: readonly AuthoringBlock[];
    readonly categories: readonly Category[];
    readonly items: readonly CategorizeItem[];
}

export type NormalizationRule = 'UNICODE_NFC' | 'TRIM' | 'CASE_FOLD';
export const NORMALIZATION_RULES: readonly NormalizationRule[] = ['UNICODE_NFC', 'TRIM', 'CASE_FOLD'];
export type MatchingMode = 'STRICT' | 'SOFT';

export interface SelfReportKey { readonly kind: 'SELF_REPORT'; }
export interface TextAnswer {
    readonly accepted: readonly string[];
    readonly normalization: readonly NormalizationRule[];
    readonly matchingMode: MatchingMode;
}
export interface TextKey extends TextAnswer { readonly kind: 'TEXT'; }
export interface ClozeBlankKey extends TextAnswer { readonly blankId: string; }
export interface ClozeKey { readonly kind: 'CLOZE'; readonly blanks: readonly ClozeBlankKey[]; }
export interface ChoiceKey { readonly kind: 'CHOICE'; readonly correctOptionIds: readonly string[]; }
export interface MatchKey {
    readonly kind: 'MATCH';
    readonly pairs: readonly { readonly leftId: string; readonly rightId: string }[];
}
export interface OrderKey { readonly kind: 'ORDER'; readonly sequence: readonly string[]; }
export interface CategorizeKey {
    readonly kind: 'CATEGORIZE';
    readonly assignments: readonly { readonly itemId: string; readonly categoryId: string }[];
}

export type AiLevel = 'COMPLETE' | 'PARTIAL' | 'INSUFFICIENT';
export const AI_LEVELS: readonly AiLevel[] = ['COMPLETE', 'PARTIAL', 'INSUFFICIENT'];
export interface AiRubric {
    readonly referenceAnswer: string;
    readonly criteria: readonly { readonly criterionId: string; readonly description: string; readonly critical: boolean }[];
    readonly levels: readonly { readonly level: AiLevel; readonly description: string }[];
}
export interface EvaluatorRef<I extends string> { readonly id: I; readonly version: '1'; }
export interface AiSemanticPolicy extends EvaluatorRef<'ai-semantic'> { readonly rubric: AiRubric; }

export interface ExerciseSubject { readonly memberKey: string; readonly itemRevisionId: string; }
interface SpecBase { readonly schemaVersion: 2; readonly enabled: boolean; readonly subject: ExerciseSubject; }

export interface SelfCheckSpec extends SpecBase {
    readonly type: 'SELF_CHECK';
    readonly content: SelfCheckContent;
    readonly answerKey: SelfReportKey;
    readonly evaluatorPolicy: EvaluatorRef<'self-check'>;
}
export interface FreeResponseSpec extends SpecBase {
    readonly type: 'FREE_RESPONSE';
    readonly content: FreeResponseContent;
    readonly answerKey: TextKey;
    readonly evaluatorPolicy: EvaluatorRef<'deterministic-text'> | AiSemanticPolicy;
}
export interface ClozeSpec extends SpecBase {
    readonly type: 'CLOZE';
    readonly content: ClozeContent;
    readonly answerKey: ClozeKey;
    readonly evaluatorPolicy: EvaluatorRef<'deterministic-cloze'>;
}
export interface ChoiceSpec extends SpecBase {
    readonly type: 'CHOICE';
    readonly content: ChoiceContent;
    readonly answerKey: ChoiceKey;
    readonly evaluatorPolicy: EvaluatorRef<'deterministic-choice'>;
}
export interface MatchSpec extends SpecBase {
    readonly type: 'MATCH';
    readonly content: MatchContent;
    readonly answerKey: MatchKey;
    readonly evaluatorPolicy: EvaluatorRef<'deterministic-match'>;
}
export interface OrderSpec extends SpecBase {
    readonly type: 'ORDER';
    readonly content: OrderContent;
    readonly answerKey: OrderKey;
    readonly evaluatorPolicy: EvaluatorRef<'deterministic-order'>;
}
export interface CategorizeSpec extends SpecBase {
    readonly type: 'CATEGORIZE';
    readonly content: CategorizeContent;
    readonly answerKey: CategorizeKey;
    readonly evaluatorPolicy: EvaluatorRef<'deterministic-categorize'>;
}
export type ExerciseSpec = SelfCheckSpec | FreeResponseSpec | ClozeSpec | ChoiceSpec | MatchSpec | OrderSpec | CategorizeSpec;

type DistributiveOmit<T, K extends PropertyKey> = T extends unknown ? Omit<T, K> : never;

/**
 * An exercise without `enabled` and `subject`: what an author preview or a demo evaluates. Nothing is stored
 * and no material is resolved, so there is no subject to pin.
 */
export type PreviewExercise = DistributiveOmit<ExerciseSpec, 'enabled' | 'subject'>;

/** The preview exercise of a publication-shaped spec. */
export function previewExerciseOf(spec: ExerciseSpec): PreviewExercise {
    const { enabled: _enabled, subject: _subject, ...exercise } = spec;
    return exercise;
}

export type ObjectiveCommand =
    | { readonly operation: 'create'; readonly title: string }
    | { readonly operation: 'reuse'; readonly objectiveId: string; readonly objectiveRevisionId: string }
    | { readonly operation: 'revise'; readonly objectiveId: string; readonly expectedObjectiveRevisionId: string;
        readonly title: string };

// ---------------------------------------------------------------------------------------------
// Learner content: what Study and the author preview render. Never carries keys, labels or
// transcripts before the learner explicitly asks.
// ---------------------------------------------------------------------------------------------

export interface LearnerText { readonly kind: 'TEXT'; readonly text: string; }
export interface LearnerImage { readonly kind: 'IMAGE'; readonly assetId: string; readonly alt: string; }
export interface LearnerAudio {
    readonly kind: 'AUDIO';
    readonly assetId: string;
    readonly transcriptAvailable: boolean;
    readonly transcript?: string;
}
export interface LearnerVideo {
    readonly kind: 'VIDEO';
    readonly assetId: string;
    readonly transcriptAvailable: boolean;
    readonly transcript?: string;
}
export interface LearnerYoutube { readonly kind: 'YOUTUBE'; readonly videoId: string; readonly title: string; }
export type LearnerBlock = LearnerText | LearnerImage | LearnerAudio | LearnerVideo | LearnerYoutube;

export interface LearnerClozeBlank {
    readonly kind: 'BLANK';
    readonly blankId: string;
    readonly size: { readonly mode: 'FIXED' | 'ANSWER_LENGTH'; readonly length: number };
    readonly firstLetterHint: boolean;
}
export type LearnerClozeSegment = LearnerText | LearnerClozeBlank;
export interface LearnerChoiceOption { readonly optionId: string; readonly blocks: readonly LearnerBlock[]; }
export interface LearnerMatchItem { readonly itemId: string; readonly blocks: readonly LearnerBlock[]; }
export interface LearnerOrderItem { readonly itemId: string; readonly blocks: readonly LearnerBlock[]; }
export interface LearnerCategory { readonly categoryId: string; readonly label: string; }
export interface LearnerCategorizeItem { readonly itemId: string; readonly blocks: readonly LearnerBlock[]; }

export interface SelfCheckLearnerContent { readonly prompt: readonly LearnerBlock[]; readonly reference: readonly LearnerBlock[]; }
export interface FreeResponseLearnerContent { readonly prompt: readonly LearnerBlock[]; readonly responseInput: ResponseInput; }
export interface ClozeLearnerContent { readonly prompt: readonly LearnerBlock[]; readonly passage: readonly LearnerClozeSegment[]; }
export interface ChoiceLearnerContent {
    readonly prompt: readonly LearnerBlock[];
    readonly selectionMode: SelectionMode;
    readonly options: readonly LearnerChoiceOption[];
}
export interface MatchLearnerContent {
    readonly prompt: readonly LearnerBlock[];
    readonly left: readonly LearnerMatchItem[];
    readonly right: readonly LearnerMatchItem[];
}
export interface OrderLearnerContent {
    readonly prompt: readonly LearnerBlock[];
    readonly items: readonly LearnerOrderItem[];
}
export interface CategorizeLearnerContent {
    readonly prompt: readonly LearnerBlock[];
    readonly categories: readonly LearnerCategory[];
    readonly items: readonly LearnerCategorizeItem[];
}

export type LearnerContent =
    | { readonly type: 'SELF_CHECK'; readonly content: SelfCheckLearnerContent }
    | { readonly type: 'FREE_RESPONSE'; readonly content: FreeResponseLearnerContent }
    | { readonly type: 'CLOZE'; readonly content: ClozeLearnerContent }
    | { readonly type: 'CHOICE'; readonly content: ChoiceLearnerContent }
    | { readonly type: 'MATCH'; readonly content: MatchLearnerContent }
    | { readonly type: 'ORDER'; readonly content: OrderLearnerContent }
    | { readonly type: 'CATEGORIZE'; readonly content: CategorizeLearnerContent };

/** Every learner block of a presentation, in reading order. Used to find transcripts and media. */
export function allLearnerBlocks(value: LearnerContent): readonly LearnerBlock[] {
    switch (value.type) {
        case 'SELF_CHECK': return [...value.content.prompt, ...value.content.reference];
        case 'FREE_RESPONSE': return [...value.content.prompt];
        case 'CLOZE': return [...value.content.prompt];
        case 'CHOICE': return [...value.content.prompt, ...value.content.options.flatMap(option => option.blocks)];
        case 'MATCH': return [...value.content.prompt, ...value.content.left.flatMap(item => item.blocks),
            ...value.content.right.flatMap(item => item.blocks)];
        case 'ORDER': return [...value.content.prompt, ...value.content.items.flatMap(item => item.blocks)];
        case 'CATEGORIZE': return [...value.content.prompt, ...value.content.items.flatMap(item => item.blocks)];
    }
}

// ---------------------------------------------------------------------------------------------
// Tiny helpers used by both parsing and authoring validation
// ---------------------------------------------------------------------------------------------

export function isBlank(value: string): boolean { return value.trim().length === 0; }

export function codePointLength(value: string): number { return Array.from(value.normalize('NFC')).length; }

export function mediaBlockCount(blocks: readonly AuthoringBlock[]): number {
    return blocks.filter(block => block.kind === 'IMAGE' || block.kind === 'AUDIO' || block.kind === 'VIDEO').length;
}

/** All block lists of one exercise (every slot), used for the whole-exercise media cap. */
export function authoringSlots(spec: ExerciseSpec): readonly (readonly AuthoringBlock[])[] {
    switch (spec.type) {
        case 'SELF_CHECK': return [spec.content.prompt, spec.content.reference];
        case 'FREE_RESPONSE': return [spec.content.prompt, spec.content.reference];
        case 'CLOZE': return [spec.content.prompt];
        case 'CHOICE': return [spec.content.prompt, ...spec.content.options.map(option => option.blocks)];
        case 'MATCH': return [spec.content.prompt, ...spec.content.left.map(item => item.blocks),
            ...spec.content.right.map(item => item.blocks)];
        case 'ORDER': return [spec.content.prompt, ...spec.content.items.map(item => item.blocks)];
        case 'CATEGORIZE': return [spec.content.prompt, ...spec.content.items.map(item => item.blocks)];
    }
}

/**
 * Identity of a group label: NFC, edge whitespace and format characters (NBSP, U+3000, U+200B, ...) removed, then
 * case folded (`toUpperCase().toLowerCase()`, so "Straße" and "STRASSE" or "Σ" and "ς" collide). The stored label stays
 * verbatim; an empty key means the label has no visible character.
 */
export function categoryLabelKey(label: string): string {
    return label.normalize('NFC').replace(/^[\p{Z}\s\p{Cf}]+|[\p{Z}\s\p{Cf}]+$/gu, '').toUpperCase().toLowerCase();
}

/**
 * Canonical form of what the learner sees of an item (author-only media titles ignored). `resolveMaterial` turns a
 * MATERIAL block into its text like publication does; without it (author preview) two fragments stay distinct.
 * Items with equal keys are interchangeable in an ORDER exercise.
 */
export function visibleBlocksKey(blocks: readonly AuthoringBlock[], resolveMaterial?: (block: MaterialBlock) => string | null): string {
    return JSON.stringify(blocks.map(block => {
        switch (block.kind) {
            case 'TEXT': return ['TEXT', block.text];
            case 'MATERIAL': {
                const text = resolveMaterial?.(block) ?? null;
                return text === null ? ['MATERIAL', block.memberKey, block.itemRevisionId, block.nodeId] : ['TEXT', text];
            }
            case 'IMAGE': return ['IMAGE', block.assetId, block.alt];
            case 'AUDIO':
            case 'VIDEO': return [block.kind, block.assetId, block.transcript !== undefined];
            case 'YOUTUBE': return ['YOUTUBE', block.videoId, block.title];
        }
    }));
}

/** How many ORDER items a learner can tell apart; identical copies count once. */
export function distinguishableItems(items: readonly { readonly blocks: readonly AuthoringBlock[] }[],
    resolveMaterial?: (block: MaterialBlock) => string | null): number {
    return new Set(items.map(item => visibleBlocksKey(item.blocks, resolveMaterial))).size;
}
