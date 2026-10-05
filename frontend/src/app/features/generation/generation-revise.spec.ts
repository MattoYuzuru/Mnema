import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';

import { AuthoringProtocolError } from '../authoring/authoring.models';
import { GenerationApiService } from './generation-api.service';
import { IntentContext, parseIntent, serializeIntentRequest } from './generation-intent';
import { readProblem } from './generation-problem';
import { clone, examples, httpContract, ids, pathOf, privateHeaders, problemResponse } from './generation-test-data';
import {
    describeTurnAsk, editOutcomeNote, intentProblemMessage, reviseStartMessage, reviseSummary, voiceChipText, voiceRedoneText, waitText, workshopHeading
} from './generation-view';
import {
    ArtifactSummary, parseArtifactDetail, parseSessionDetail, parseTurn, serializeEdit, serializeSpec
} from './generation.models';

const material: IntentContext = { kind: 'MATERIAL', memberKey: '44444444-4444-4444-8444-444444444444' };
const exercise: IntentContext = { kind: 'EXERCISE', exerciseId: '66666666-6666-4666-8666-666666666666' };

describe('REVISE_* specs, exercise edits and the intent (AI-16, contracts/generation decision 16)', () => {
    describe('the specs the server builds are the specs the client sends', () => {
        it('serializes the contract examples of REVISE_ITEM and REVISE_EXERCISE to exactly their wire shape', () => {
            const item = parseIntent({ operation: 'REVISE_ITEM', spec: examples['specReviseItem'],
                chips: [{ kind: 'OPERATION', value: 'REVISE_ITEM' }, { kind: 'INSTRUCTION', value: 'Сделай объяснение проще', maxLength: 2000 }], notes: [] });
            expect(serializeSpec(item.spec!)).toEqual(examples['specReviseItem']);
            const exerciseIntent = parseIntent(examples['intentReviseExercise']);
            expect(serializeSpec(exerciseIntent.spec!)).toEqual(examples['specReviseExercise']);
        });

        it('trims the instruction, keeps the order of the members, and refuses what the server would refuse', () => {
            const spec = parseIntent(examples['intentReviseExercise']).spec!;
            if (spec.kind !== 'REVISE_EXERCISE') throw new Error('exercise spec expected');
            expect(serializeSpec({ ...spec, instruction: '  Короче  ' })).toEqual({ ...examples['specReviseExercise'], instruction: 'Короче' });
            // a revision of an exercise needs a request or a media action
            const { media: _media, ...onlyText } = spec;
            expect(() => serializeSpec(onlyText)).toThrow(AuthoringProtocolError);
            expect(() => serializeSpec({ ...spec, media: { action: 'AUDIO_REGENERATE', voice: 'robot' as never } })).toThrow(AuthoringProtocolError);
            const item = { kind: 'REVISE_ITEM' as const, target: { memberKey: material.memberKey, itemRevisionId: ids.revision }, instruction: '   ' };
            expect(() => serializeSpec(item)).toThrow(AuthoringProtocolError);
            expect(() => serializeSpec({ ...item, instruction: 'я'.repeat(2001) })).toThrow(AuthoringProtocolError);
            expect(serializeSpec({ ...item, instruction: 'я'.repeat(2000), outputLanguage: 'ru' })).toMatchObject({ outputLanguage: 'ru' });
        });
    });

    describe('the intent answer', () => {
        it('parses the contract examples: exercises with a number, the clamp note, the voice chip and the unsupported answer', () => {
            const exercises = parseIntent(examples['intentExercises']);
            expect(exercises.operation).toBe('EXERCISES');
            expect(exercises.spec).toMatchObject({ kind: 'EXERCISES', settings: { mechanics: 'AUTO', quantity: { mode: 'EXACT', perTarget: 3 } } });
            expect(exercises.chips.map(chip => chip.kind)).toEqual(['OPERATION', 'MECHANICS', 'PER_TARGET']);

            const clamped = parseIntent(examples['intentClamped']);
            expect(clamped.notes).toEqual([{ code: 'PER_TARGET_CLAMPED', text: expect.stringContaining('10'), limit: 10 }]);

            const voice = parseIntent(examples['intentReviseExercise']);
            expect(voice.chips.find(chip => chip.kind === 'VOICE')).toMatchObject({ value: 'male', options: ['female', 'male'] });

            const unsupported = parseIntent(examples['intentUnsupported']);
            expect(unsupported).toMatchObject({ operation: 'UNSUPPORTED', spec: null, chips: [] });
            expect(unsupported.notes[0]!.text).toContain('пока не умею');
        });

        it('reads the mechanics in the registry order, and ignores a chip of a kind it does not know', () => {
            const answer = clone(examples['intentExercises']);
            answer.spec.settings.mechanics = ['CHOICE', 'CLOZE'];
            answer.chips.push({ kind: 'FROM_THE_FUTURE', value: 1 });
            const parsed = parseIntent(answer);
            expect(parsed.spec).toMatchObject({ settings: { mechanics: ['CLOZE', 'CHOICE'] } });
            expect(parsed.chips).toHaveLength(3);
        });

        it('refuses an answer that does not hold together: an operation that is not the spec, an unknown field, a malformed spec or note', () => {
            const bad = (change: (answer: Record<string, any>) => void) => {
                const answer = clone(examples['intentExercises']);
                change(answer);
                expect(() => parseIntent(answer)).toThrow(AuthoringProtocolError);
            };
            bad(answer => { answer['extra'] = 1; });
            bad(answer => { answer['operation'] = 'REVISE_ITEM'; });
            bad(answer => { answer['operation'] = 'UNSUPPORTED'; });
            bad(answer => { answer['spec'] = null; });
            bad(answer => { answer['spec'].kind = 'MATERIALS'; });
            bad(answer => { answer['spec'].targets = []; });
            bad(answer => { answer['spec'].settings.mechanics = ['POEM']; });
            bad(answer => { answer['spec'].settings.mechanics = ['CLOZE', 'CLOZE']; });
            bad(answer => { answer['spec'].settings.quantity = { mode: 'LOTS' }; });
            bad(answer => { answer['spec'].settings.priority = 'RANDOM'; });
            bad(answer => { answer['spec'].targets[0].extra = 1; });
            bad(answer => { answer['chips'][2].value = 0; });
            bad(answer => { answer['notes'] = [{ code: 'X' }]; });
            bad(answer => { answer['chips'] = 'none'; });
            const unsupported = clone(examples['intentUnsupported']);
            unsupported.spec = clone(examples['specReviseItem']);
            expect(() => parseIntent(unsupported)).toThrow(AuthoringProtocolError);
        });

        it('reads an exercise quantity of every mode and a share of the limit', () => {
            const answer = clone(examples['intentExercises']);
            answer.spec.settings.quantity = { mode: 'AUTO' };
            expect(parseIntent(answer).spec).toMatchObject({ settings: { quantity: { mode: 'AUTO' } } });
            answer.spec.settings.quantity = { mode: 'BUDGET_PERCENT', percent: 5 };
            expect(parseIntent(answer).spec).toMatchObject({ settings: { quantity: { mode: 'BUDGET_PERCENT', percent: 5 } } });
        });

        it('refuses an answer that is about something else than the request: another target, a number above ten, an operation the context does not allow, an unknown member', () => {
            const answer = () => clone(examples['intentExercises']);
            const forMaterial: IntentContext = { kind: 'MATERIAL', memberKey: examples['intentExercises'].spec.targets[0].memberKey };
            expect(parseIntent(answer(), forMaterial).operation).toBe('EXERCISES');
            expect(() => parseIntent(answer(), { kind: 'MATERIAL', memberKey: '44444444-4444-4444-8444-444444444445' })).toThrow(AuthoringProtocolError);
            expect(() => parseIntent(examples['intentReviseExercise'], forMaterial)).toThrow(AuthoringProtocolError);
            expect(() => parseIntent(examples['intentReviseExercise'], { kind: 'EXERCISE', exerciseId: '66666666-6666-4666-8666-666666666667' })).toThrow(AuthoringProtocolError);
            expect(parseIntent(examples['intentReviseExercise'], { kind: 'EXERCISE', exerciseId: examples['specReviseExercise'].target.exerciseId }).operation).toBe('REVISE_EXERCISE');
            const item = { operation: 'REVISE_ITEM', spec: examples['specReviseItem'], chips: [], notes: [] };
            expect(parseIntent(item, { kind: 'MATERIAL', memberKey: examples['specReviseItem'].target.memberKey }).operation).toBe('REVISE_ITEM');
            expect(() => parseIntent(item, { kind: 'MATERIAL', memberKey: '44444444-4444-4444-8444-444444444445' })).toThrow(AuthoringProtocolError);
            expect(() => parseIntent(item, exercise)).toThrow(AuthoringProtocolError);
            const high = answer(); high.spec.settings.quantity = { mode: 'EXACT', perTarget: 11 };
            expect(() => parseIntent(high)).toThrow(AuthoringProtocolError);
            const chip = answer(); chip.chips[2].value = 11;
            expect(() => parseIntent(chip)).toThrow(AuthoringProtocolError);
            for (const change of [(a: any) => { a.spec.extra = 1; }, (a: any) => { a.spec.settings.extra = 1; }, (a: any) => { a.notes = [{ code: 'X', text: 'y', extra: 1 }]; },
                (a: any) => { a.spec.settings.quantity.extra = 1; }]) {
                const changed = answer(); change(changed);
                expect(() => parseIntent(changed)).toThrow(AuthoringProtocolError);
            }
            const withOptional = answer(); withOptional.spec.settings.planFirst = false; withOptional.spec.settings.budgetPercent = null; withOptional.spec.outputLanguage = 'ru';
            expect(parseIntent(withOptional).operation).toBe('EXERCISES');
            const extraItem = clone(item); (extraItem.spec as any).extra = 1;
            expect(() => parseIntent(extraItem)).toThrow(AuthoringProtocolError);
            const extraExercise = clone(examples['intentReviseExercise']); extraExercise.spec.extra = 1;
            expect(() => parseIntent(extraExercise)).toThrow(AuthoringProtocolError);
        });

        it('reports an answer that does not read as an unreadable answer, not as a network failure', () => {
            expect(intentProblemMessage(readProblem(new AuthoringProtocolError('x')))).toContain('не смогли это разобрать');
        });

        it('builds the request body: the context and the trimmed text, nothing else', () => {
            expect(serializeIntentRequest(material, '  Сделай все типы упражнений по 3 ')).toEqual({
                context: { kind: 'MATERIAL', memberKey: material.memberKey }, text: 'Сделай все типы упражнений по 3' });
            expect(serializeIntentRequest(exercise, 'Замени аудио на мужской голос')).toEqual({
                context: { kind: 'EXERCISE', exerciseId: exercise.exerciseId }, text: 'Замени аудио на мужской голос' });
            expect(() => serializeIntentRequest(material, '   ')).toThrow();
            expect(() => serializeIntentRequest(material, 'я'.repeat(2001))).toThrow();
            expect(serializeIntentRequest(material, 'я'.repeat(2000))['text']).toHaveLength(2000);
        });
    });

    describe('createIntent over HTTP', () => {
        let api: GenerationApiService;
        let http: HttpTestingController;

        beforeEach(() => {
            TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
            api = TestBed.inject(GenerationApiService);
            http = TestBed.inject(HttpTestingController);
        });
        afterEach(() => http.verify());

        it('posts the context and the text to the deck path of http.json and parses the answer; no command, no receipt', async () => {
            const contract = httpContract['endpoints'].find((endpoint: any) => endpoint.operationId === 'createIntent');
            const result = firstValueFrom(api.createIntent(ids.deckId, material, 'Сделай все типы упражнений по 3'));
            const request = http.expectOne(candidate => candidate.url === pathOf('createIntent'));
            expect(request.request.method).toBe(contract.method);
            expect(request.request.body).toEqual(contract.requestBody.example);
            request.flush(examples['intentExercises'], { headers: privateHeaders });
            expect((await result).operation).toBe('EXERCISES');
        });

        it('refuses a cacheable or malformed answer', async () => {
            const cacheable = firstValueFrom(api.createIntent(ids.deckId, material, 'Сделай'));
            http.expectOne(candidate => candidate.url === pathOf('createIntent')).flush(examples['intentExercises']);
            await expect(cacheable).rejects.toBeInstanceOf(AuthoringProtocolError);
            const malformed = firstValueFrom(api.createIntent(ids.deckId, material, 'Сделай'));
            http.expectOne(candidate => candidate.url === pathOf('createIntent')).flush({ operation: 'EXERCISES' }, { headers: privateHeaders });
            await expect(malformed).rejects.toBeInstanceOf(AuthoringProtocolError);
        });
    });

    describe('what a revision adds to the sessions and artifacts it is read from', () => {
        it('reads the voice of a turn and of a slot, and tolerates their absence in the shapes that never had them', () => {
            expect(parseTurn({ ...examples['turnQueued'], voice: 'male' }).voice).toBe('male');
            expect(parseTurn(examples['turnQueued']).voice).toBeNull();
            const { voice: _voice, ...legacy } = examples['turnQueued'];
            expect(parseTurn(legacy).voice).toBeNull();
            expect(() => parseTurn({ ...examples['turnQueued'], voice: 'robot' })).toThrow(AuthoringProtocolError);
            const detail = clone(examples['artifactDetailItem']);
            detail.mediaSlots[0].voice = 'female';
            detail.mediaSlots[0].voice = 'male';
            expect(parseArtifactDetail(detail).mediaSlots[0]!.voice).toBe('male');
            // The audio of the contract's material has its voice and language (AI-09); an older answer has neither.
            expect(parseArtifactDetail(examples['artifactDetailItem']).mediaSlots[0]).toMatchObject({ voice: 'female', lang: 'ja' });
            delete detail.mediaSlots[0].voice;
            delete detail.mediaSlots[0].lang;
            expect(parseArtifactDetail(detail).mediaSlots[0]).toMatchObject({ voice: null, lang: null });
        });

        it('echoes what a revision was asked in the session: the instruction and the voice', () => {
            const session = clone(examples['sessionDetail']);
            session.kind = 'REVISE_EXERCISE';
            session.spec = clone(examples['specReviseExercise']);
            expect(parseSessionDetail(session).spec).toMatchObject({ kind: 'REVISE_EXERCISE', instruction: null, voice: 'male' });
            session.spec.instruction = 'Короче';
            expect(parseSessionDetail(session).spec.instruction).toBe('Короче');
            expect(parseSessionDetail(examples['sessionDetail']).spec).toMatchObject({ instruction: null, voice: null });
        });
    });

    describe('edits of the exercise of a revision', () => {
        const base = { expectedRevisionId: ids.revision, nodeIds: [] as string[], exercise: true };

        it('rewrites it whole (an instruction, no target) or redoes its audio (a voice and nothing else)', () => {
            expect(serializeEdit({ ...base, action: 'FREE', instruction: ' Короче ' }, ids.command)).toEqual({
                commandId: ids.command, expectedRevisionId: ids.revision, action: 'FREE', instruction: 'Короче' });
            expect(serializeEdit({ ...base, action: 'AUDIO_REGENERATE', voice: 'male' }, ids.command)).toEqual({
                commandId: ids.command, expectedRevisionId: ids.revision, action: 'AUDIO_REGENERATE', voice: 'male' });
        });

        it('refuses a request that breaks the rules before it is sent', () => {
            expect(() => serializeEdit({ ...base, action: 'FREE' }, ids.command)).toThrow(AuthoringProtocolError);
            expect(() => serializeEdit({ ...base, action: 'FREE', instruction: 'x', voice: 'male' }, ids.command)).toThrow(AuthoringProtocolError);
            expect(() => serializeEdit({ ...base, action: 'AUDIO_REGENERATE' }, ids.command)).toThrow(AuthoringProtocolError);
            expect(() => serializeEdit({ ...base, action: 'REWRITE', instruction: 'x' }, ids.command)).toThrow(AuthoringProtocolError);
            expect(() => serializeEdit({ ...base, action: 'FREE', instruction: 'x', nodeIds: [ids.first] }, ids.command)).toThrow(AuthoringProtocolError);
            expect(() => serializeEdit({ ...base, action: 'FREE', instruction: 'x', preset: 'SIMPLER' }, ids.command)).toThrow(AuthoringProtocolError);
            // a material never takes a voice
            expect(() => serializeEdit({ expectedRevisionId: ids.revision, action: 'FREE', nodeIds: [ids.first], instruction: 'x', voice: 'male' }, ids.command))
                .toThrow(AuthoringProtocolError);
        });
    });

    describe('what the owner reads', () => {
        it('reads the wait of a 429 from the member or the header, whole seconds', () => {
            expect(readProblem(problemResponse(429, { code: 'RATE_LIMITED', retryAfter: 42 })).retryAfter).toBe(42);
            const withHeader = problemResponse(429, { code: 'RATE_LIMITED' });
            const headed = Object.assign(Object.create(Object.getPrototypeOf(withHeader)), withHeader, { headers: { get: (name: string) => name === 'Retry-After' ? '120' : null } });
            expect(readProblem(headed).retryAfter).toBe(120);
            expect(readProblem(problemResponse(429, { code: 'RATE_LIMITED' })).retryAfter).toBeNull();
        });

        it('says how long to wait in seconds or minutes, with the right plural', () => {
            const say = (seconds: number | null): string => waitText(seconds).replace(/\u00a0/g, ' ');
            expect(say(null)).toBe('немного');
            expect(say(1)).toBe('1 секунду');
            expect(say(5)).toBe('5 секунд');
            expect(say(60)).toBe('1 минуту');
            expect(say(121)).toBe('3 минуты');
            expect(say(600)).toBe('10 минут');
        });

        it('explains each way the free intent call can fail, and says it cost nothing when the outcome is unknown', () => {
            const say = (status: number, body: Record<string, unknown> = {}) => intentProblemMessage(readProblem(problemResponse(status, body)));
            expect(say(429, { code: 'RATE_LIMITED', retryAfter: 30 })).toContain('Подождите 30');
            expect(say(409, { code: 'CAPABILITY_UNAVAILABLE', capability: 'aiGeneration' })).toContain('Мнема сейчас недоступна');
            expect(say(409, { code: 'GENERATION_STATE_CONFLICT' })).toContain('Обновите страницу');
            expect(say(404)).toContain('больше недоступны');
            expect(say(400)).toContain('Перефразируйте');
            expect(say(418)).toContain('Попробуйте ещё раз');
            expect(say(503)).toContain('бесплатный');
        });

        it('explains why a revision could not start: the target, the size, the voice, the stale material', () => {
            const say = (kind: 'REVISE_ITEM' | 'REVISE_EXERCISE', status: number, body: Record<string, unknown>) =>
                reviseStartMessage(readProblem(problemResponse(status, body)), kind);
            for (const reason of ['TARGET_UNSUPPORTED_BLOCK', 'TARGET_PERSONAL_DATA', 'TARGET_MEDIA_ONLY', 'TARGET_NO_AUDIO']) {
                expect(say('REVISE_ITEM', 400, { code: 'INVALID_REQUEST', reason }), reason).toMatch(/[А-Яа-я]/u);
            }
            expect(say('REVISE_EXERCISE', 400, { code: 'INVALID_REQUEST', reason: 'TARGET_NO_AUDIO' }))
                .toBe('Озвучить заново можно только аудио с текстом: добавьте расшифровку к записи в упражнении.');
            expect(say('REVISE_ITEM', 422, { code: 'RESOURCE_LIMIT_EXCEEDED', limit: 'EDIT_TARGET_SIZE' })).toContain('слишком длинный');
            expect(say('REVISE_EXERCISE', 409, { code: 'CAPABILITY_UNAVAILABLE', capability: 'textToSpeech' })).toContain('Озвучивание пока недоступно');
            expect(say('REVISE_ITEM', 409, { code: 'SOURCE_UNAVAILABLE' })).toContain('Материал уже изменился');
            expect(say('REVISE_EXERCISE', 409, { code: 'SOURCE_UNAVAILABLE' })).toContain('Упражнение или его материал');
            expect(say('REVISE_ITEM', 409, { code: 'GENERATION_STATE_CONFLICT', reason: 'SOURCE_STALE' })).toContain('В колоде остался прежний текст');
            expect(say('REVISE_EXERCISE', 409, { code: 'GENERATION_STATE_CONFLICT', reason: 'SOURCE_STALE' })).toContain('осталась прежняя версия');
            expect(say('REVISE_ITEM', 409, { code: 'GENERATION_STATE_CONFLICT', reason: 'NOT_RETRYABLE' })).toContain('нельзя повторить');
        });

        it('names the heading, the voice, the history entry and the sentence of the live region of a revision', () => {
            expect(workshopHeading('REVISE_ITEM')).toBe('Правка материала');
            expect(workshopHeading('REVISE_EXERCISE')).toBe('Правка упражнения');
            expect(workshopHeading('EXERCISES')).toBe('Мастерская упражнений');
            expect(workshopHeading('MATERIALS')).toBe('Мастерская');
            expect(voiceChipText('male')).toBe('Голос: мужской');
            expect(voiceChipText('female')).toBe('Голос: женский');
            expect(voiceRedoneText('male')).toBe('Озвучено заново: мужской');
            expect(describeTurnAsk({ action: 'AUDIO_REGENERATE', preset: null, instruction: null, voice: 'male' })).toBe('Озвучка заново: мужской голос');
            expect(describeTurnAsk({ action: 'AUDIO_REGENERATE', preset: null, instruction: null })).toBe('Озвучка заново');
            expect(editOutcomeNote('APPLIED', 'AUDIO_REGENERATE', true)).toBe('Озвучено заново.');
            expect(editOutcomeNote('APPLIED', 'FREE', true)).toBe('Мнема переписала упражнение.');
            expect(editOutcomeNote('FAILED', 'AUDIO_REGENERATE', true)).toBe('Не удалось озвучить: запись не изменилась.');
            expect(editOutcomeNote('FAILED', 'FREE', true)).toContain('упражнение');
            expect(editOutcomeNote('CANCELLED', 'FREE', true)).toContain('упражнение');
            const artifact = (state: string) => ({ state }) as ArtifactSummary;
            const lines = ['QUEUED', 'REVISING', 'PROPOSED', 'PUBLISHED', 'REJECTED', 'STALE', 'FAILED', 'HANDED_OFF']
                .map(state => reviseSummary(artifact(state), 'REVISE_EXERCISE'));
            expect(new Set(lines).size).toBe(lines.length - 1);
            expect(reviseSummary(artifact('PROPOSED'), 'REVISE_ITEM')).toContain('прежний текст');
            expect(reviseSummary(artifact('STALE'), 'REVISE_ITEM')).toContain('Материал изменился');
            expect(reviseSummary(null, 'REVISE_ITEM')).toBe('');
        });
    });
});
