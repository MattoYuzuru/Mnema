import { ExerciseContentError } from '../../content/exercise/exercise-content.parse';
import { StudyProtocolError, StudyResponse } from './study.models';

/** Strict wire primitives shared by every parser of the Study contract (sessions, outcomes, author preview). */

export function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}

export function protocol(message: string): StudyProtocolError { return new StudyProtocolError(message); }

/** Exactly these keys, nothing else. */
export function exact(value: unknown, keys: readonly string[]): Record<string, unknown> {
    if (!isRecord(value)) throw protocol('Expected Study object.');
    const actual = Object.keys(value);
    if (actual.length !== keys.length || actual.some(key => !keys.includes(key))) throw protocol('Unexpected Study shape.');
    return value;
}

export function entity(value: unknown): string {
    if (typeof value !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u.test(value)) {
        throw protocol('Invalid entity ID.');
    }
    return value.toLowerCase();
}

export function text(value: unknown, maximum: number, minimum = 1): string {
    if (typeof value !== 'string' || value.length < minimum || new TextEncoder().encode(value).length > maximum) {
        throw protocol('Invalid Study text.');
    }
    return value;
}

export function strings(value: unknown): readonly string[] {
    if (!Array.isArray(value) || value.length > 20 || value.some(item => typeof item !== 'string')) {
        throw protocol('Invalid feedback details.');
    }
    return value as string[];
}

/** Content parsers throw their own error type; Study callers only see Study protocol errors. */
export function guard<T>(parse: () => T): T {
    try {
        return parse();
    } catch (error) {
        if (error instanceof ExerciseContentError) throw protocol(error.message);
        throw error;
    }
}

/** One first-letter hint exactly as the server returns it. */
export function hintLetter(value: unknown): string {
    if (typeof value !== 'string' || value.length < 1 || value.length > 16) throw protocol('Invalid hint letter.');
    return value;
}

/** Structural check of one learner response before it leaves the browser (Study attempts and author previews). */
export function validateResponse(response: StudyResponse): void {
    if (response.kind === 'TEXT') {
        // `answerSource` is sent only with a `TEXT_OR_SPEECH` exercise: `SPEECH` marks a text that came from a transcript (contracts/study).
        exact(response, response.answerSource === undefined ? ['kind', 'text'] : ['kind', 'text', 'answerSource']);
        text(response.text, 4096, 0);
        if (response.answerSource !== undefined && !['TYPED', 'SPEECH'].includes(response.answerSource)) throw protocol('Invalid answer source.');
    }
    else if (response.kind === 'SELF_CHECK') {
        exact(response, ['kind', 'rating']);
        if (!['NOT_RECALLED', 'HINTED', 'PARTIAL', 'FULL'].includes(response.rating)) throw protocol('Invalid rating.');
    } else if (response.kind === 'CLOZE') {
        exact(response, ['kind', 'blanks']);
        if (!Array.isArray(response.blanks) || response.blanks.length < 1 || response.blanks.length > 12
            || new Set(response.blanks.map(blank => entity(blank.blankId))).size !== response.blanks.length) {
            throw protocol('Invalid cloze response.');
        }
        response.blanks.forEach(blank => { exact(blank, ['blankId', 'text']); text(blank.text, 4096, 0); });
    } else if (response.kind === 'CHOICE') {
        exact(response, ['kind', 'optionIds']);
        if (!Array.isArray(response.optionIds) || response.optionIds.length === 0 || response.optionIds.length > 12
            || new Set(response.optionIds.map(entity)).size !== response.optionIds.length) {
            throw protocol('Invalid choice selection.');
        }
    } else if (response.kind === 'MATCH') {
        exact(response, ['kind', 'pairs']);
        if (!Array.isArray(response.pairs) || response.pairs.length < 2 || response.pairs.length > 6
            || new Set(response.pairs.map(pair => entity(pair.leftId))).size !== response.pairs.length
            || new Set(response.pairs.map(pair => entity(pair.rightId))).size !== response.pairs.length) {
            throw protocol('Invalid match response.');
        }
        response.pairs.forEach(pair => exact(pair, ['leftId', 'rightId']));
    } else if (response.kind === 'ORDER') {
        exact(response, ['kind', 'sequence']);
        if (!Array.isArray(response.sequence) || response.sequence.length < 2 || response.sequence.length > 12
            || new Set(response.sequence.map(entity)).size !== response.sequence.length) {
            throw protocol('Invalid order response.');
        }
    } else if (response.kind === 'CATEGORIZE') {
        exact(response, ['kind', 'assignments']);
        if (!Array.isArray(response.assignments) || response.assignments.length < 2 || response.assignments.length > 12
            || new Set(response.assignments.map(assignment => entity(assignment.itemId))).size !== response.assignments.length) {
            throw protocol('Invalid categorize response.');
        }
        response.assignments.forEach(assignment => { exact(assignment, ['itemId', 'categoryId']); entity(assignment.categoryId); });
    } else if (response.kind === 'CANCEL') exact(response, ['kind']);
    else throw protocol('Invalid response kind.');
}
