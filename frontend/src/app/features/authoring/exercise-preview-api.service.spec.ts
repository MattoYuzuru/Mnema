import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';

import { PreviewExercise } from '../../content/exercise/exercise-content.models';
import { clone, previews } from '../study/study-test-data';
import { StudyProtocolError } from '../study/study.models';
import { ExercisePreviewApiService } from './exercise-preview-api.service';

describe('ExercisePreviewApiService', () => {
    let api: ExercisePreviewApiService;
    let http: HttpTestingController;
    const headers = { 'Cache-Control': 'private, no-store' };
    const url = '/api/exercise-previews';

    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
        api = TestBed.inject(ExercisePreviewApiService);
        http = TestBed.inject(HttpTestingController);
    });
    afterEach(() => http.verify());

    const submission = (name: string) => {
        const action = previews[name].action;
        return { response: action.response, hintedBlankIds: action.hintedBlankIds, pairMistakes: action.pairMistakes,
            transcriptRevealed: action.transcriptRevealed };
    };

    it('sends exactly the contract request for every SUBMIT fixture and parses the golden feedback', async () => {
        for (const name of ['submitCloze', 'submitChoice', 'submitMatchAfterMistake', 'submitFreeResponse', 'submitOrder', 'submitCategorize']) {
            const result = firstValueFrom(api.submit(previews[name].exercise, submission(name)));
            const request = http.expectOne(url);
            expect(request.request.method).toBe('POST');
            expect(request.request.body, name).toEqual(previews[name]);
            request.flush(previews[name + 'Result'], { headers });
            expect(await result, name).toEqual(previews[name + 'Result'].feedback);
        }
    });

    it('checks one pair and asks for one hint through the same endpoint', async () => {
        const pair = firstValueFrom(api.checkPair(previews['pairCheck'].exercise, previews['pairCheck'].action.leftId, previews['pairCheck'].action.rightId));
        const pairRequest = http.expectOne(url);
        expect(pairRequest.request.body).toEqual(previews['pairCheck']);
        pairRequest.flush(previews['pairCheckResult'], { headers });
        expect(await pair).toBe(false);

        const hint = firstValueFrom(api.hint(previews['hint'].exercise, previews['hint'].action.blankId));
        const hintRequest = http.expectOne(url);
        expect(hintRequest.request.body).toEqual(previews['hint']);
        hintRequest.flush(previews['hintResult'], { headers });
        expect(await hint).toBe('m');
    });

    it('returns an unavailable verdict as it is, never a substitute result', async () => {
        const result = firstValueFrom(api.submit(previews['submitFreeResponse'].exercise, submission('submitFreeResponse')));
        http.expectOne(url).flush(previews['aiSemanticResult'], { headers });
        expect(await result).toEqual({ result: 'UNAVAILABLE', reasonCodes: ['EVALUATOR_UNAVAILABLE'] });
    });

    it('sends no authoring-only fields and refuses an exercise the publication rules would refuse', async () => {
        const exercise = clone(previews['submitFreeResponse'].exercise) as Record<string, unknown>;
        const sent = firstValueFrom(api.submit({ ...exercise, enabled: true, subject: { memberKey: 'x' } } as unknown as PreviewExercise, submission('submitFreeResponse')));
        const request = http.expectOne(url);
        expect(Object.keys(request.request.body.exercise).sort()).toEqual(['answerKey', 'content', 'evaluatorPolicy', 'schemaVersion', 'type']);
        request.flush(previews['submitFreeResponseResult'], { headers });
        await sent;

        const broken = clone(previews['submitFreeResponse'].exercise);
        broken.answerKey.accepted = [];
        await expect(firstValueFrom(api.submit(broken, submission('submitFreeResponse')))).rejects.toThrow();
        http.expectNone(url);
    });

    it('refuses a malformed request before it leaves the browser', async () => {
        const base = submission('submitCloze');
        const exercise = previews['submitCloze'].exercise;
        const bad = [
            { ...base, response: { kind: 'CLOZE', blanks: [] } },
            { ...base, hintedBlankIds: ['not-an-id'] },
            { ...base, hintedBlankIds: [base.hintedBlankIds[0], base.hintedBlankIds[0]] },
            { ...base, pairMistakes: 'yes' as unknown as boolean }
        ];
        for (const entry of bad) {
            await expect(firstValueFrom(api.submit(exercise, entry as never))).rejects.toThrowError(StudyProtocolError);
        }
        await expect(firstValueFrom(api.checkPair(exercise, 'x', 'y'))).rejects.toThrowError(StudyProtocolError);
        await expect(firstValueFrom(api.hint(exercise, 'x'))).rejects.toThrowError(StudyProtocolError);
        http.expectNone(url);
    });

    describe('strict response parsing', () => {
        const submit = (body: object, extra: Record<string, string> = headers, status = 200) => {
            const result = firstValueFrom(api.submit(previews['submitChoice'].exercise, submission('submitChoice')));
            http.expectOne(url).flush(body, { headers: extra, status, statusText: 'x' });
            return result;
        };

        it('rejects a response that can be cached, a wrong status and unknown fields', async () => {
            await expect(submit(previews['submitChoiceResult'], { 'Cache-Control': 'max-age=60' })).rejects.toThrowError(/cached/);
            await expect(submit(previews['submitChoiceResult'], headers, 201)).rejects.toThrowError(/status/);
            await expect(submit({ ...previews['submitChoiceResult'], evidence: null })).rejects.toThrowError(/shape/);
            await expect(submit({ feedback: { ...previews['submitChoiceResult'].feedback, extra: 1 } })).rejects.toThrowError(/shape/);
        });

        it('rejects feedback that belongs to another mechanic or carries extra reference content', async () => {
            await expect(submit(previews['submitClozeResult'])).rejects.toThrowError(/does not match/);
            const free = firstValueFrom(api.submit(previews['submitFreeResponse'].exercise, submission('submitFreeResponse')));
            http.expectOne(url).flush({ feedback: { ...previews['submitFreeResponseResult'].feedback,
                    referenceContent: [{ kind: 'TEXT', text: 'x' }] } }, { headers });
            await expect(free).rejects.toThrowError(/does not match/);
        });

        it('rejects ORDER feedback for a CATEGORIZE exercise and the other way round', async () => {
            const categorize = firstValueFrom(api.submit(previews['submitCategorize'].exercise, submission('submitCategorize')));
            http.expectOne(url).flush(previews['submitOrderResult'], { headers });
            await expect(categorize).rejects.toThrowError(/does not match/);
            const order = firstValueFrom(api.submit(previews['submitOrder'].exercise, submission('submitOrder')));
            http.expectOne(url).flush(previews['submitCategorizeResult'], { headers });
            await expect(order).rejects.toThrowError(/does not match/);
        });

        it('rejects a hint for another blank, a non-boolean pair verdict and an extra pair field', async () => {
            const hint = firstValueFrom(api.hint(previews['hint'].exercise, previews['hint'].action.blankId));
            http.expectOne(url).flush({ blankId: 'b1a00000-0000-4000-8000-000000000002', firstLetter: 'm' }, { headers });
            await expect(hint).rejects.toThrowError(/scope/);
            const pair = firstValueFrom(api.checkPair(previews['pairCheck'].exercise, previews['pairCheck'].action.leftId, previews['pairCheck'].action.rightId));
            http.expectOne(url).flush({ correct: 'no' }, { headers });
            await expect(pair).rejects.toThrowError(/pair check/);
            const extra = firstValueFrom(api.checkPair(previews['pairCheck'].exercise, previews['pairCheck'].action.leftId, previews['pairCheck'].action.rightId));
            http.expectOne(url).flush({ correct: true, hint: 'x' }, { headers });
            await expect(extra).rejects.toThrowError(/shape/);
        });
    });
});
