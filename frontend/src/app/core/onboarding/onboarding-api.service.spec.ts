import { HttpHeaders, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';

import contract from '../../../../../contracts/onboarding/journey.json';
import { OnboardingApiService } from './onboarding-api.service';
import { OnboardingProtocolError } from './onboarding.models';

const ID = '018f1d98-5c10-7abc-8abc-0123456789ab';
const privateHeaders = (extra: Record<string, string> = {}): HttpHeaders => new HttpHeaders({ 'Cache-Control': 'private, no-store', ETag: '"onb-9"', ...extra });
const view = contract.views.offered;
const sampleReady = contract.views.sampleReady;

describe('OnboardingApiService', () => {
    let api: OnboardingApiService;
    let http: HttpTestingController;

    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
        api = TestBed.inject(OnboardingApiService);
        http = TestBed.inject(HttpTestingController);
    });
    afterEach(() => http.verify());

    describe('read', () => {
        it('sends no validator the first time and echoes the held ETag as If-None-Match afterwards', async () => {
            const first = firstValueFrom(api.read());
            const initial = http.expectOne('/api/onboarding');
            expect(initial.request.headers.has('If-None-Match')).toBe(false);
            initial.flush(view, { headers: privateHeaders() });
            await first;
            const again = firstValueFrom(api.read('"onb-9"'));
            const conditional = http.expectOne('/api/onboarding');
            expect(conditional.request.headers.get('If-None-Match')).toBe('"onb-9"');
            conditional.flush(null, { status: 304, statusText: 'Not Modified' });
            await expect(again).resolves.toEqual({ kind: 'not-modified' });
        });

        it('parses the disabled view, whose sample deck still has all three free checks (0..3 is the whole range)', async () => {
            expect(contract.views.disabled.sampleDeck.freeChecksLeft).toBe(3);
            const result = firstValueFrom(api.read());
            http.expectOne('/api/onboarding').flush(contract.views.disabled, { headers: privateHeaders() });
            await expect(result).resolves.toMatchObject({ kind: 'view', view: { enabled: false, sampleDeck: { freeChecksLeft: 3 } } });
        });

        it('keeps the ETag with the view', async () => {
            const result = firstValueFrom(api.read());
            http.expectOne('/api/onboarding').flush(view, { headers: privateHeaders() });
            await expect(result).resolves.toEqual({ kind: 'view', view, etag: '"onb-9"' });
        });

        it.each([
            ['a cacheable response', { headers: new HttpHeaders({ ETag: '"x"' }) }],
            ['a response without Cache-Control: no-store', { headers: new HttpHeaders({ 'Cache-Control': 'private', ETag: '"x"' }) }],
            ['a response without an ETag', { headers: new HttpHeaders({ 'Cache-Control': 'private, no-store' }) }],
            ['a weak ETag', { headers: privateHeaders({ ETag: 'W/"x"' }) }]
        ])('refuses %s', async (_name, init) => {
            const result = firstValueFrom(api.read());
            http.expectOne('/api/onboarding').flush(view, init);
            await expect(result).rejects.toBeInstanceOf(OnboardingProtocolError);
        });

        it('refuses an envelope that does not match the contract', async () => {
            for (const body of [{ ...view, enabled: 'yes' }, { ...view, sampleDeck: { available: true } }, { ...view, sampleDeck: { ...view.sampleDeck, freeChecksLeft: 4 } },
                { ...view, sampleDeck: { ...view.sampleDeck, freeChecksLeft: -1 } }, { ...view, sampleDeck: { ...view.sampleDeck, freeChecksLeft: 1.5 } },
                { ...view, sampleDeck: { available: false, deckId: null, updateAvailable: false } }, { ...view, journey: { ...view.journey, state: 'DONE' } },
                { ...view, journey: { ...view.journey, rowVersion: '01' } }, { ...view, journey: { ...view.journey, weeklyTarget: 8 } },
                { ...view, journey: { ...view.journey, stepKey: 'sample.hub', branch: 'OWN' } }, [], null]) {
                const result = firstValueFrom(api.read());
                http.expectOne('/api/onboarding').flush(body, { headers: privateHeaders() });
                await expect(result, JSON.stringify(body)).rejects.toBeInstanceOf(OnboardingProtocolError);
            }
        });

        it('passes a failure on as the raw response', async () => {
            const result = firstValueFrom(api.read());
            http.expectOne('/api/onboarding').flush({ code: 'AUTHENTICATION_REQUIRED' }, { status: 401, statusText: 'Unauthorized' });
            await expect(result).rejects.toMatchObject({ status: 401 });
        });
    });

    describe('command', () => {
        it('puts the command and parses the view; the validator is optional on a write', async () => {
            const body = { commandId: ID, rowVersion: '0', action: 'START', stepKey: 'survey.goal' } as const;
            const result = firstValueFrom(api.command(body));
            const request = http.expectOne('/api/onboarding/journey');
            expect(request.request.method).toBe('PUT');
            expect(request.request.body).toEqual(body);
            request.flush(view, { headers: new HttpHeaders({ 'Cache-Control': 'private, no-store' }) });
            await expect(result).resolves.toEqual({ view, etag: null });
        });

        it('refuses a command the contract refuses without any request', async () => {
            const bad = [
                { commandId: 'x', rowVersion: '0', action: 'PAUSE' }, { commandId: ID, rowVersion: '-1', action: 'PAUSE' },
                { commandId: ID, rowVersion: '0', action: 'JUMP' }, { commandId: ID, rowVersion: '0', action: 'START' },
                { commandId: ID, rowVersion: '0', action: 'SKIP', stepKey: 'welcome' }, { commandId: ID, rowVersion: '0', action: 'START', branch: 'OWN', stepKey: 'survey.goal' },
                { commandId: ID, rowVersion: '1', action: 'STEP', branch: 'OWN', stepKey: 'community.add' }, { commandId: ID, rowVersion: '1', action: 'STEP', stepKey: 'tour.unknown' },
                { commandId: ID, rowVersion: '1', action: 'PAUSE', note: 'x' }
            ];
            for (const body of bad) await expect(firstValueFrom(api.command(body as never)), JSON.stringify(body)).rejects.toBeInstanceOf(OnboardingProtocolError);
        });

        it('lets a step of no branch carry no branch and a branch step carry its own', async () => {
            for (const body of [{ commandId: ID, rowVersion: '3', action: 'STEP', stepKey: 'finale.ai' }, { commandId: ID, rowVersion: '3', action: 'STEP', branch: 'OWN', stepKey: 'own.exercise' }]) {
                const result = firstValueFrom(api.command(body as never));
                http.expectOne('/api/onboarding/journey').flush(view, { headers: privateHeaders() });
                await result;
            }
        });
    });

    describe('createSampleDeck', () => {
        it('posts the command id alone and parses the view that names the copy, on 201 and on 200', async () => {
            for (const status of [201, 200]) {
                const result = firstValueFrom(api.createSampleDeck({ commandId: ID }));
                const request = http.expectOne('/api/onboarding/sample-deck');
                expect(request.request.method).toBe('POST');
                expect(request.request.body).toEqual({ commandId: ID });
                request.flush(sampleReady, { status, statusText: 'OK', headers: new HttpHeaders({ 'Cache-Control': 'private, no-store', 'Idempotency-Replayed': 'true' }) });
                await expect(result).resolves.toEqual({ view: sampleReady, etag: null });
            }
        });

        it('keeps the validator of the answer when there is one', async () => {
            const result = firstValueFrom(api.createSampleDeck({ commandId: ID }));
            http.expectOne('/api/onboarding/sample-deck').flush(sampleReady, { status: 201, statusText: 'Created', headers: privateHeaders() });
            await expect(result).resolves.toEqual({ view: sampleReady, etag: '"onb-9"' });
        });

        it('refuses an answer that names no copy, another status, a cacheable answer or a bad envelope', async () => {
            const cases: [unknown, { status: number; headers?: HttpHeaders }][] = [
                [view, { status: 201 }], [sampleReady, { status: 202 }], [sampleReady, { status: 204 }],
                [sampleReady, { status: 201, headers: new HttpHeaders({ 'Cache-Control': 'public' }) }],
                [{ ...sampleReady, extra: 1 }, { status: 201 }], [null, { status: 201 }]
            ];
            for (const [body, init] of cases) {
                const result = firstValueFrom(api.createSampleDeck({ commandId: ID }));
                http.expectOne('/api/onboarding/sample-deck').flush(body as object | null, { statusText: 'x', headers: privateHeaders(), ...init });
                await expect(result, JSON.stringify([body, init.status])).rejects.toBeInstanceOf(OnboardingProtocolError);
            }
        });

        it('refuses a defective command without any request, and passes a failure on as the raw response', async () => {
            for (const body of [{ commandId: 'x' }, { commandId: ID, rowVersion: '0' }, {}]) {
                await expect(firstValueFrom(api.createSampleDeck(body as never)), JSON.stringify(body)).rejects.toBeInstanceOf(OnboardingProtocolError);
            }
            const result = firstValueFrom(api.createSampleDeck({ commandId: ID }));
            http.expectOne('/api/onboarding/sample-deck').flush(contract.problems.sampleUnavailable, { status: 409, statusText: 'Conflict' });
            await expect(result).rejects.toMatchObject({ status: 409, error: { code: 'SAMPLE_DECK_UNAVAILABLE' } });
        });
    });

    describe('preferences', () => {
        it('puts the answers; null clears one', async () => {
            const result = firstValueFrom(api.preferences({ commandId: ID, rowVersion: '3', experience: null, weeklyTarget: 5 }));
            const request = http.expectOne('/api/onboarding/preferences');
            expect(request.request.method).toBe('PUT');
            expect(request.request.body).toEqual({ commandId: ID, rowVersion: '3', experience: null, weeklyTarget: 5 });
            request.flush(view, { headers: privateHeaders() });
            await result;
        });

        it('refuses an empty command, an unknown experience and a target out of range', async () => {
            for (const body of [{ commandId: ID, rowVersion: '0' }, { commandId: ID, rowVersion: '0', experience: 'EXPERT' }, { commandId: ID, rowVersion: '0', weeklyTarget: 0 },
                { commandId: ID, rowVersion: '0', weeklyTarget: 8 }, { commandId: ID, rowVersion: '0', weeklyTarget: 2.5 }]) {
                await expect(firstValueFrom(api.preferences(body as never)), JSON.stringify(body)).rejects.toBeInstanceOf(OnboardingProtocolError);
            }
        });
    });
});
