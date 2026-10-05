import { HttpHeaders, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';

import { LearningProfileApiService } from './learning-profile-api.service';
import { PlansProtocolError } from '../plans/plans.models';

const PRIVATE = new HttpHeaders({ 'Cache-Control': 'private, no-store' });

describe('LearningProfileApiService', () => {
    let api: LearningProfileApiService;
    let http: HttpTestingController;

    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
        api = TestBed.inject(LearningProfileApiService);
        http = TestBed.inject(HttpTestingController);
    });
    afterEach(() => http.verify());

    it('reads an unanswered profile, a goal and a skip', async () => {
        for (const body of [{ goal: null, skipped: false, answeredAt: null }, { goal: 'EXAMS', skipped: false, answeredAt: '2026-10-05T09:00:00Z' },
            { goal: null, skipped: true, answeredAt: '2026-10-05T09:00:00Z' }]) {
            const result = firstValueFrom(api.load());
            http.expectOne('/api/learning-profile').flush(body, { headers: PRIVATE });
            expect(await result).toEqual(body);
        }
    });

    it('puts a goal, and a skip as an explicit null goal', async () => {
        const goal = firstValueFrom(api.answer('WORK'));
        const first = http.expectOne('/api/learning-profile');
        expect(first.request.method).toBe('PUT');
        expect(first.request.body).toEqual({ goal: 'WORK' });
        first.flush({ goal: 'WORK', skipped: false, answeredAt: '2026-10-05T09:00:00Z' }, { headers: PRIVATE });
        expect((await goal).goal).toBe('WORK');

        const skip = firstValueFrom(api.answer(null));
        const second = http.expectOne('/api/learning-profile');
        expect(second.request.body).toEqual({ goal: null, skipped: true });
        second.flush({ goal: null, skipped: true, answeredAt: '2026-10-05T09:00:00Z' }, { headers: PRIVATE });
        expect((await skip).skipped).toBe(true);
    });

    it('rejects unknown goals, extra members and cacheable responses', async () => {
        const bodies: [unknown, HttpHeaders][] = [
            [{ goal: 'FAME', skipped: false, answeredAt: null }, PRIVATE],
            [{ goal: null, skipped: false, answeredAt: null, extra: 1 }, PRIVATE],
            [{ goal: null, skipped: 'no', answeredAt: null }, PRIVATE],
            [{ goal: null, skipped: false, answeredAt: 'yesterday' }, PRIVATE],
            [{ goal: null, skipped: false, answeredAt: null }, new HttpHeaders({ 'Cache-Control': 'public' })]
        ];
        for (const [body, headers] of bodies) {
            const result = firstValueFrom(api.load()).then(() => null, (error: unknown) => error);
            http.expectOne('/api/learning-profile').flush(body as object, { headers });
            expect(await result).toBeInstanceOf(PlansProtocolError);
        }
    });
});
