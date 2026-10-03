import { MECHANICS, Mechanic } from '../../content/exercise/exercise-content.models';
import { AuthoringProtocolError, requireEntity, requireObject } from '../authoring/authoring.models';
import {
    EXERCISE_PRIORITIES, ExerciseQuantity, ExercisesSpec, GenerationSpec, MAX_EXERCISES_PER_TARGET, MAX_INSTRUCTION_LENGTH, RequestValidationError,
    ReviseExerciseSpec, ReviseItemSpec, SPEECH_VOICES, SpeechVoice
} from './generation.models';

/**
 * «Попросить Мнему…» (AI-16, #294, `createIntent`): one sentence, with the material or the exercise the owner is looking at, becomes
 * a spec, the chips that edit it, and notes. The server builds the spec (the target comes from the context, never from the model, and
 * the numbers are clamped), so what is parsed here is shown and posted as it is; the chips are only the way to change it.
 */

/** What the owner is looking at: a material (its profile) or an exercise (its editor). The server pins it at its head. */
export type IntentContext =
    | { readonly kind: 'MATERIAL'; readonly memberKey: string }
    | { readonly kind: 'EXERCISE'; readonly exerciseId: string };

export const INTENT_OPERATIONS = ['EXERCISES', 'REVISE_ITEM', 'REVISE_EXERCISE', 'UNSUPPORTED'] as const;
export type IntentOperation = (typeof INTENT_OPERATIONS)[number];

export const MAX_INTENT_TEXT_LENGTH = 2000;

/** The editable parameters of a spec. `kind` is stable; a chip of a kind this client does not know is ignored. */
export type IntentChip =
    | { readonly kind: 'OPERATION'; readonly value: IntentOperation }
    | { readonly kind: 'MECHANICS'; readonly value: 'AUTO' | readonly Mechanic[]; readonly options: readonly Mechanic[] }
    | { readonly kind: 'PER_TARGET'; readonly value: number | null; readonly min: number; readonly max: number }
    | { readonly kind: 'INSTRUCTION'; readonly value: string; readonly maxLength: number }
    | { readonly kind: 'VOICE'; readonly value: SpeechVoice; readonly options: readonly SpeechVoice[] };

/** A sentence the server adds, in words (never the model's own): a clamp, a trim, or why the request is not supported. */
export interface IntentNote {
    readonly code: string;
    readonly text: string;
    readonly limit: number | null;
}

export interface IntentResult {
    readonly operation: IntentOperation;
    /** `null` for `UNSUPPORTED`: there is nothing to start. */
    readonly spec: GenerationSpec | null;
    readonly chips: readonly IntentChip[];
    readonly notes: readonly IntentNote[];
}

/** The exact body of `createIntent`: the context and 1 to 2000 code points of text, not blank. */
export function serializeIntentRequest(context: IntentContext, text: string): Record<string, unknown> {
    const trimmed = text.trim();
    let length = 0;
    for (const _ of trimmed) length += 1;
    if (trimmed.length === 0 || length > MAX_INTENT_TEXT_LENGTH) throw new RequestValidationError('The request is empty or too long.');
    return {
        context: context.kind === 'MATERIAL'
            ? { kind: 'MATERIAL', memberKey: requireEntity(context.memberKey) }
            : { kind: 'EXERCISE', exerciseId: requireEntity(context.exerciseId) },
        text: trimmed
    };
}

function oneOf<T extends string>(value: unknown, allowed: readonly T[], what: string): T {
    if (typeof value !== 'string' || !(allowed as readonly string[]).includes(value)) throw new AuthoringProtocolError(`Invalid ${what}.`);
    return value as T;
}

function integer(value: unknown, minimum: number, maximum: number): number {
    if (typeof value !== 'number' || !Number.isInteger(value) || value < minimum || value > maximum) throw new AuthoringProtocolError('Invalid number.');
    return value;
}

function object(value: unknown): Record<string, unknown> {
    if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new AuthoringProtocolError('Expected object.');
    return value as Record<string, unknown>;
}

/** An object whose members are exactly `required`, plus any of `optional` that it carries: an unknown member is a protocol error. */
function members(value: unknown, required: readonly string[], optional: readonly string[] = []): Record<string, unknown> {
    const read = object(value);
    const keys = Object.keys(read);
    if (required.some(key => !keys.includes(key)) || keys.some(key => !required.includes(key) && !optional.includes(key))) {
        throw new AuthoringProtocolError('Unexpected response shape.');
    }
    return read;
}

function pin(value: unknown, keys: readonly [string, string]): Record<string, string> {
    const read = requireObject(value, keys);
    return { [keys[0]]: requireEntity(read[keys[0]]), [keys[1]]: requireEntity(read[keys[1]]) };
}

function parseQuantity(value: unknown): ExerciseQuantity {
    const read = object(value);
    members(read, ['mode'], ['perTarget', 'percent']);
    switch (read['mode']) {
        case 'AUTO': return { mode: 'AUTO' };
        case 'EXACT': return { mode: 'EXACT', perTarget: integer(read['perTarget'], 1, MAX_EXERCISES_PER_TARGET) };
        case 'BUDGET_PERCENT': return { mode: 'BUDGET_PERCENT', percent: integer(read['percent'], 1, 100) };
        default: throw new AuthoringProtocolError('Invalid quantity.');
    }
}

function parseMechanics(value: unknown): 'AUTO' | readonly Mechanic[] {
    if (value === 'AUTO') return 'AUTO';
    if (!Array.isArray(value) || value.length === 0 || value.length > MECHANICS.length) throw new AuthoringProtocolError('Invalid mechanics.');
    const chosen = value.map(entry => oneOf(entry, MECHANICS, 'mechanic'));
    if (new Set(chosen).size !== chosen.length) throw new AuthoringProtocolError('Invalid mechanics.');
    return MECHANICS.filter(mechanic => chosen.includes(mechanic));
}

/** The spec the server built, read into the model the builder, the estimate and `createSession` share. Members the client does not use are not kept. */
function parseSpec(value: unknown): GenerationSpec {
    const read = object(value);
    const language = typeof read['outputLanguage'] === 'string' ? { outputLanguage: read['outputLanguage'] } : {};
    switch (read['kind']) {
        case 'EXERCISES': {
            members(read, ['kind', 'targets', 'settings'], ['outputLanguage']);
            const settings = members(read['settings'], ['mechanics', 'priority', 'quantity'], ['planFirst', 'budgetPercent']);
            const targets = read['targets'];
            if (!Array.isArray(targets) || targets.length === 0 || targets.length > 20) throw new AuthoringProtocolError('Invalid targets.');
            const spec: ExercisesSpec = {
                kind: 'EXERCISES', ...language,
                targets: targets.map(target => pin(target, ['memberKey', 'itemRevisionId']) as { memberKey: string; itemRevisionId: string }),
                settings: { mechanics: parseMechanics(settings['mechanics']), priority: oneOf(settings['priority'], EXERCISE_PRIORITIES, 'priority'),
                    quantity: parseQuantity(settings['quantity']) }
            };
            return spec;
        }
        case 'REVISE_ITEM': {
            members(read, ['kind', 'target', 'instruction'], ['outputLanguage']);
            if (typeof read['instruction'] !== 'string') throw new AuthoringProtocolError('Invalid instruction.');
            const spec: ReviseItemSpec = { kind: 'REVISE_ITEM', ...language,
                target: pin(read['target'], ['memberKey', 'itemRevisionId']) as { memberKey: string; itemRevisionId: string },
                instruction: read['instruction'] };
            return spec;
        }
        case 'REVISE_EXERCISE': {
            members(read, ['kind', 'target'], ['instruction', 'media', 'outputLanguage']);
            const instruction = read['instruction'];
            if (instruction !== undefined && typeof instruction !== 'string') throw new AuthoringProtocolError('Invalid instruction.');
            const media = read['media'] === undefined ? null : requireObject(read['media'], ['action', 'voice']);
            if (media !== null && media['action'] !== 'AUDIO_REGENERATE') throw new AuthoringProtocolError('Invalid media action.');
            const spec: ReviseExerciseSpec = { kind: 'REVISE_EXERCISE', ...language,
                target: pin(read['target'], ['exerciseId', 'exerciseRevisionId']) as { exerciseId: string; exerciseRevisionId: string },
                ...(instruction === undefined ? {} : { instruction }),
                ...(media === null ? {} : { media: { action: 'AUDIO_REGENERATE' as const, voice: oneOf(media['voice'], SPEECH_VOICES, 'voice') } }) };
            return spec;
        }
        default: throw new AuthoringProtocolError('Invalid spec kind.');
    }
}

function parseChip(value: unknown): IntentChip | null {
    const read = object(value);
    switch (read['kind']) {
        case 'OPERATION': return { kind: 'OPERATION', value: oneOf(read['value'], INTENT_OPERATIONS, 'operation') };
        case 'MECHANICS': {
            if (!Array.isArray(read['options'])) throw new AuthoringProtocolError('Invalid options.');
            return { kind: 'MECHANICS', value: parseMechanics(read['value']), options: read['options'].map(entry => oneOf(entry, MECHANICS, 'mechanic')) };
        }
        case 'PER_TARGET':
            return { kind: 'PER_TARGET', value: read['value'] === null ? null : integer(read['value'], 1, MAX_EXERCISES_PER_TARGET),
                min: integer(read['min'], 1, MAX_EXERCISES_PER_TARGET), max: integer(read['max'], 1, MAX_EXERCISES_PER_TARGET) };
        case 'INSTRUCTION':
            if (typeof read['value'] !== 'string') throw new AuthoringProtocolError('Invalid instruction.');
            return { kind: 'INSTRUCTION', value: read['value'], maxLength: integer(read['maxLength'], 1, MAX_INSTRUCTION_LENGTH) };
        case 'VOICE':
            return { kind: 'VOICE', value: oneOf(read['value'], SPEECH_VOICES, 'voice'), options: SPEECH_VOICES };
        // The vocabulary is additive: a chip this client does not know is left out, never a reason to refuse the answer.
        default: return null;
    }
}

function parseNote(value: unknown): IntentNote {
    const read = members(value, ['code', 'text'], ['limit']);
    const text = read['text'];
    const code = read['code'];
    if (typeof code !== 'string' || code.length === 0 || code.length > 64 || typeof text !== 'string' || text.length === 0 || text.length > 400) {
        throw new AuthoringProtocolError('Invalid note.');
    }
    const limit = read['limit'];
    return { code, text, limit: typeof limit === 'number' && Number.isInteger(limit) ? limit : null };
}

/** What each context can ask for: a material its exercises or a rewrite, an exercise its own revision or the exercises of its material. */
const ALLOWED: Readonly<Record<IntentContext['kind'], readonly IntentOperation[]>> = {
    MATERIAL: ['EXERCISES', 'REVISE_ITEM', 'UNSUPPORTED'], EXERCISE: ['EXERCISES', 'REVISE_EXERCISE', 'UNSUPPORTED']
};

/**
 * `200` of `createIntent`: `{operation, spec, chips, notes}`; `spec` is `null` and `chips` empty for `UNSUPPORTED`. With the `context` the
 * request was made with, the answer must be about it: an operation the context does not allow, or a revision of another material or exercise,
 * is not a spec the owner asked for and is refused as an unreadable answer.
 */
export function parseIntent(value: unknown, context: IntentContext | null = null): IntentResult {
    const read = requireObject(value, ['operation', 'spec', 'chips', 'notes']);
    const operation = oneOf(read['operation'], INTENT_OPERATIONS, 'operation');
    if (!Array.isArray(read['chips']) || read['chips'].length > 16 || !Array.isArray(read['notes']) || read['notes'].length > 16) {
        throw new AuthoringProtocolError('Invalid intent.');
    }
    const spec = read['spec'] === null ? null : parseSpec(read['spec']);
    if ((operation === 'UNSUPPORTED') !== (spec === null) || (spec !== null && spec.kind !== operation)) throw new AuthoringProtocolError('Invalid intent.');
    if (context !== null) {
        if (!ALLOWED[context.kind].includes(operation)) throw new AuthoringProtocolError('Invalid intent.');
        const aboutOther = spec?.kind === 'REVISE_ITEM' ? context.kind !== 'MATERIAL' || spec.target.memberKey !== context.memberKey
            : spec?.kind === 'REVISE_EXERCISE' ? context.kind !== 'EXERCISE' || spec.target.exerciseId !== context.exerciseId
                : spec?.kind === 'EXERCISES' && context.kind === 'MATERIAL'
                    && (spec.targets.length !== 1 || spec.targets[0]!.memberKey !== context.memberKey);
        if (aboutOther) throw new AuthoringProtocolError('Invalid intent.');
    }
    return {
        operation, spec,
        chips: read['chips'].map(parseChip).filter((chip): chip is IntentChip => chip !== null),
        notes: read['notes'].map(parseNote)
    };
}
