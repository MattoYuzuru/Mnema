import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { AuthService, AuthStatus } from '../../auth.service';

import { CONTROL, ExperimentService } from './experiment.service';

describe('ExperimentService', () => {
    let service: ExperimentService;
    let http: HttpTestingController;
    const status = signal<AuthStatus>('authenticated');
    const user = signal<{ accountId: string } | null>({ accountId: 'first-account' });

    beforeEach(() => {
        status.set('authenticated');
        user.set({ accountId: 'first-account' });
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(),
            { provide: AuthService, useValue: { status, user } }] });
        service = TestBed.inject(ExperimentService);
        http = TestBed.inject(HttpTestingController);
    });
    afterEach(() => http.verify());

    it('gives the assigned variant, and control for an experiment that is not running', () => {
        expect(service.variant('plans_year_first')).toBe(CONTROL);
        service.adopt({ plans_year_first: 'plans_year_first' });
        expect(service.variant('plans_year_first')).toBe('plans_year_first');
        expect(service.variant('other')).toBe(CONTROL);
        expect(service.running('plans_year_first')).toBe(true);
        expect(service.running('other')).toBe(false);
        expect(service.running('constructor')).toBe(false);
        expect(service.variant('constructor')).toBe(CONTROL);
    });

    it('sends an exposure and a conversion once each, with the key and no variant', () => {
        service.adopt({ plans_year_first: 'control' });
        service.expose('plans_year_first');
        service.expose('plans_year_first');
        service.convert('plans_year_first');
        service.convert('plans_year_first');
        const requests = http.match('/api/experiment-events');
        expect(requests.map(request => request.request.body)).toEqual([
            { key: 'plans_year_first', event: 'EXPOSURE' }, { key: 'plans_year_first', event: 'CONVERSION' }]);
        for (const request of requests) request.flush(null, { status: 204, statusText: 'No Content' });
    });

    it('sends nothing for an experiment that is not running', () => {
        service.expose('plans_year_first');
        service.convert('plans_year_first');
        http.expectNone('/api/experiment-events');
    });

    it('stays silent when sending fails and lets the same event be tried again', () => {
        service.adopt({ plans_year_first: 'control' });
        service.expose('plans_year_first');
        http.expectOne('/api/experiment-events').flush(null, { status: 500, statusText: 'Server Error' });
        service.expose('plans_year_first');
        http.expectOne('/api/experiment-events').flush(null, { status: 204, statusText: 'No Content' });
    });

    it('starts afresh after a logout: the next account in the tab gets its own exposure', () => {
        TestBed.tick();
        service.adopt({ plans_year_first: 'control' });
        service.expose('plans_year_first');
        http.expectOne('/api/experiment-events').flush(null, { status: 204, statusText: 'No Content' });

        status.set('anonymous');
        TestBed.tick();
        expect(service.running('plans_year_first')).toBe(false);
        status.set('authenticated');
        TestBed.tick();
        service.adopt({ plans_year_first: 'control' });
        service.expose('plans_year_first');

        http.expectOne('/api/experiment-events').flush(null, { status: 204, statusText: 'No Content' });
    });

    it('forgets the previous account even when the authenticated status did not change', () => {
        TestBed.tick();
        service.adopt({ plans_year_first: 'plans_year_first' });
        service.expose('plans_year_first');
        http.expectOne('/api/experiment-events').flush(null, { status: 204, statusText: 'No Content' });
        user.set({ accountId: 'second-account' });
        TestBed.tick();
        expect(service.running('plans_year_first')).toBe(false);
        service.adopt({ plans_year_first: 'control' });
        service.expose('plans_year_first');
        http.expectOne('/api/experiment-events').flush(null, { status: 204, statusText: 'No Content' });
    });

    it('does not let the old account failure clear the new account event guard', () => {
        TestBed.tick();
        service.adopt({ plans_year_first: 'control' });
        service.expose('plans_year_first');
        const oldRequest = http.expectOne('/api/experiment-events');
        user.set({ accountId: 'second-account' });
        TestBed.tick();
        service.adopt({ plans_year_first: 'control' });
        service.expose('plans_year_first');
        http.expectOne('/api/experiment-events').flush(null, { status: 204, statusText: 'No Content' });
        oldRequest.flush(null, { status: 500, statusText: 'Server Error' });
        service.expose('plans_year_first');
        http.expectNone('/api/experiment-events');
    });
});
