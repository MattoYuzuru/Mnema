import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';

import { MECHANICS } from '../../content/exercise/exercise-content.models';
import { ExerciseContentError, parseExerciseSpec, parseObjectiveCommand } from '../../content/exercise/exercise-content.parse';
import { CapabilitiesApiService } from '../authoring/capabilities-api.service';
import { AuthoringProtocolError } from '../authoring/authoring.models';
import { ExerciseApiService } from '../authoring/exercise-api.service';
import { StudyApiService } from './study-api.service';
import { AttemptCommand, AttemptOutcome, StudyProtocolError } from './study.models';
import { assessedOutcome, clone, ids, mechanics, privateHeaders, readySession, removed, removedNames } from './study-test-data';

/**
 * contracts/study/mechanics.json is the single wire contract of the five mechanics. Every fixture must
 * parse and serialize exactly, and unknown fields, legacy types and answer leaks must be rejected.
 */
describe('Mechanics wire contract (contracts/study/mechanics.json)', () => {
    const deckId = ids.deckId;
    let http: HttpTestingController;
    let exercises: ExerciseApiService;
    let study: StudyApiService;
    let capabilities: CapabilitiesApiService;

    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
        http = TestBed.inject(HttpTestingController);
        exercises = TestBed.inject(ExerciseApiService);
        study = TestBed.inject(StudyApiService);
        capabilities = TestBed.inject(CapabilitiesApiService);
    });
    afterEach(() => http.verify());

    const commands = ['createSelfCheck', 'createFreeResponseAudio', 'createCloze', 'createChoiceVideoMultiple',
        'createMatchMixed', 'createOrder', 'createCategorize', 'rejectedAiAssessment', 'rejectedSpeechInput'];

    it('contains no removed mechanic names, no later-epic mechanics and no hintsUsed anywhere', () => {
        const text = JSON.stringify(mechanics);
        for (const token of [...removedNames, 'hintsUsed', 'AUDIO_ASSET']) {
            expect(text).not.toContain(token);
        }
    });

    for (const name of commands) {
        it(`parses and serializes ${name} exactly`, () => {
            const fixture = mechanics[name];
            expect(parseExerciseSpec(fixture.exercise)).toEqual(fixture.exercise);
            expect(parseObjectiveCommand(fixture.objective)).toEqual(fixture.objective);
            // The strict parse is also the serializer: the HTTP body equals the fixture.
            exercises.create(deckId, '6', fixture.expectedDeckRevisionId, fixture.objective, fixture.exercise, fixture.commandId).subscribe();
            const request = http.expectOne(`/api/decks/${deckId}/exercises`);
            expect(request.request.body).toEqual(fixture);
            expect(request.request.headers.get('If-Match')).toBe('"6"');
        });
    }

    it('parses objective operations and publishes a revision with the expected exercise revision', () => {
        expect(parseObjectiveCommand(mechanics['reviseObjective'])).toEqual(mechanics['reviseObjective']);
        expect(parseObjectiveCommand(mechanics['reuseObjective'])).toEqual(mechanics['reuseObjective']);
        const create = mechanics['createCloze'];
        exercises.update(deckId, '88888888-8888-4888-8888-888888888881', '6', create.expectedDeckRevisionId, '88888888-8888-4888-8888-888888888882', mechanics['reviseObjective'], create.exercise, create.commandId).subscribe();
        const request = http.expectOne(`/api/decks/${deckId}/exercises/88888888-8888-4888-8888-888888888881`);
        expect(request.request.body).toEqual({ commandId: create.commandId, expectedDeckRevisionId: create.expectedDeckRevisionId,
            expectedExerciseRevisionId: '88888888-8888-4888-8888-888888888882', objective: mechanics['reviseObjective'],
            exercise: create.exercise });
    });

    it('reads the exercise detail with the typed content, key and evaluator', async () => {
        const reading = firstValueFrom(exercises.read(deckId, mechanics['exerciseDetail'].exerciseId));
        http.expectOne(`/api/decks/${deckId}/exercises/${mechanics['exerciseDetail'].exerciseId}`)
            .flush(mechanics['exerciseDetail'], { headers: { ...privateHeaders, ETag: '"6"' } });
        const detail = await reading;
        expect(detail.type).toBe('CHOICE');
        expect(detail.objective.title).toBe('Признаки реакции в опыте');
        expect(detail.answerKey).toEqual(mechanics['exerciseDetail'].answerKey);
        expect(detail.content).toEqual(mechanics['exerciseDetail'].content);
    });

    it('parses every learner presentation exactly, including the revealed transcript variant', async () => {
        const fixtures = mechanics['presentations'];
        const plain = ['selfCheck', 'freeResponse', 'cloze', 'choice', 'match', 'order', 'categorize'].map(name => fixtures[name]);
        const reading = firstValueFrom(study.read(deckId, ids.sessionId));
        http.expectOne(`/api/decks/${deckId}/study-sessions/${ids.sessionId}`).flush(readySession(plain), { headers: privateHeaders });
        const session = await reading;
        if (session.status === 'PREPARING') {
            expect.fail('Expected a ready session.');
            return;
        }
        expect(session.presentations).toEqual(plain);
        expect(session.presentations.map(item => item.type)).toEqual([...MECHANICS]);

        const revealedReading = firstValueFrom(study.read(deckId, ids.sessionId));
        http.expectOne(`/api/decks/${deckId}/study-sessions/${ids.sessionId}`)
            .flush(readySession([fixtures['freeResponseTranscriptRevealed']]), { headers: privateHeaders });
        const revealed = await revealedReading;
        if (revealed.status !== 'PREPARING')
            expect(revealed.presentations[0]).toEqual(fixtures['freeResponseTranscriptRevealed']);
    });

    it('reveals a transcript and records a hint exactly as fixtured', async () => {
        const presentation = mechanics['presentations']['freeResponse'];
        const reveal = firstValueFrom(study.revealTranscript(deckId, ids.sessionId, presentation.presentationId, mechanics['transcriptRevealCommand'].nonce, 'FREE_RESPONSE'));
        const request = http.expectOne(`/api/decks/${deckId}/study-sessions/${ids.sessionId}/presentations/${presentation.presentationId}/transcript`);
        expect(request.request.body).toEqual(mechanics['transcriptRevealCommand']);
        request.flush(mechanics['transcriptRevealResponse'], { headers: privateHeaders });
        expect((await reveal).content).toEqual({ type: 'FREE_RESPONSE', content: mechanics['transcriptRevealResponse'].content });

        const cloze = mechanics['presentations']['cloze'];
        const hint = firstValueFrom(study.hint(deckId, ids.sessionId, cloze.presentationId, mechanics['hintCommand'].nonce, mechanics['hintCommand'].blankId));
        const hintRequest = http.expectOne(`/api/decks/${deckId}/study-sessions/${ids.sessionId}/presentations/${cloze.presentationId}/hints`);
        expect(hintRequest.request.body).toEqual(mechanics['hintCommand']);
        hintRequest.flush(mechanics['hintResponse'], { headers: privateHeaders });
        expect(await hint).toEqual(mechanics['hintResponse']);
    });

    it('checks a pair with the left and right ids and nothing else', async () => {
        const fixture = mechanics['pairCheck'];
        const checking = firstValueFrom(study.checkPair(deckId, ids.sessionId, fixture.presentationId, fixture.nonce, fixture.leftId, fixture.rightId));
        const request = http.expectOne(`/api/decks/${deckId}/study-sessions/${ids.sessionId}/pair-checks`);
        expect(request.request.body).toEqual(fixture);
        request.flush(mechanics['pairCheckResult'], { headers: privateHeaders });
        expect(await checking).toEqual(mechanics['pairCheckResult']);
    });

    for (const name of ['selfCheck', 'freeResponse', 'cloze', 'choice', 'match', 'order', 'orderEquivalent', 'categorize', 'cancel']) {
        it(`serializes the ${name} attempt exactly and has no hintsUsed`, () => {
            const command = mechanics['submits'][name] as AttemptCommand;
            study.submit(deckId, ids.sessionId, command).subscribe();
            const request = http.expectOne(`/api/decks/${deckId}/study-sessions/${ids.sessionId}/attempts`);
            expect(request.request.body).toEqual(command);
            expect(Object.keys(request.request.body)).not.toContain('hintsUsed');
        });
    }

    for (const name of ['selfCheck', 'freeResponse', 'cloze', 'choice', 'match', 'order', 'orderEquivalent', 'categorize']) {
        it(`parses the ${name} feedback exactly`, async () => {
            const command = mechanics['submits'][name] as AttemptCommand;
            const submitting = firstValueFrom(study.submit(deckId, ids.sessionId, command));
            http.expectOne(`/api/decks/${deckId}/study-sessions/${ids.sessionId}/attempts`)
                .flush(assessedOutcome(command, mechanics['feedback'][name]), { headers: privateHeaders });
            expect(((await submitting).value as AttemptOutcome).feedback).toEqual(mechanics['feedback'][name]);
        });
    }

    it('parses media-not-ready and evaluator-unavailable outcomes without evidence or transition', async () => {
        const command = mechanics['submits']['choice'] as AttemptCommand;
        for (const [name, status] of [['mediaNotReady', 'NOT_ASSESSED'], ['evaluatorUnavailable', 'UNAVAILABLE']]) {
            const submitting = firstValueFrom(study.submit(deckId, ids.sessionId, command));
            http.expectOne(`/api/decks/${deckId}/study-sessions/${ids.sessionId}/attempts`).flush({
                attemptId: command.attemptId, presentationId: command.presentationId, mode: 'SCHEDULED', status,
                evidence: null, feedback: mechanics['feedback'][name], transition: null
            }, { headers: privateHeaders });
            expect(((await submitting).value as AttemptOutcome).feedback).toEqual(mechanics['feedback'][name]);
        }
    });

    it('parses capabilities, including a flag enabled without a provider', async () => {
        for (const name of ['capabilities', 'capabilitiesFlagWithoutProvider']) {
            const reading = firstValueFrom(capabilities.read());
            http.expectOne('/api/capabilities').flush(mechanics[name], { headers: privateHeaders });
            expect(await reading).toEqual(mechanics[name]);
        }
        const cacheable = firstValueFrom(capabilities.read());
        http.expectOne('/api/capabilities').flush(mechanics['capabilities']);
        await expect(cacheable).rejects.toThrowError(AuthoringProtocolError);
    });

    it('keeps the capability-unavailable problem on a stable code', () => {
        expect(mechanics['capabilityUnavailableProblem']).toEqual(expect.objectContaining({
            status: 409, code: 'CAPABILITY_UNAVAILABLE'
        }));
    });

    describe('rejects what the contract forbids', () => {
        it('legacy mechanic names, unknown fields and answer leaks in the exercise', () => {
            const base = mechanics['createChoiceVideoMultiple'].exercise;
            const legacy = clone(base);
            legacy.type = removed.singleChoice;
            expect(() => parseExerciseSpec(legacy)).toThrowError(ExerciseContentError);
            const extra = clone(base);
            extra.bindings = [];
            expect(() => parseExerciseSpec(extra)).toThrowError(ExerciseContentError);
            const block = clone(base);
            block.content.prompt[1].html = '<b>x</b>';
            expect(() => parseExerciseSpec(block)).toThrowError(ExerciseContentError);
            const v1 = clone(base);
            v1.schemaVersion = 1;
            expect(() => parseExerciseSpec(v1)).toThrowError(ExerciseContentError);
            const mismatch = clone(base);
            mismatch.evaluatorPolicy = { id: 'deterministic-match', version: '1' };
            expect(() => parseExerciseSpec(mismatch)).toThrowError(ExerciseContentError);
        });

        it('structural violations of slots, keys and objectives', () => {
            const choice = clone(mechanics['createChoiceVideoMultiple'].exercise);
            choice.content.selectionMode = 'SINGLE';
            expect(() => parseExerciseSpec(choice)).toThrowError(ExerciseContentError); // two correct options
            const image = clone(mechanics['createSelfCheck'].exercise);
            image.content.prompt[0].alt = '  ';
            expect(() => parseExerciseSpec(image)).toThrowError(ExerciseContentError);
            const youtubeCompact = clone(mechanics['createChoiceVideoMultiple'].exercise);
            youtubeCompact.content.options[0].blocks = [{ kind: 'YOUTUBE', videoId: 'dQw4w9WgXcQ', title: 'x' }];
            expect(() => parseExerciseSpec(youtubeCompact)).toThrowError(ExerciseContentError);
            const cloze = clone(mechanics['createCloze'].exercise);
            cloze.answerKey.blanks.pop();
            expect(() => parseExerciseSpec(cloze)).toThrowError(ExerciseContentError);
            const match = clone(mechanics['createMatchMixed'].exercise);
            match.answerKey.pairs[1].rightId = match.answerKey.pairs[0].rightId;
            expect(() => parseExerciseSpec(match)).toThrowError(ExerciseContentError);
            expect(() => parseObjectiveCommand({ operation: 'create', title: '  ' })).toThrowError(ExerciseContentError);
            expect(() => parseObjectiveCommand({ ...mechanics['reuseObjective'], title: 'x' })).toThrowError(ExerciseContentError);
        });

        it('ORDER and CATEGORIZE keys, items and groups that break their rules', () => {
            const order = (change: (spec: any) => void) => {
                const spec = clone(mechanics['createOrder'].exercise);
                change(spec);
                expect(() => parseExerciseSpec(spec)).toThrowError(ExerciseContentError);
            };
            order(spec => { spec.answerKey.sequence.pop(); }); // missing item
            order(spec => { spec.answerKey.sequence[1] = spec.answerKey.sequence[0]; }); // duplicate item
            order(spec => { spec.answerKey.sequence[0] = '0d000000-0000-4000-8000-0000000000ff'; }); // foreign item
            order(spec => { spec.content.items.pop(); spec.content.items.pop(); spec.content.items.pop(); spec.content.items.pop(); spec.content.items.pop(); }); // one item
            order(spec => { spec.content.items[1].itemId = spec.content.items[0].itemId; }); // duplicate id
            order(spec => { spec.content.items[0].blocks = [{ kind: 'TEXT', text: 'x'.repeat(1001) }]; }); // SEQUENCE bound
            order(spec => { spec.content.items[0].blocks = [{ kind: 'YOUTUBE', videoId: 'dQw4w9WgXcQ', title: 'x' }]; });
            order(spec => { spec.evaluatorPolicy = { id: 'deterministic-match', version: '1' }; });
            const categorize = (change: (spec: any) => void) => {
                const spec = clone(mechanics['createCategorize'].exercise);
                change(spec);
                expect(() => parseExerciseSpec(spec)).toThrowError(ExerciseContentError);
            };
            categorize(spec => { spec.answerKey.assignments.pop(); }); // item without a group
            categorize(spec => { spec.answerKey.assignments[0].categoryId = 'ca000000-0000-4000-8000-0000000000ff'; }); // dangling group
            categorize(spec => { spec.answerKey.assignments[1].itemId = spec.answerKey.assignments[0].itemId; }); // twice
            categorize(spec => { spec.content.categories[1].label = ' СУЩЕСТВИТЕЛЬНОЕ '; }); // same after trim and case fold
            categorize(spec => { spec.content.categories[0].label = 'я'.repeat(81); });
            categorize(spec => { spec.content.categories.splice(2, 1); spec.content.categories.splice(1, 1); }); // one group
            categorize(spec => { spec.content.categories.push(...[4, 5, 6, 7].map(n => ({ categoryId: `ca000000-0000-4000-8000-00000000000${n}`, label: `Г${n}` }))); });
            categorize(spec => { spec.content.items[0].blocks = [{ kind: 'TEXT', text: 'x'.repeat(301) }]; });
        });

        it('a publication command that does not satisfy the contract never reaches the network', async () => {
            const bad = clone(mechanics['createCloze']);
            bad.exercise.answerKey.blanks[0].accepted = ['map', 'map'];
            const writing = firstValueFrom(exercises.create(deckId, '6', bad.expectedDeckRevisionId, bad.objective, bad.exercise, bad.commandId));
            await expect(writing).rejects.toThrowError(AuthoringProtocolError);
            http.expectNone(`/api/decks/${deckId}/exercises`);
        });

        it('learner presentations that leak keys, titles or unrevealed transcripts', async () => {
            const read = async (value: unknown) => {
                const reading = firstValueFrom(study.read(deckId, ids.sessionId));
                http.expectOne(`/api/decks/${deckId}/study-sessions/${ids.sessionId}`)
                    .flush(readySession([value]), { headers: privateHeaders });
                await expect(reading).rejects.toThrowError(StudyProtocolError);
            };
            const choice = clone(mechanics['presentations']['choice']);
            choice.correctOptionIds = [];
            await read(choice);
            const bindings = clone(mechanics['presentations']['choice']);
            bindings.bindings = [];
            await read(bindings);
            const title = clone(mechanics['presentations']['freeResponse']);
            title.content.prompt[0].title = 'Слово 12';
            await read(title);
            const transcript = clone(mechanics['presentations']['freeResponse']);
            transcript.content.prompt[0].transcript = 'Erinnerung';
            await read(transcript);
            const legacy = clone(mechanics['presentations']['selfCheck']);
            legacy.type = removed.typed;
            await read(legacy);
            const hint = clone(mechanics['presentations']['cloze']);
            hint.hints = [{ blankId: 'b1a00000-0000-4000-8000-000000000002', firstLetter: 't' }];
            await read(hint); // blank 2 has no first-letter hint
        });

        it('ORDER and CATEGORIZE presentations that repeat items or leak the key', async () => {
            const read = async (value: unknown) => {
                const reading = firstValueFrom(study.read(deckId, ids.sessionId));
                http.expectOne(`/api/decks/${deckId}/study-sessions/${ids.sessionId}`)
                    .flush(readySession([value]), { headers: privateHeaders });
                await expect(reading).rejects.toThrowError(StudyProtocolError);
            };
            const duplicate = clone(mechanics['presentations']['order']);
            duplicate.content.items[1].itemId = duplicate.content.items[0].itemId;
            await read(duplicate);
            const leak = clone(mechanics['presentations']['order']);
            leak.content.sequence = [];
            await read(leak);
            const wrongEvaluator = clone(mechanics['presentations']['categorize']);
            wrongEvaluator.evaluator.id = 'deterministic-order';
            await read(wrongEvaluator);
            const keyed = clone(mechanics['presentations']['categorize']);
            keyed.content.items[0].categoryId = keyed.content.categories[0].categoryId;
            await read(keyed);
            const labelClash = clone(mechanics['presentations']['categorize']);
            labelClash.content.categories[1].label = 'существительное';
            await read(labelClash);
        });

        it('ORDER and CATEGORIZE responses that are not a full, duplicate-free answer never leave the browser', async () => {
            const order = mechanics['submits']['order'];
            const categorize = mechanics['submits']['categorize'];
            const broken: AttemptCommand[] = [
                { ...order, response: { kind: 'ORDER', sequence: [order.response['sequence'][0]] } },
                { ...order, response: { kind: 'ORDER', sequence: [order.response['sequence'][0], order.response['sequence'][0]] } },
                { ...order, response: { kind: 'ORDER', sequence: order.response['sequence'], extra: 1 } },
                { ...categorize, response: { kind: 'CATEGORIZE', assignments: [categorize.response['assignments'][0]] } },
                { ...categorize, response: { kind: 'CATEGORIZE', assignments: [categorize.response['assignments'][0], categorize.response['assignments'][0]] } },
                { ...categorize, response: { kind: 'CATEGORIZE', assignments: categorize.response['assignments'].map((entry: object) => ({ ...entry, label: 'x' })) } }
            ];
            for (const command of broken)
                await expect(firstValueFrom(study.submit(deckId, ids.sessionId, command))).rejects.toThrowError(StudyProtocolError);
            http.expectNone(`/api/decks/${deckId}/study-sessions/${ids.sessionId}/attempts`);
        });

        it('ORDER and CATEGORIZE feedback that contradicts its own verdict or ids is a protocol error', async () => {
            const submit = async (name: string, change: (feedback: any) => void) => {
                const command = mechanics['submits'][name] as AttemptCommand;
                const feedback = clone(mechanics['feedback'][name]);
                change(feedback);
                const submitting = firstValueFrom(study.submit(deckId, ids.sessionId, command));
                http.expectOne(`/api/decks/${deckId}/study-sessions/${ids.sessionId}/attempts`)
                    .flush(assessedOutcome(command, feedback), { headers: privateHeaders });
                await expect(submitting).rejects.toThrowError(StudyProtocolError);
            };
            await submit('order', feedback => { feedback.result = 'PARTIAL'; }); // ORDER is binary
            await submit('order', feedback => { feedback.result = 'CORRECT'; }); // wrong positions exist
            await submit('order', feedback => { feedback.positions[2].position = 7; });
            await submit('order', feedback => { feedback.correctSequence.pop(); });
            await submit('order', feedback => { feedback.positions[0].selectedItemId = feedback.positions[1].selectedItemId; });
            await submit('categorize', feedback => { feedback.result = 'CORRECT'; }); // one assignment is wrong
            await submit('categorize', feedback => { feedback.result = 'INCORRECT'; }); // three are right
            await submit('categorize', feedback => { feedback.assignments[1].itemId = feedback.assignments[0].itemId; });
        });

        it('an attempt carrying legacy fields or the wrong response shape', async () => {
            const spoof = { ...mechanics['submits']['freeResponse'], hintsUsed: [] };
            await expect(firstValueFrom(study.submit(deckId, ids.sessionId, spoof))).rejects.toThrowError(StudyProtocolError);
            const speech = { ...mechanics['submits']['freeResponse'], response: { kind: 'SPEECH', audio: 'x' } };
            await expect(firstValueFrom(study.submit(deckId, ids.sessionId, speech))).rejects.toThrowError(StudyProtocolError);
            http.expectNone(`/api/decks/${deckId}/study-sessions/${ids.sessionId}/attempts`);
        });
    });
});
