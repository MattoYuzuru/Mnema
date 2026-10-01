import { HttpClient, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, defer, map } from 'rxjs';

import { appConfig } from '../../app.config';
import { PreviewExercise, previewExerciseOf } from '../../content/exercise/exercise-content.models';
import { parseExerciseSpec } from '../../content/exercise/exercise-content.parse';
import { parseAttemptFeedback } from '../study/attempt-feedback.parse';
import { AttemptFeedback, isChoiceFeedback, isClozeFeedback, isFreeResponseFeedback, isMatchFeedback, isUnassessed } from '../study/study.models';
import { entity, exact, guard, hintLetter, protocol, validateResponse } from '../study/study-wire';
import { PreviewSubmission } from './exercise-preview.models';

/** Placeholder subject: the preview contract carries none, but the shared strict parser needs a valid one. */
const NIL = '00000000-0000-4000-8000-000000000000';

/**
 * Author preview evaluation (`POST /api/exercise-previews`, contracts/study/preview.json). The server runs the
 * same evaluator as Study without reading or writing any exercise, session, attempt or progress state, so the
 * browser never evaluates answers itself. Responses are parsed strictly and a mismatch is a protocol error.
 */
@Injectable({ providedIn: 'root' })
export class ExercisePreviewApiService {
    private readonly http = inject(HttpClient);
    private readonly url = `${appConfig.learningApiBaseUrl.replace(/\/$/, '')}/exercise-previews`;

    /** Evaluates one trial answer. */
    submit(exercise: PreviewExercise, submission: PreviewSubmission): Observable<AttemptFeedback> {
        return this.post(exercise, () => {
            validateResponse(submission.response);
            const hinted = submission.hintedBlankIds.map(entity);
            if (new Set(hinted).size !== hinted.length || hinted.length > 12) throw protocol('Invalid hinted blanks.');
            if (typeof submission.pairMistakes !== 'boolean' || typeof submission.transcriptRevealed !== 'boolean') {
                throw protocol('Invalid preview flags.');
            }
            return { kind: 'SUBMIT', response: submission.response, hintedBlankIds: hinted,
                pairMistakes: submission.pairMistakes, transcriptRevealed: submission.transcriptRevealed };
        }).pipe(map(body => {
            const object = exact(body, ['feedback']);
            const feedback = parseAttemptFeedback(object['feedback']);
            if (!feedbackFits(exercise, feedback)) throw protocol('Feedback does not match the exercise.');
            return feedback;
        }));
    }

    /** Checks one pair of a MATCH preview against the author's key. */
    checkPair(exercise: PreviewExercise, leftId: string, rightId: string): Observable<boolean> {
        return this.post(exercise, () => ({ kind: 'PAIR_CHECK', leftId: entity(leftId), rightId: entity(rightId) }))
            .pipe(map(body => {
                const object = exact(body, ['correct']);
                if (typeof object['correct'] !== 'boolean') throw protocol('Invalid pair check result.');
                return object['correct'];
            }));
    }

    /** The first letter of one CLOZE blank; the browser never derives it. */
    hint(exercise: PreviewExercise, blankId: string): Observable<string> {
        return defer(() => {
            const blank = entity(blankId);
            return this.post(exercise, () => ({ kind: 'HINT', blankId: blank })).pipe(map(body => {
                const object = exact(body, ['blankId', 'firstLetter']);
                if (entity(object['blankId']) !== blank) throw protocol('Hint scope mismatch.');
                return hintLetter(object['firstLetter']);
            }));
        });
    }

    private post(exercise: PreviewExercise, action: () => Record<string, unknown>): Observable<unknown> {
        return defer(() => {
            const checked = guard(() => parseExerciseSpec({ ...exercise, enabled: true,
                subject: { memberKey: NIL, itemRevisionId: NIL } }));
            const body = { exercise: previewExerciseOf(checked), action: action() };
            return this.http.post<unknown>(this.url, body, { observe: 'response' });
        }).pipe(map(response => {
            if (response.status !== 200) throw protocol('Unexpected preview status.');
            requirePrivate(response);
            return response.body;
        }));
    }
}

/** Feedback keeps the shape of its mechanic; an unassessed verdict fits every mechanic. */
function feedbackFits(exercise: PreviewExercise, feedback: AttemptFeedback): boolean {
    if (isUnassessed(feedback)) return true;
    switch (exercise.type) {
        case 'CLOZE': return isClozeFeedback(feedback);
        case 'CHOICE': return isChoiceFeedback(feedback);
        case 'MATCH': return isMatchFeedback(feedback);
        // The editor shows the author's own reference, so the server returns no extra reference content.
        case 'FREE_RESPONSE': return isFreeResponseFeedback(feedback) && feedback.referenceContent.length === 0;
        case 'SELF_CHECK': return !isClozeFeedback(feedback) && !isChoiceFeedback(feedback) && !isMatchFeedback(feedback)
            && !isFreeResponseFeedback(feedback);
    }
}

function requirePrivate(response: HttpResponse<unknown>): void {
    const directives = (response.headers.get('Cache-Control') ?? '').toLowerCase().split(',').map(value => value.trim());
    if (!directives.includes('private') || !directives.includes('no-store')) throw protocol('Preview response can be cached.');
}
