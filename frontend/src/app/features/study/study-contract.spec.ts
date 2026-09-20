import adversarial from '../../../../../contracts/study/adversarial.json';
import attempts from '../../../../../contracts/study/attempts.json';
import authoring from '../../../../../contracts/study/authoring.json';
import flows from '../../../../../contracts/study/flows.json';
import progress from '../../../../../contracts/study/progress.json';
import replaySources from '../../../../../contracts/study/replay-sources.json';
import reducer from '../../../../../contracts/study/reducer-v1.json';
import restart from '../../../../../contracts/study/restart.json';
import schemaDocument from '../../../../../contracts/study/study.schema.json';
import session from '../../../../../contracts/study/session.json';

type JsonObject = Record<string, unknown>;

describe('Study shared contract', () => {
    it('validates every fixture and rejects unknown or missing envelope fields', () => {
        const root = schemaDocument as unknown as JsonObject;
        const definitions = asObject(root['$defs']);
        const fixtures: Array<[unknown, string]> = [
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
        fixtures.forEach(([fixture, definition]) => validate(fixture, asObject(definitions[definition]), root));

        const unknown = clone(authoring);
        unknown['clientAuthority'] = true;
        expect(() => validate(unknown, asObject(definitions['authoringDocument']), root))
            .toThrowError(/unknown property/);

        const missing = clone(attempts);
        delete asObject(missing['typedSubmit'])['attemptId'];
        expect(() => validate(missing, asObject(definitions['attemptsDocument']), root))
            .toThrowError(/attemptId/);

        const nestedUnknown = clone(attempts);
        asObject(asObject(nestedUnknown['typedSubmit'])['response'])['correctAnswer'] = 'spoofed';
        expect(() => validate(nestedUnknown, asObject(definitions['attemptsDocument']), root))
            .toThrowError(/must match exactly one schema/);

        const invalidMode = clone(session);
        delete asObject(invalidMode['startReplay'])['sourceSessionId'];
        expect(() => validate(invalidMode, asObject(definitions['sessionDocument']), root))
            .toThrowError(/must match exactly one schema/);

        const forgedEffect = clone(attempts);
        asObject(forgedEffect['practiceOutcome'])['mode'] = 'SCHEDULED';
        expect(() => validate(forgedEffect, asObject(definitions['attemptsDocument']), root))
            .toThrowError(/must match exactly one schema/);
    });

    it('keeps assessment authority in the server-issued presentation', () => {
        const assessed = session.active.presentations[0].bindings.filter(binding => binding.role === 'ASSESSED');
        expect(assessed.length).toBe(1);
        expect(authoring.createTyped.exercise.bindings.filter(binding => binding.role === 'ASSESSED').length).toBe(1);

        const submit = attempts.typedSubmit as unknown as JsonObject;
        expect(submit['mode']).toBeUndefined();
        expect(submit['deckRevisionId']).toBeUndefined();
        expect(submit['exerciseRevisionId']).toBeUndefined();
        expect(submit['bindings']).toBeUndefined();
        expect(submit['correctAnswer']).toBeUndefined();
    });

    it('defines every assessed reducer combination once and binds its canonical hash', async () => {
        const combinations = reducer.transitions.map(row => `${row.result}:${row.evidenceClass}`);
        expect(combinations.length).toBe(12);
        expect(new Set(combinations).size).toBe(12);
        expect(combinations.some(value => value.endsWith(':NONE'))).toBeFalse();
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

    it('pins progress, restart and adversarial zero-effect behavior', () => {
        expect(flows.flows.length).toBe(10);
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

        expect(attempts.practiceOutcome.canonicalEffects).toBeFalse();
        expect(attempts.practiceOutcome.evidence).toBeNull();
        expect(attempts.practiceOutcome.transition).toBeNull();
        expect(attempts.notAssessedOutcome.transition).toBeNull();

        const cases = new Map(adversarial.cases.map(testCase => [testCase.id, testCase]));
        expect(cases.size).toBe(20);
        expect(cases.get('A-03')?.persistedEffect.transitions).toBe(1);
        expect(cases.get('A-05')?.persistedEffect.transitions).toBe(0);
        expect(cases.get('A-11')?.persistedEffect.stateTransitions).toBe(0);
        expect(cases.get('A-13')?.persistedEffect.fullScan).toBeFalse();
        expect(cases.get('A-20')?.persistedEffect.evaluationRuns).toBe(0);
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
    if (typeof schema['pattern'] === 'string' && typeof value === 'string'
        && !new RegExp(schema['pattern']).test(value)) fail(path, 'does not match pattern');

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
