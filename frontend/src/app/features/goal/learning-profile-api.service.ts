import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { appConfig } from '../../app.config';
import { LEARNING_GOALS, LearningGoal, LearningProfile } from './goal.models';
import { bool, exact, instant, nullable, oneOf, privateOk } from '../plans/wire';

/** HTTP boundary of the goal answer. The goal tunes copy and the recommended tier only; it never reaches an AI provider. */
@Injectable({ providedIn: 'root' })
export class LearningProfileApiService {
    private readonly http = inject(HttpClient);
    private readonly url = `${appConfig.learningApiBaseUrl.replace(/\/$/u, '')}/learning-profile`;

    load(): Observable<LearningProfile> {
        return this.http.get<unknown>(this.url, { observe: 'response' }).pipe(map(response => {
            privateOk(response);
            return parseProfile(response.body);
        }));
    }

    /** Records a goal, or (with `null`) a skip. */
    answer(goal: LearningGoal | null): Observable<LearningProfile> {
        const body = goal === null ? { goal: null, skipped: true } : { goal };
        return this.http.put<unknown>(this.url, body, { observe: 'response' }).pipe(map(response => {
            privateOk(response);
            return parseProfile(response.body);
        }));
    }
}

export function parseProfile(value: unknown): LearningProfile {
    const profile = exact(value, ['goal', 'skipped', 'answeredAt']);
    return {
        goal: nullable(profile['goal'], goal => oneOf(goal, LEARNING_GOALS)), skipped: bool(profile['skipped']),
        answeredAt: nullable(profile['answeredAt'], instant)
    };
}
