import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';

import journey from '../../../../../contracts/onboarding/journey.json';
import { AuthService } from '../../auth.service';
import { AdminOnboardingPageComponent } from './admin-onboarding-page.component';
import { AdminSession } from './admin-api.service';
import { parseOnboardingReport } from './admin.models';
import { clone, ownerId } from './admin-test-data';

/** contracts/onboarding/journey.json `report` is shared with the backend: the example parses strictly and renders aggregates only. */
describe('Onboarding holdout report', () => {
    let http: HttpTestingController;
    let query: BehaviorSubject<ReturnType<typeof convertToParamMap>>;

    async function render(params: Record<string, string> = { from: '2026-09-01', through: '2026-09-30' }) {
        TestBed.configureTestingModule({
            providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([]),
                { provide: AuthService, useValue: { status: () => 'authenticated', user: () => ({ accountId: ownerId }) } }]
        });
        query = new BehaviorSubject(convertToParamMap(params));
        Object.defineProperty(TestBed.inject(ActivatedRoute), 'queryParamMap', { value: query.asObservable() });
        TestBed.inject(AdminSession).access.set({ owner: true, permissions: { events: true, moderation: true, promos: true, support: true } });
        http = TestBed.inject(HttpTestingController);
        const fixture = TestBed.createComponent(AdminOnboardingPageComponent);
        fixture.detectChanges();
        return fixture;
    }

    afterEach(() => http?.verify());

    it('parses the contract example with exactly its properties', () => {
        const parsed = parseOnboardingReport(journey.report);
        expect(Object.keys(parsed).sort()).toEqual(Object.keys(journey.report).sort());
        expect(parsed.minimumGroup).toBe(journey.constants.report.minimumGroup);
        expect(parsed.groups.map(group => group.variant)).toEqual(['control', 'tour']);
        expect(() => parseOnboardingReport({ ...journey.report, groups: [{ variant: 'tour', accounts: -1, activated: 0, returned: 0 }] })).toThrow();
    });

    it('asks for the half-open UTC window and shows shares with the small-sample warning', async () => {
        const fixture = await render();
        const request = http.expectOne(candidate => candidate.url === '/api/admin/onboarding/experiment');
        expect(request.request.params.get('from')).toBe('2026-09-01');
        expect(request.request.params.get('to')).toBe('2026-10-01');
        request.flush(journey.report);
        await fixture.whenStable();
        fixture.detectChanges();
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('Мало данных');
        expect(text).toContain('Без автопредложения (holdout)');
        expect(text).toMatch(/33,3\s?%/u);
        expect(text).not.toContain('accountId');
    });

    it('shows no warning for large groups and an empty row without data', async () => {
        const fixture = await render();
        const report = clone(journey.report) as { smallSample: boolean; groups: unknown[] };
        report.smallSample = false;
        report.groups = [];
        http.expectOne(candidate => candidate.url === '/api/admin/onboarding/experiment').flush(report);
        await fixture.whenStable();
        fixture.detectChanges();
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).not.toContain('Мало данных');
        expect(text).toContain('новых аккаунтов в эксперименте нет');
    });

    it('refuses an invalid period without a request and reports a failed load', async () => {
        const fixture = await render({ from: '2026-10-10', through: '2026-10-01' });
        await fixture.whenStable();
        fixture.detectChanges();
        expect((fixture.nativeElement as HTMLElement).querySelector('[role="alert"]')?.textContent).toContain('Не удалось получить отчёт');
        query.next(convertToParamMap({ from: '2026-09-01', through: '2026-09-02' }));
        http.expectOne(candidate => candidate.url === '/api/admin/onboarding/experiment').flush({}, { status: 500, statusText: 'Error' });
        await fixture.whenStable();
        fixture.detectChanges();
        expect((fixture.nativeElement as HTMLElement).querySelector('[role="alert"]')).not.toBeNull();
    });
});
