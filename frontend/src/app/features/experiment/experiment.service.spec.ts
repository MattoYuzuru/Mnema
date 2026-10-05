import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { CONTROL, ExperimentService } from './experiment.service';

describe('ExperimentService', () => {
    let service: ExperimentService;
    let http: HttpTestingController;

    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
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
});
