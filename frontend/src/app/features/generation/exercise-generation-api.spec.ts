import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';

import { AuthoringProtocolError } from '../authoring/authoring.models';
import { DEFAULT_BUILDER_VALUE, buildExercisesSpec } from './exercise-builder';
import { ack, artifactIds, choice, createCommand, exerciseArtifact, exerciseDetail, exerciseSession, materialIds, materialRevisions, nodeIds, QUOTE, selfCheck } from './exercise-test-data';
import { GenerationApiService } from './generation-api.service';
import { readProblem } from './generation-problem';
import { isApprovable, parseArtifactDetail, parseSessionDetail } from './generation.models';
import { clone, examples, ids, pathOf, privateHeaders, usageContract } from './generation-test-data';
import { exerciseFailureReason, problemMessage } from './generation-view';

describe('Exercise generation over the wire (AI-13)', () => {
    let api: GenerationApiService;
    let http: HttpTestingController;
    const pins = [{ memberKey: materialIds.first, itemRevisionId: materialRevisions.first }];
    const spec = buildExercisesSpec(pins, { ...DEFAULT_BUILDER_VALUE, quantityMode: 'EXACT', perTarget: 3 }, 'ru');
    const deckPin = { rowVersion: '8', revisionId: ids.deckRevision };
    const target = { artifactId: artifactIds[0]!, expectedArtifactVersion: '3', expectedRevisionId: ids.revision };

    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
        api = TestBed.inject(GenerationApiService);
        http = TestBed.inject(HttpTestingController);
    });
    afterEach(() => http.verify());

    it('estimates an exercises spec with the exact wire shape of generationSpec.EXERCISES', async () => {
        const result = firstValueFrom(api.estimate(ids.deckId, spec));
        const request = http.expectOne(pathOf('estimateGeneration'));
        expect(request.request.body).toEqual({ spec: { kind: 'EXERCISES', outputLanguage: 'ru', targets: pins,
            settings: { mechanics: 'AUTO', priority: 'UNCOVERED_FIRST', quantity: { mode: 'EXACT', perTarget: 3 }, planFirst: false, budgetPercent: null } } });
        request.flush(usageContract['estimateResponse'], { headers: privateHeaders });
        expect((await result).canStart).toBe(true);
    });

    it('creates an exercises session and reads the echoed targets of its spec', async () => {
        const session = exerciseSession([exerciseArtifact(artifactIds[0]!, 0, 'QUEUED')], { state: 'RUNNING' });
        const result = firstValueFrom(api.createSession(ids.deckId, spec, ids.command));
        const request = http.expectOne(pathOf('createSession'));
        expect(request.request.body).toMatchObject({ commandId: ids.command, spec: { kind: 'EXERCISES' } });
        request.flush(session, { status: 201, statusText: 'Created', headers: { ...privateHeaders, ETag: `"${session['rowVersion']}"` } });
        const created = await result;
        expect(created.session.kind).toBe('EXERCISES');
        expect(created.session.spec.targets).toEqual(pins);
        expect(created.session.artifacts[0]!.targetKind).toBe('EXERCISE');
    });

    it('reads a materials spec echo as having no targets, and a malformed targets echo as none', () => {
        const materials = parseSessionDetail(exerciseSession([], { spec: { kind: 'MATERIALS', prompt: 'x' } }));
        expect(materials.spec.targets).toEqual([]);
        expect(parseSessionDetail(exerciseSession([], { spec: { kind: 'EXERCISES', targets: [{ memberKey: 'nope' }] } })).spec.targets).toEqual([]);
        expect(parseSessionDetail(exerciseSession([], { spec: { kind: 'EXERCISES', targets: 'x' } })).spec.targets).toEqual([]);
        expect(parseSessionDetail(exerciseSession([], { spec: { kind: 'EXERCISES', targets: Array.from({ length: 21 }, () => pins[0]) } })).spec.targets).toEqual([]);
    });

    describe('the artifact detail', () => {
        it('reads the display members: mechanic, objective title and the quotes by node id', () => {
            const detail = parseArtifactDetail(exerciseDetail(artifactIds[0]!, 0));
            expect(detail.display).toEqual({ mechanic: 'SELF_CHECK', objectiveTitle: 'Выбор между Seq Scan и Index Scan', quotes: { [nodeIds.first]: QUOTE } });
            expect(detail.revision?.payload.kind).toBe('EXERCISE_COMMAND');
        });

        it('has the display exactly where the contract puts it: an exercise with a revision, never an item or an empty artifact', () => {
            const exercise = exerciseDetail(artifactIds[0]!, 0);
            const withoutDisplay = { ...exercise };
            delete withoutDisplay['display'];
            expect(() => parseArtifactDetail(withoutDisplay)).toThrow(AuthoringProtocolError);
            expect(() => parseArtifactDetail({ ...clone(examples['artifactDetailItem']), display: exercise['display'] })).toThrow(AuthoringProtocolError);
            expect(parseArtifactDetail(clone(examples['artifactDetailItem'])).display).toBeNull();
            const queued = { ...withoutDisplay, state: 'QUEUED', currentRevisionId: null, revision: null };
            expect(parseArtifactDetail(queued).display).toBeNull();
            expect(() => parseArtifactDetail({ ...queued, display: exercise['display'] })).toThrow(AuthoringProtocolError);
        });

        it('refuses a display that is not well formed', () => {
            const bad = (display: unknown) => () => parseArtifactDetail(exerciseDetail(artifactIds[0]!, 0, createCommand(selfCheck()), {}, display as never));
            expect(bad({ mechanic: 'NOPE', objectiveTitle: 'x', quotes: {} })).toThrow(AuthoringProtocolError);
            expect(bad({ mechanic: 'CHOICE', objectiveTitle: 'x', quotes: [] })).toThrow(AuthoringProtocolError);
            expect(bad({ mechanic: 'CHOICE', objectiveTitle: 'x', quotes: { 'not-an-id': 'text' } })).toThrow(AuthoringProtocolError);
            expect(bad({ mechanic: 'CHOICE', objectiveTitle: 'x', quotes: { [nodeIds.first]: 4 } })).toThrow(AuthoringProtocolError);
            expect(bad({ mechanic: 'CHOICE', objectiveTitle: 'x' })).toThrow(AuthoringProtocolError);
            const tooMany = Object.fromEntries(Array.from({ length: 101 }, (_, index) => [`00000000-0000-4000-8000-${String(index).padStart(12, '0')}`, 'a']));
            expect(bad({ mechanic: 'CHOICE', objectiveTitle: 'x', quotes: tooMany })).toThrow(AuthoringProtocolError);
        });

        it('treats a proposed exercise as approvable the moment it is proposed (it has no media)', () => {
            const session = parseSessionDetail(exerciseSession([exerciseArtifact(artifactIds[0]!, 0, 'PROPOSED'), exerciseArtifact(artifactIds[1]!, 1, 'GENERATING')]));
            expect(session.artifacts.filter(isApprovable).map(artifact => artifact.artifactId)).toEqual([artifactIds[0]]);
        });
    });

    describe('approval with a replacement («Изменить»)', () => {
        const replacement = { objective: { operation: 'create', title: 'Новая цель' } as const, exercise: choice() };

        it('sends the edited exercise next to the approval and reads the exercise ack', async () => {
            const result = firstValueFrom(api.approveArtifact(ids.deckId, ids.sessionId, target, deckPin, ids.command, replacement));
            const request = http.expectOne(pathOf('approveArtifact', { artifactId: artifactIds[0]! }));
            expect(request.request.headers.get('If-Match')).toBe('"8"');
            expect(request.request.body).toEqual({ commandId: ids.command, expectedArtifactVersion: '3', expectedRevisionId: ids.revision,
                expectedDeckRevisionId: ids.deckRevision, replacement });
            request.flush(ack(ids.command, [artifactIds[0]!]), { headers: { ...privateHeaders, ETag: '"9"' } });
            const done = await result;
            expect(done.artifacts[0]!.publishedRef).toMatchObject({ kind: 'EXERCISE' });
        });

        it('sends no replacement member for a plain approval', async () => {
            const result = firstValueFrom(api.approveArtifact(ids.deckId, ids.sessionId, target, deckPin, ids.command));
            const request = http.expectOne(pathOf('approveArtifact', { artifactId: artifactIds[0]! }));
            expect(Object.keys(request.request.body)).not.toContain('replacement');
            request.flush(ack(ids.command, [artifactIds[0]!]), { headers: { ...privateHeaders, ETag: '"9"' } });
            await result;
        });

        it('never sends a replacement that does not read as an exercise command', async () => {
            await expect(firstValueFrom(api.approveArtifact(ids.deckId, ids.sessionId, target, deckPin, ids.command,
                { objective: { operation: 'create', title: '  ' }, exercise: choice() }))).rejects.toBeInstanceOf(AuthoringProtocolError);
            await expect(firstValueFrom(api.approveArtifact(ids.deckId, ids.sessionId, target, deckPin, ids.command,
                { objective: replacement.objective, exercise: { ...choice(), schemaVersion: 1 } as never }))).rejects.toBeInstanceOf(AuthoringProtocolError);
        });
    });

    describe('problems', () => {
        it('reads the limits a RESOURCE_LIMIT_EXCEEDED carries and drops what is not a limit', () => {
            const problem = readProblem(new HttpErrorResponse({ status: 422, error: { code: 'RESOURCE_LIMIT_EXCEEDED', limit: 'EXERCISES_PER_SESSION',
                limits: { maxExerciseTargets: 20, maxExercisesPerTarget: 10, maxExercisesPerSession: 60, other: 5, maxBad: -1, maxText: 'x' } } }));
            expect(problem.limits).toEqual({ maxExerciseTargets: 20, maxExercisesPerTarget: 10, maxExercisesPerSession: 60 });
            expect(readProblem(new HttpErrorResponse({ status: 422, error: { code: 'X', limits: [] } })).limits).toBeNull();
            expect(readProblem(new HttpErrorResponse({ status: 422, error: { code: 'X', limits: { other: 1 } } })).limits).toBeNull();
            expect(readProblem(new HttpErrorResponse({ status: 0 })).limits).toBeNull();
            expect(readProblem(new AuthoringProtocolError('x')).limits).toBeNull();
        });

        it('says what a stale or missing material means for exercises, and keeps the materials wording for materials', () => {
            const stale = readProblem(new HttpErrorResponse({ status: 409, error: { code: 'GENERATION_STATE_CONFLICT', reason: 'SOURCE_STALE' } }));
            expect(problemMessage(stale, 'EXERCISES')).toContain('пересоздайте');
            expect(problemMessage(stale)).toContain('Заметка изменилась');
            const gone = readProblem(new HttpErrorResponse({ status: 409, error: { code: 'SOURCE_UNAVAILABLE' } }));
            expect(problemMessage(gone, 'EXERCISES')).toContain('Выберите материалы заново');
            expect(problemMessage(gone)).toContain('Заметка');
        });

        it('says why an exercise failed in words', () => {
            expect(exerciseFailureReason('INVALID_OUTPUT')).toContain('не смогла собрать корректное упражнение');
            expect(exerciseFailureReason('REFUSAL')).toContain('отказалась');
            expect(exerciseFailureReason('SOURCE_UNAVAILABLE')).toContain('Материал');
            expect(exerciseFailureReason('ESTIMATE_EXCEEDED')).toContain('Упражнение');
            expect(exerciseFailureReason('PROVIDER_UNAVAILABLE')).toBe('Сервис ИИ временно недоступен.');
            expect(exerciseFailureReason(null)).toBe('Что-то пошло не так.');
        });
    });
});
