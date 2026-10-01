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
import { AttemptCommand, StudyProtocolError } from './study.models';
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
        'createMatchMixed', 'rejectedAiAssessment', 'rejectedSpeechInput'];

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
        exercises.update(deckId, '88888888-8888-4888-8888-888888888881', '6', create.expectedDeckRevisionId,
            '88888888-8888-4888-8888-888888888882', mechanics['reviseObjective'], create.exercise, create.commandId).subscribe();
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
        const plain = ['selfCheck', 'freeResponse', 'cloze', 'choice', 'match'].map(name => fixtures[name]);
        const reading = firstValueFrom(study.read(deckId, ids.sessionId));
        http.expectOne(`/api/decks/${deckId}/study-sessions/${ids.sessionId}`).flush(readySession(plain), { headers: privateHeaders });
        const session = await reading;
        if (session.status === 'PREPARING') { fail('Expected a ready session.'); return; }
        expect(session.presentations).toEqual(plain);
        expect(session.presentations.map(item => item.type)).toEqual([...MECHANICS]);

        const revealedReading = firstValueFrom(study.read(deckId, ids.sessionId));
        http.expectOne(`/api/decks/${deckId}/study-sessions/${ids.sessionId}`)
            .flush(readySession([fixtures['freeResponseTranscriptRevealed']]), { headers: privateHeaders });
        const revealed = await revealedReading;
        if (revealed.status !== 'PREPARING') expect(revealed.presentations[0]).toEqual(fixtures['freeResponseTranscriptRevealed']);
    });

    it('reveals a transcript and records a hint exactly as fixtured', async () => {
        const presentation = mechanics['presentations']['freeResponse'];
        const reveal = firstValueFrom(study.revealTranscript(deckId, ids.sessionId, presentation.presentationId,
            mechanics['transcriptRevealCommand'].nonce, 'FREE_RESPONSE'));
        const request = http.expectOne(`/api/decks/${deckId}/study-sessions/${ids.sessionId}/presentations/${presentation.presentationId}/transcript`);
        expect(request.request.body).toEqual(mechanics['transcriptRevealCommand']);
        request.flush(mechanics['transcriptRevealResponse'], { headers: privateHeaders });
        expect((await reveal).content).toEqual({ type: 'FREE_RESPONSE', content: mechanics['transcriptRevealResponse'].content });

        const cloze = mechanics['presentations']['cloze'];
        const hint = firstValueFrom(study.hint(deckId, ids.sessionId, cloze.presentationId,
            mechanics['hintCommand'].nonce, mechanics['hintCommand'].blankId));
        const hintRequest = http.expectOne(`/api/decks/${deckId}/study-sessions/${ids.sessionId}/presentations/${cloze.presentationId}/hints`);
        expect(hintRequest.request.body).toEqual(mechanics['hintCommand']);
        hintRequest.flush(mechanics['hintResponse'], { headers: privateHeaders });
        expect(await hint).toEqual(mechanics['hintResponse']);
    });

    it('checks a pair with the left and right ids and nothing else', async () => {
        const fixture = mechanics['pairCheck'];
        const checking = firstValueFrom(study.checkPair(deckId, ids.sessionId, fixture.presentationId, fixture.nonce,
            fixture.leftId, fixture.rightId));
        const request = http.expectOne(`/api/decks/${deckId}/study-sessions/${ids.sessionId}/pair-checks`);
        expect(request.request.body).toEqual(fixture);
        request.flush(mechanics['pairCheckResult'], { headers: privateHeaders });
        expect(await checking).toEqual(mechanics['pairCheckResult']);
    });

    for (const name of ['selfCheck', 'freeResponse', 'cloze', 'choice', 'match', 'cancel']) {
        it(`serializes the ${name} attempt exactly and has no hintsUsed`, () => {
            const command = mechanics['submits'][name] as AttemptCommand;
            study.submit(deckId, ids.sessionId, command).subscribe();
            const request = http.expectOne(`/api/decks/${deckId}/study-sessions/${ids.sessionId}/attempts`);
            expect(request.request.body).toEqual(command);
            expect(Object.keys(request.request.body)).not.toContain('hintsUsed');
        });
    }

    for (const name of ['selfCheck', 'freeResponse', 'cloze', 'choice', 'match']) {
        it(`parses the ${name} feedback exactly`, async () => {
            const command = mechanics['submits'][name] as AttemptCommand;
            const submitting = firstValueFrom(study.submit(deckId, ids.sessionId, command));
            http.expectOne(`/api/decks/${deckId}/study-sessions/${ids.sessionId}/attempts`)
                .flush(assessedOutcome(command, mechanics['feedback'][name]), { headers: privateHeaders });
            expect((await submitting).value.feedback).toEqual(mechanics['feedback'][name]);
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
            expect((await submitting).value.feedback).toEqual(mechanics['feedback'][name]);
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
        await expectAsync(cacheable).toBeRejectedWithError(AuthoringProtocolError);
    });

    it('keeps the capability-unavailable problem on a stable code', () => {
        expect(mechanics['capabilityUnavailableProblem']).toEqual(jasmine.objectContaining({
            status: 409, code: 'CAPABILITY_UNAVAILABLE' }));
    });

    describe('rejects what the contract forbids', () => {
        it('legacy mechanic names, unknown fields and answer leaks in the exercise', () => {
            const base = mechanics['createChoiceVideoMultiple'].exercise;
            const legacy = clone(base); legacy.type = removed.singleChoice;
            expect(() => parseExerciseSpec(legacy)).toThrowError(ExerciseContentError);
            const extra = clone(base); extra.bindings = [];
            expect(() => parseExerciseSpec(extra)).toThrowError(ExerciseContentError);
            const block = clone(base); block.content.prompt[1].html = '<b>x</b>';
            expect(() => parseExerciseSpec(block)).toThrowError(ExerciseContentError);
            const v1 = clone(base); v1.schemaVersion = 1;
            expect(() => parseExerciseSpec(v1)).toThrowError(ExerciseContentError);
            const mismatch = clone(base); mismatch.evaluatorPolicy = { id: 'deterministic-match', version: '1' };
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

        it('a publication command that does not satisfy the contract never reaches the network', async () => {
            const bad = clone(mechanics['createCloze']);
            bad.exercise.answerKey.blanks[0].accepted = ['map', 'map'];
            const writing = firstValueFrom(exercises.create(deckId, '6', bad.expectedDeckRevisionId, bad.objective, bad.exercise, bad.commandId));
            await expectAsync(writing).toBeRejectedWithError(AuthoringProtocolError);
            http.expectNone(`/api/decks/${deckId}/exercises`);
        });

        it('learner presentations that leak keys, titles or unrevealed transcripts', async () => {
            const read = async (value: unknown) => {
                const reading = firstValueFrom(study.read(deckId, ids.sessionId));
                http.expectOne(`/api/decks/${deckId}/study-sessions/${ids.sessionId}`)
                    .flush(readySession([value]), { headers: privateHeaders });
                await expectAsync(reading).toBeRejectedWithError(StudyProtocolError);
            };
            const choice = clone(mechanics['presentations']['choice']); choice.correctOptionIds = [];
            await read(choice);
            const bindings = clone(mechanics['presentations']['choice']); bindings.bindings = [];
            await read(bindings);
            const title = clone(mechanics['presentations']['freeResponse']); title.content.prompt[0].title = 'Слово 12';
            await read(title);
            const transcript = clone(mechanics['presentations']['freeResponse']); transcript.content.prompt[0].transcript = 'Erinnerung';
            await read(transcript);
            const legacy = clone(mechanics['presentations']['selfCheck']); legacy.type = removed.typed;
            await read(legacy);
            const hint = clone(mechanics['presentations']['cloze']); hint.hints = [{ blankId: 'b1a00000-0000-4000-8000-000000000002', firstLetter: 't' }];
            await read(hint); // blank 2 has no first-letter hint
        });

        it('an attempt carrying legacy fields or the wrong response shape', async () => {
            const spoof = { ...mechanics['submits']['freeResponse'], hintsUsed: [] };
            await expectAsync(firstValueFrom(study.submit(deckId, ids.sessionId, spoof))).toBeRejectedWithError(StudyProtocolError);
            const speech = { ...mechanics['submits']['freeResponse'], response: { kind: 'SPEECH', audio: 'x' } };
            await expectAsync(firstValueFrom(study.submit(deckId, ids.sessionId, speech))).toBeRejectedWithError(StudyProtocolError);
            http.expectNone(`/api/decks/${deckId}/study-sessions/${ids.sessionId}/attempts`);
        });
    });
});
