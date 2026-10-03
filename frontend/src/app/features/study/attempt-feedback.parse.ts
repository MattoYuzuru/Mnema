import { parseLearnerBlock } from '../../content/exercise/exercise-content.parse';
import { AssessmentFeedback, AttemptFeedback } from './study.models';
import { entity, exact, guard, isRecord, protocol, strings, text } from './study-wire';

const RESULTS = ['CORRECT', 'PARTIAL', 'UNSURE', 'INCORRECT'] as const;

/** Strictly parses the feedback of one evaluated attempt. Study outcomes and author previews share these shapes. */
export function parseAttemptFeedback(value: unknown): AttemptFeedback {
    if (!isRecord(value) || typeof value['result'] !== 'string') throw protocol('Invalid feedback.');
    const result = value['result'];
    if (result === 'NOT_ASSESSED' || result === 'UNAVAILABLE') {
        if ('referenceContent' in value) {
            const object = exact(value, ['result', 'reasonCodes', 'reference', 'referenceContent']);
            return { result, reasonCodes: strings(object['reasonCodes']), ...parseReference(object) };
        }
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
    if ('correctSequence' in value) {
        const object = exact(value, ['result', 'appliedRules', 'correctSequence', 'positions']);
        if (!Array.isArray(object['correctSequence']) || object['correctSequence'].length < 2
            || object['correctSequence'].length > 12 || !Array.isArray(object['positions'])
            || object['positions'].length !== object['correctSequence'].length) throw protocol('Invalid order feedback.');
        const correctSequence = object['correctSequence'].map(entity);
        const positions = object['positions'].map((entry, index) => {
            const position = exact(entry, ['position', 'selectedItemId', 'correct']);
            if (position['position'] !== index || typeof position['correct'] !== 'boolean') throw protocol('Invalid position result.');
            return { position: index, selectedItemId: entity(position['selectedItemId']), correct: position['correct'] };
        });
        if (new Set(correctSequence).size !== correctSequence.length
            || new Set(positions.map(position => position.selectedItemId)).size !== positions.length
            || positions.some(position => !correctSequence.includes(position.selectedItemId))) throw protocol('Invalid order feedback ids.');
        // The result is binary: the sequence is either right at every position or the attempt is incorrect.
        if (verdict !== (positions.every(position => position.correct) ? 'CORRECT' : 'INCORRECT')) throw protocol('Order result mismatch.');
        return { result: verdict, appliedRules: rules(object), correctSequence, positions };
    }
    if ('assignments' in value) {
        const object = exact(value, ['result', 'appliedRules', 'assignments']);
        if (!Array.isArray(object['assignments']) || object['assignments'].length < 2 || object['assignments'].length > 12) {
            throw protocol('Invalid categorize feedback.');
        }
        const assignments = object['assignments'].map(entry => {
            const assignment = exact(entry, ['itemId', 'selectedCategoryId', 'correctCategoryId', 'correct']);
            if (typeof assignment['correct'] !== 'boolean') throw protocol('Invalid assignment result.');
            return { itemId: entity(assignment['itemId']), selectedCategoryId: entity(assignment['selectedCategoryId']),
                correctCategoryId: entity(assignment['correctCategoryId']), correct: assignment['correct'] };
        });
        if (new Set(assignments.map(assignment => assignment.itemId)).size !== assignments.length) throw protocol('Duplicate assignment feedback.');
        const right = assignments.filter(assignment => assignment.correct).length;
        if (verdict !== (right === assignments.length ? 'CORRECT' : right === 0 ? 'INCORRECT' : 'PARTIAL')) {
            throw protocol('Categorize result mismatch.');
        }
        return { result: verdict, appliedRules: rules(object), assignments };
    }
    if ('referenceContent' in value) {
        if ('assessment' in value) {
            const object = exact(value, ['result', 'appliedRules', 'reference', 'referenceContent', 'assessment']);
            const assessment = parseAssessment(object['assessment']);
            if (verdict !== { COMPLETE: 'CORRECT', PARTIAL: 'PARTIAL', INSUFFICIENT: 'INCORRECT' }[assessment.judgement]) {
                throw protocol('Assessment judgement mismatch.');
            }
            return { result: verdict, appliedRules: rules(object), ...parseReference(object), assessment };
        }
        const object = exact(value, ['result', 'appliedRules', 'reference', 'referenceContent']);
        return { result: verdict, appliedRules: rules(object), ...parseReference(object) };
    }
    return { result: verdict, appliedRules: rules(exact(value, ['result', 'appliedRules'])) };
}

function parseReference(object: Record<string, unknown>) {
    if (!Array.isArray(object['referenceContent']) || object['referenceContent'].length > 8) {
        throw protocol('Invalid reference content.');
    }
    return { reference: text(object['reference'], 16384, 0),
        referenceContent: object['referenceContent'].map(block => guard(() => parseLearnerBlock(block, 'REFERENCE', null))) };
}

/** Key points found in, and missing from, an AI-graded answer. The server aggregated them; the browser only shows them. */
function parseAssessment(value: unknown): AssessmentFeedback {
    const object = exact(value, ['strictness', 'judgement', 'covered', 'missing', 'contradicted', 'nextStricter']);
    const strictness = object['strictness'];
    const judgement = object['judgement'];
    if (strictness !== 'S1' && strictness !== 'S2' && strictness !== 'S3') throw protocol('Invalid strictness.');
    if (judgement !== 'COMPLETE' && judgement !== 'PARTIAL' && judgement !== 'INSUFFICIENT') throw protocol('Invalid judgement.');
    if (typeof object['nextStricter'] !== 'boolean') throw protocol('Invalid assessment note.');
    const points = (list: unknown): readonly unknown[] => {
        if (!Array.isArray(list) || list.length > 10) throw protocol('Invalid assessment points.');
        return list;
    };
    const flag = (entry: Record<string, unknown>): boolean => {
        if (typeof entry['partial'] !== 'boolean') throw protocol('Invalid assessment point.');
        return entry['partial'];
    };
    const covered = points(object['covered']).map(item => {
        const entry = exact(item, ['criterionId', 'description', 'quote', 'partial']);
        return { criterionId: entity(entry['criterionId']), description: text(entry['description'], 2048, 0),
            quote: text(entry['quote'], 2048, 1), partial: flag(entry) };
    });
    const missing = points(object['missing']).map(item => {
        const entry = exact(item, ['criterionId', 'description', 'partial']);
        return { criterionId: entity(entry['criterionId']), description: text(entry['description'], 2048, 0), partial: flag(entry) };
    });
    const contradicted = points(object['contradicted']).map(item => {
        const entry = exact(item, ['criterionId', 'description', 'note']);
        return { criterionId: entity(entry['criterionId']), description: text(entry['description'], 2048, 0),
            note: text(entry['note'], 2048, 0) };
    });
    return { strictness, judgement, covered, missing, contradicted, nextStricter: object['nextStricter'] };
}
