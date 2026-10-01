import { parseLearnerBlock } from '../../content/exercise/exercise-content.parse';
import { AttemptFeedback } from './study.models';
import { entity, exact, guard, isRecord, protocol, strings, text } from './study-wire';

const RESULTS = ['CORRECT', 'PARTIAL', 'UNSURE', 'INCORRECT'] as const;

/** Strictly parses the feedback of one evaluated attempt. Study outcomes and author previews share these shapes. */
export function parseAttemptFeedback(value: unknown): AttemptFeedback {
    if (!isRecord(value) || typeof value['result'] !== 'string') throw protocol('Invalid feedback.');
    const result = value['result'];
    if (result === 'NOT_ASSESSED' || result === 'UNAVAILABLE') {
        const object = exact(value, ['result', 'reasonCodes']);
        return { result, reasonCodes: strings(object['reasonCodes']) };
    }
    if (!(RESULTS as readonly string[]).includes(result)) throw protocol('Invalid feedback result.');
    const verdict = result as (typeof RESULTS)[number];
    const rules = (object: Record<string, unknown>) => strings(object['appliedRules']);
    if ('blanks' in value) {
        const object = exact(value, ['result', 'appliedRules', 'blanks']);
        if (!Array.isArray(object['blanks']) || object['blanks'].length < 1 || object['blanks'].length > 12) {
            throw protocol('Invalid blank feedback.');
        }
        const blanks = object['blanks'].map(entry => {
            const blank = exact(entry, ['blankId', 'correct', 'hinted', 'reference']);
            if (typeof blank['correct'] !== 'boolean' || typeof blank['hinted'] !== 'boolean') throw protocol('Invalid blank result.');
            return { blankId: entity(blank['blankId']), correct: blank['correct'], hinted: blank['hinted'],
                reference: text(blank['reference'], 4096, 0) };
        });
        if (new Set(blanks.map(blank => blank.blankId)).size !== blanks.length) throw protocol('Duplicate blank feedback.');
        return { result: verdict, appliedRules: rules(object), blanks };
    }
    if ('correctOptionIds' in value) {
        const object = exact(value, ['result', 'appliedRules', 'correctOptionIds']);
        if (!Array.isArray(object['correctOptionIds']) || object['correctOptionIds'].length < 1
            || object['correctOptionIds'].length > 12) throw protocol('Invalid choice feedback.');
        const correctOptionIds = object['correctOptionIds'].map(entity);
        if (new Set(correctOptionIds).size !== correctOptionIds.length) throw protocol('Duplicate correct option.');
        return { result: verdict, appliedRules: rules(object), correctOptionIds };
    }
    if ('pairs' in value) {
        const object = exact(value, ['result', 'appliedRules', 'pairs']);
        if (!Array.isArray(object['pairs']) || object['pairs'].length < 2 || object['pairs'].length > 6) {
            throw protocol('Invalid pair feedback.');
        }
        const pairs = object['pairs'].map(entry => {
            const pair = exact(entry, ['leftId', 'selectedRightId', 'correctRightId', 'correct']);
            if (typeof pair['correct'] !== 'boolean') throw protocol('Invalid pair result.');
            return { leftId: entity(pair['leftId']), selectedRightId: entity(pair['selectedRightId']),
                correctRightId: entity(pair['correctRightId']), correct: pair['correct'] };
        });
        if (new Set(pairs.map(pair => pair.leftId)).size !== pairs.length) throw protocol('Duplicate pair feedback.');
        return { result: verdict, appliedRules: rules(object), pairs };
    }
    if ('referenceContent' in value) {
        const object = exact(value, ['result', 'appliedRules', 'reference', 'referenceContent']);
        if (!Array.isArray(object['referenceContent']) || object['referenceContent'].length > 8) {
            throw protocol('Invalid reference content.');
        }
        return { result: verdict, appliedRules: rules(object), reference: text(object['reference'], 4096, 0),
            referenceContent: object['referenceContent'].map(block => guard(() => parseLearnerBlock(block, 'REFERENCE', null))) };
    }
    return { result: verdict, appliedRules: rules(exact(value, ['result', 'appliedRules'])) };
}
