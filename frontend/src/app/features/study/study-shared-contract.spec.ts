import adversarial from '../../../../../contracts/study/adversarial.json';
import attempts from '../../../../../contracts/study/attempts.json';
import authoring from '../../../../../contracts/study/authoring.json';
import flows from '../../../../../contracts/study/flows.json';
import mechanics from '../../../../../contracts/study/mechanics.json';
import progress from '../../../../../contracts/study/progress.json';
import replaySources from '../../../../../contracts/study/replay-sources.json';
import reducer from '../../../../../contracts/study/reducer-v1.json';
import restart from '../../../../../contracts/study/restart.json';
import schemaDocument from '../../../../../contracts/study/study.schema.json';
import session from '../../../../../contracts/study/session.json';

type JsonObject = Record<string, unknown>;

/** Schema-level checks shared with the backend StudyContractFixtureTest; wire parsing lives in study-contract.spec. */
describe('Study shared contract fixtures', () => {
    const root = schemaDocument as unknown as JsonObject;
    const definitions = asObject(root['$defs']);
    const check = (value: unknown, definition: string): void =>
        validate(value, asObject(definitions[definition]), root);

    it('validates every fixture and rejects unknown, missing or forged fields', () => {
        const fixtures: Array<[unknown, string]> = [
            [mechanics, 'mechanicsDocument'],
            [authoring, 'authoringDocument'],
            [session, 'sessionDocument'],
            [attempts, 'attemptsDocument'],
            [reducer, 'reducerDocument'],
            [adversarial, 'adversarialDocument'],
            [flows, 'flowsDocument'],
            [progress, 'progressDocument'],
            [replaySources, 'replaySourcesDocument'],
            [restart, 'restartDocument']
        ];
        fixtures.forEach(([fixture, definition]) => check(fixture, definition));

        const unknown = clone(authoring);
        unknown['clientAuthority'] = true;
        expect(() => check(unknown, 'authoringDocument')).toThrowError(/unknown property/);

        const missing = clone(attempts);
        delete asObject(missing['freeResponseSubmit'])['attemptId'];
        expect(() => check(missing, 'attemptsDocument')).toThrowError(/attemptId/);

        const claimedHints = clone(attempts);
        asObject(claimedHints['freeResponseSubmit'])['hintsUsed'] = ['FIRST_LETTER'];
        expect(() => check(claimedHints, 'attemptsDocument')).toThrowError(/unknown property hintsUsed/);

        const nestedUnknown = clone(attempts);
        asObject(asObject(nestedUnknown['freeResponseSubmit'])['response'])['correctAnswer'] = 'spoofed';
        expect(() => check(nestedUnknown, 'attemptsDocument')).toThrowError(/must match exactly one schema/);

        const invalidMode = clone(session);
        delete asObject(invalidMode['startReplay'])['sourceSessionId'];
        expect(() => check(invalidMode, 'sessionDocument')).toThrowError(/must match exactly one schema/);

        const forgedEffect = clone(attempts);
        asObject(forgedEffect['practiceOutcome'])['mode'] = 'SCHEDULED';
        expect(() => check(forgedEffect, 'attemptsDocument')).toThrowError(/must match exactly one schema/);

        const leakedKey = clone(mechanics);
        asObject(asObject(leakedKey['presentations'])['choice'])['answerKey'] = { kind: 'CHOICE', correctOptionIds: [] };
        expect(() => check(leakedKey, 'mechanicsDocument')).toThrowError(/answerKey|exactly one schema/);

        const retiredType = clone(mechanics);
        asObject(asObject(retiredType['createChoiceVideoMultiple'])['exercise'])['type'] = 'LISTEN' + '_CHOICE';
        expect(() => check(retiredType, 'mechanicsDocument')).toThrowError();
    });

    it('keeps assessment authority in the server-issued presentation', () => {
        const presentation = session.active.presentations[0] as unknown as JsonObject;
        for (const field of ['answerKey', 'bindings', 'reference', 'options', 'prompt']) {
            expect(presentation[field], field).toBeUndefined();
        }
        const submit = attempts.freeResponseSubmit as unknown as JsonObject;
        for (const field of ['mode', 'deckRevisionId', 'exerciseRevisionId', 'bindings', 'correctAnswer', 'hintsUsed']) {
            expect(submit[field], field).toBeUndefined();
        }
    });

    it('defines every assessed reducer combination once and binds its canonical hash', async () => {
        const combinations = reducer.transitions.map(row => `${row.result}:${row.evidenceClass}`);
        expect(combinations.length).toBe(12);
        expect(new Set(combinations).size).toBe(12);
        expect(combinations.some(value => value.endsWith(':NONE'))).toBe(false);
        expect(reducer.intervals.length).toBe(8);

        const projection = {
            configId: reducer.configId,
            intervals: reducer.intervals,
            reducerId: reducer.reducerId,
            reducerVersion: reducer.reducerVersion,
            transitions: reducer.transitions
        };
        const bytes = new TextEncoder().encode(JSON.stringify(canonical(projection)));
        const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
        const hash = `sha256:${[...digest].map(value => value.toString(16).padStart(2, '0')).join('')}`;
        expect(session.active.reducer.configHash).toBe(hash);
        expect(attempts.scheduledOutcome.transition.configHash).toBe(hash);
    });

    it('pins flows, progress, restart and adversarial zero-effect behavior', () => {
        expect(flows.flows.length).toBe(12);
        flows.flows.forEach(flow => {
            expect(resolveFixturePointer(flow.requestFixture)).toBeDefined();
            expect(resolveFixturePointer(flow.responseFixture)).toBeDefined();
        });
        expect(session.active.mode).toBe(session.startScheduled.mode);
        expect(session.activeReplay.mode).toBe(session.startReplay.mode);
        expect(session.activePractice.mode).toBe(session.startPractice.mode);
        expect(progress.response.items.map(item => item.state)).toEqual(['ON_TRACK', 'NOT_STARTED']);
        expect((progress.response as unknown as JsonObject)['percentage']).toBeUndefined();
        expect(restart.precondition.objectiveStates[0].learningEpoch).toBe('0');
        expect(restart.persistedEffect.objectiveStates[0].learningEpoch).toBe('1');
        expect(restart.persistedEffect.historyRowsDeleted).toBe(0);

        expect(attempts.practiceOutcome.canonicalEffects).toBe(false);
        expect(attempts.practiceOutcome.evidence).toBeNull();
        expect(attempts.practiceOutcome.transition).toBeNull();
        expect(attempts.notAssessedOutcome.transition).toBeNull();

        const cases = new Map(adversarial.cases.map(testCase => [testCase.id, testCase]));
        expect(cases.size).toBe(24);
        const effect = (id: string): JsonObject => asObject(cases.get(id)?.persistedEffect);
        expect(effect('A-03')['transitions']).toBe(1);
        expect(effect('A-05')['transitions']).toBe(0);
        expect(effect('A-11')['stateTransitions']).toBe(0);
        expect(effect('A-13')['fullScan']).toBe(false);
        expect(effect('A-20')['evaluationRuns']).toBe(0);
    });
});

function validate(value: unknown, schema: JsonObject, root: JsonObject, path = '$'): void {
    if (Array.isArray(schema['oneOf'])) {
        const matches = schema['oneOf'].filter(candidate => {
            try {
                validate(value, asObject(candidate), root, path);
                return true;
            } catch {
                return false;
            }
        }).length;
        if (matches !== 1) fail(path, 'must match exactly one schema');
        return;
    }
    if (typeof schema['$ref'] === 'string') {
        const segments = schema['$ref'].slice(2).split('/');
        const target = segments.reduce<unknown>((current, segment) => asObject(current)[segment], root);
        validate(value, asObject(target), root, path);
        return;
    }
    if ('const' in schema && !equal(value, schema['const'])) fail(path, 'does not match const');
    if (Array.isArray(schema['enum']) && !schema['enum'].some(option => equal(value, option))) {
        fail(path, 'is not in enum');
    }
    if ('type' in schema && !matchesType(value, schema['type'])) fail(path, 'has wrong type');
    if (typeof value === 'string') {
        if (typeof schema['pattern'] === 'string' && !new RegExp(schema['pattern']).test(value)) {
            fail(path, 'does not match pattern');
        }
        // JSON Schema counts code points; the Mnema profiles additionally bound UTF-16 units server-side.
        const length = [...value].length;
        if (typeof schema['minLength'] === 'number' && length < schema['minLength']) fail(path, 'is too short');
        if (typeof schema['maxLength'] === 'number' && length > schema['maxLength']) fail(path, 'is too long');
    }
    if (typeof value === 'number') {
        if (typeof schema['minimum'] === 'number' && value < schema['minimum']) fail(path, 'is below minimum');
        if (typeof schema['maximum'] === 'number' && value > schema['maximum']) fail(path, 'is above maximum');
    }
    if (isObject(value)) {
        const required = Array.isArray(schema['required']) ? schema['required'] : [];
        required.forEach(name => {
            if (typeof name === 'string' && !(name in value)) fail(path, `missing required ${name}`);
        });
        const properties = isObject(schema['properties']) ? schema['properties'] : {};
        Object.entries(value).forEach(([name, child]) => {
            if (name in properties) validate(child, asObject(properties[name]), root, `${path}.${name}`);
            else if (schema['additionalProperties'] === false) fail(path, `unknown property ${name}`);
        });
    }
    if (Array.isArray(value)) {
        if (typeof schema['minItems'] === 'number' && value.length < schema['minItems']) fail(path, 'too few items');
        if (typeof schema['maxItems'] === 'number' && value.length > schema['maxItems']) fail(path, 'too many items');
        if (schema['uniqueItems'] === true && new Set(value.map(item => JSON.stringify(item))).size !== value.length) {
            fail(path, 'has duplicate items');
        }
        if (isObject(schema['items'])) value.forEach((child, index) =>
            validate(child, schema['items'] as JsonObject, root, `${path}[${index}]`));
    }
}

function matchesType(value: unknown, type: unknown): boolean {
    if (Array.isArray(type)) return type.some(candidate => matchesType(value, candidate));
    switch (type) {
        case 'object': return isObject(value);
        case 'array': return Array.isArray(value);
        case 'string': return typeof value === 'string';
        case 'integer': return typeof value === 'number' && Number.isInteger(value);
        case 'number': return typeof value === 'number';
        case 'boolean': return typeof value === 'boolean';
        case 'null': return value === null;
        default: return false;
    }
}

function canonical(value: unknown): unknown {
    if (Array.isArray(value)) return value.map(canonical);
    if (isObject(value)) return Object.fromEntries(Object.keys(value).sort()
        .map(key => [key, canonical(value[key])]));
    return value;
}

function resolveFixturePointer(reference: string): unknown {
    const [file, pointer = ''] = reference.split('#', 2);
    const documents: Record<string, unknown> = {
        'adversarial.json': adversarial,
        'attempts.json': attempts,
        'authoring.json': authoring,
        'mechanics.json': mechanics,
        'progress.json': progress,
        'restart.json': restart,
        'session.json': session
    };
    let current = documents[file];
    if (current === undefined) throw new Error(`Unknown fixture ${file}`);
    for (const segment of pointer.split('/').filter(Boolean)) {
        if (Array.isArray(current)) current = current[Number(segment)];
        else current = asObject(current)[segment];
        if (current === undefined) throw new Error(`Missing pointer ${reference}`);
    }
    return current;
}

function clone(value: unknown): JsonObject {
    return asObject(JSON.parse(JSON.stringify(value)) as unknown);
}

function equal(left: unknown, right: unknown): boolean {
    return JSON.stringify(left) === JSON.stringify(right);
}

function isObject(value: unknown): value is JsonObject {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function asObject(value: unknown): JsonObject {
    if (!isObject(value)) throw new Error('Expected JSON object');
    return value;
}

function fail(path: string, reason: string): never {
    throw new Error(`${path} ${reason}`);
}
