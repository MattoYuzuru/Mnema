import { MECHANIC_CATALOG } from '../../../content/exercise/mechanic-catalog';
import { UPDATE_TEXT } from '../../../shared/copy-text';
import { ExerciseSide, MetadataSide, ObjectiveSide, OrderSide, UnitSide } from './update-plan.models';

/** A labelled run of plain text lines: a read-only description of one side of a unit that is not a document. */
export interface SideLine {
    readonly label: string;
    readonly values: readonly string[];
}

const T = UPDATE_TEXT.exercise;

/**
 * What a side of any unit that is not a material says in words, for the comparison columns. An exercise is a minimal text projection
 * (the question, the options or answers, which are right, the objective): the exercise editor is a form with write controls and
 * previews that need the whole authoring context, so the review reads the stored content directly and tolerantly. An unknown shape
 * yields fewer lines, never an error: a comparison must not fail because of one mechanic.
 */
export function sideLines(side: UnitSide): readonly SideLine[] {
    switch (side.kind) {
        case 'EXERCISE': return exerciseLines(side);
        case 'OBJECTIVE': return objectiveLines(side);
        case 'METADATA': return metadataLines(side);
        case 'ORDER': return orderLines(side);
        default: return [];
    }
}

function objectiveLines(side: ObjectiveSide): readonly SideLine[] {
    return [{ label: UPDATE_TEXT.objectiveTitle, values: [side.title || UPDATE_TEXT.exercise.empty] }];
}

function metadataLines(side: MetadataSide): readonly SideLine[] {
    return [
        { label: UPDATE_TEXT.metadataTitle, values: [side.title || T.empty] },
        { label: UPDATE_TEXT.metadataDescription, values: [side.description.trim() ? side.description : UPDATE_TEXT.noDescription] }
    ];
}

function orderLines(side: OrderSide): readonly SideLine[] {
    if (side.moved.length === 0) return [{ label: UPDATE_TEXT.moved, values: [side.total > 0 ? UPDATE_TEXT.movedCount(side.total) : UPDATE_TEXT.notMoved] }];
    // Above 5000 materials the server sends neither titles nor places: only how many moved.
    if (side.moved.some(move => move.title === null)) return [{ label: UPDATE_TEXT.moved, values: [UPDATE_TEXT.movedCount(side.total)] }];
    const values = side.moved.map(move => UPDATE_TEXT.movedPlace(move.title ?? '', move.from, move.to));
    if (side.truncated) values.push(UPDATE_TEXT.movedTruncated(side.moved.length, side.total));
    return [{ label: UPDATE_TEXT.moved, values }];
}

function exerciseLines(side: ExerciseSide): readonly SideLine[] {
    if (side.unavailable) return [];
    const content = object(side.content);
    const key = object(side.answerKey);
    const lines: SideLine[] = [{ label: T.type, values: [mechanicTitle(side.type)] }];
    if (!side.enabled) lines.push({ label: T.status, values: [T.disabled] });
    const prompt = blocks(content['prompt']);
    if (prompt.length > 0) lines.push({ label: T.prompt, values: prompt });
    const reference = blocks(content['reference']);
    if (reference.length > 0) lines.push({ label: T.reference, values: reference });
    switch (side.type) {
        case 'SELF_CHECK': lines.push({ label: T.answers, values: [T.selfReport] }); break;
        case 'FREE_RESPONSE': pushIf(lines, T.accepted, strings(key['accepted'])); break;
        case 'CLOZE': lines.push(...clozeLines(content, key)); break;
        case 'CHOICE': lines.push(...choiceLines(content, key)); break;
        case 'MATCH': lines.push(...matchLines(content, key)); break;
        case 'ORDER': lines.push(...orderKeyLines(content, key)); break;
        case 'CATEGORIZE': lines.push(...categorizeLines(content, key)); break;
        default: break;
    }
    if (side.objectiveTitle) lines.push({ label: T.objective, values: [side.objectiveTitle] });
    return lines;
}

function clozeLines(content: Record<string, unknown>, key: Record<string, unknown>): SideLine[] {
    const segments = array(content['passage']).map(object);
    const text = segments.map(segment => segment['kind'] === 'BLANK' ? `[${T.blank}]` : str(segment['text'])).join('');
    const lines: SideLine[] = [];
    if (text.trim()) lines.push({ label: T.passage, values: [text] });
    const answers = array(key['blanks']).map(object).map((blank, index) => `${index + 1}. ${strings(blank['accepted']).join(' / ')}`);
    pushIf(lines, T.answers, answers);
    return lines;
}

function choiceLines(content: Record<string, unknown>, key: Record<string, unknown>): SideLine[] {
    const correct = new Set(strings(key['correctOptionIds']));
    const options = array(content['options']).map(object).map(option => {
        const text = blocks(option['blocks']).join(' ') || T.empty;
        return correct.has(str(option['optionId'])) ? `${text} (${T.correct})` : text;
    });
    const lines: SideLine[] = [];
    if (content['selectionMode'] === 'SINGLE' || content['selectionMode'] === 'MULTIPLE') {
        lines.push({ label: T.options, values: [content['selectionMode'] === 'SINGLE' ? T.single : T.multiple, ...options] });
    } else {
        pushIf(lines, T.options, options);
    }
    return lines;
}

function itemsById(items: unknown): Map<string, string> {
    return new Map(array(items).map(object).map(item => [str(item['itemId']), blocks(item['blocks']).join(' ') || T.empty] as const));
}

function matchLines(content: Record<string, unknown>, key: Record<string, unknown>): SideLine[] {
    const left = itemsById(content['left']);
    const right = itemsById(content['right']);
    const pairs = array(key['pairs']).map(object).map(pair => `${left.get(str(pair['leftId'])) ?? T.empty} → ${right.get(str(pair['rightId'])) ?? T.empty}`);
    const lines: SideLine[] = [];
    pushIf(lines, T.pairs, pairs);
    return lines;
}

function orderKeyLines(content: Record<string, unknown>, key: Record<string, unknown>): SideLine[] {
    const items = itemsById(content['items']);
    const sequence = strings(key['sequence']).map((id, index) => `${index + 1}. ${items.get(id) ?? T.empty}`);
    const lines: SideLine[] = [];
    pushIf(lines, T.sequence, sequence);
    return lines;
}

function categorizeLines(content: Record<string, unknown>, key: Record<string, unknown>): SideLine[] {
    const items = itemsById(content['items']);
    const assignments = array(key['assignments']).map(object);
    return array(content['categories']).map(object).map(category => ({
        label: `${T.groups}: ${str(category['label']) || T.empty}`,
        values: assignments.filter(entry => entry['categoryId'] === category['categoryId']).map(entry => items.get(str(entry['itemId'])) ?? T.empty)
    })).filter(line => line.values.length > 0);
}

function mechanicTitle(type: string): string {
    return MECHANIC_CATALOG.find(entry => entry.mechanic === type)?.title ?? type;
}

/** The text of authoring blocks, one line each; media are named, never fetched. */
function blocks(value: unknown): string[] {
    return array(value).map(object).map((block): string => {
        switch (block['kind']) {
            case 'TEXT': return str(block['text']);
            case 'MATERIAL': return T.material;
            case 'IMAGE': return `${T.image}${str(block['alt']) ? `: ${str(block['alt'])}` : ''}`;
            case 'AUDIO': return `${T.audio}${str(block['title']) ? `: ${str(block['title'])}` : ''}`;
            case 'VIDEO': return `${T.video}${str(block['title']) ? `: ${str(block['title'])}` : ''}`;
            case 'YOUTUBE': return `${T.youtube}${str(block['title']) ? `: ${str(block['title'])}` : ''}`;
            default: return '';
        }
    }).filter(line => line.length > 0);
}

function pushIf(lines: SideLine[], label: string, values: readonly string[]): void {
    if (values.length > 0) lines.push({ label, values });
}

function object(value: unknown): Record<string, unknown> {
    return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {};
}

function array(value: unknown): unknown[] {
    return Array.isArray(value) ? value : [];
}

function str(value: unknown): string {
    return typeof value === 'string' ? value : '';
}

function strings(value: unknown): string[] {
    return array(value).filter((item): item is string => typeof item === 'string');
}
