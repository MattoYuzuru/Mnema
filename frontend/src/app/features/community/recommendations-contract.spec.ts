import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import contract from '../../../../../contracts/community/recommendations.json';
import { NO_FILTERS } from '../../shared/catalog-filters';
import { PublicDeckFailure } from '../public-deck/public-deck.models';
import { displayCard } from './community-view';
import { RecommendationsApiService } from './recommendations-api.service';
import { parseInterestProfile, parseRecommendationPage } from './recommendations.models';

describe('Private recommendations wire contract', () => {
    let api: RecommendationsApiService;
    let http: HttpTestingController;
    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
        api = TestBed.inject(RecommendationsApiService); http = TestBed.inject(HttpTestingController);
    });
    afterEach(() => http.verify());
    it('executes the shared profile, selection and reset fixtures with exact private paths and If-Match', async () => {
        const reading = firstValueFrom(api.profile());
        http.expectOne('/api/community/interests').flush(contract.profile);
        expect(await reading).toEqual(contract.profile);
        for (const [reset, example, version] of [[false, contract.select, '0'], [true, contract.reset, '1']] as const) {
            const changing = firstValueFrom(api.change(example.request, version, reset));
            const request = http.expectOne(`/api/community/interests${reset ? ':reset' : ''}`);
            expect(request.request.method).toBe(reset ? 'POST' : 'PUT');
            expect(request.request.body).toEqual(example.request);
            expect(request.request.headers.get('If-Match')).toBe(`"${version}"`);
            request.flush(example.response);
            expect(await changing).toEqual(example.response);
        }
    });
    it('keeps recommendation metadata separate from public pages and sends filters, text, cursor and limits', async () => {
        const reading = firstValueFrom(api.recommendations({ q: '  испанский  ', filters: { ...NO_FILTERS, topic: 'spanish', media: ['audio'], sort: 'for-you' } }, 'opaque', 12));
        const request = http.expectOne(r => r.url === '/api/community/recommendations');
        expect(request.request.params.keys()).toEqual(['q', 'topic', 'media', 'sort', 'limit', 'cursor']);
        expect(request.request.params.get('q')).toBe('испанский');
        expect(request.request.params.get('sort')).toBe('for-you');
        request.flush(contract.recommendations);
        expect(await reading).toEqual(contract.recommendations);
        expect(parseRecommendationPage(contract.emptyRecommendations).decks).toEqual([]);
    });
    it('refuses unknown fields, weights, rank values, duplicate topics and malformed versions', () => {
        for (const patch of [{ weight: 8 }, { rowVersion: 0 }, { rowVersion: '-1' }, { eventsEnabled: 'true' }, { selectedTopics: Array(21).fill(contract.profile.availableTopics[0]) },
            { inferredTopics: [contract.profile.availableTopics[0], contract.profile.availableTopics[0]] }, { availableTopics: [{ ...contract.profile.availableTopics[0], weight: 8 }] }]) {
            expect(() => parseInterestProfile({ ...contract.profile, ...patch })).toThrow(PublicDeckFailure);
        }
        for (const patch of [{ score: 1 }, { rankVersion: 'unknown' }, { explanationTopic: { ...contract.recommendations.explanationTopic, weight: 8 } },
            { decks: [{ ...contract.recommendations.decks[0], score: 8 }] }, { decks: [contract.recommendations.decks[0], contract.recommendations.decks[0]] }]) {
            expect(() => parseRecommendationPage({ ...contract.recommendations, ...patch })).toThrow(PublicDeckFailure);
        }
        expect(() => parseInterestProfile(null)).toThrow(PublicDeckFailure);
        expect(() => parseRecommendationPage({ ...contract.recommendations, decks: Array(13).fill(contract.recommendations.decks[0]) }, 12)).toThrow(PublicDeckFailure);
    });
    it('guards small audiences even when the server sends an exact small number', () => {
        const page = parseRecommendationPage({ ...contract.recommendations, decks: [{ ...contract.recommendations.decks[0], addedCount: 9, learningNowCount: 2 }] });
        const card = displayCard(page.decks[0], { username: null, avatarSrc: null }, 'https://mnema.app');
        expect([card.addedCount, card.learningNowCount]).toEqual([null, null]);
        expect(Object.keys(page)).not.toContain('score');
        expect(Object.keys(parseInterestProfile(contract.select.response))).not.toContain('weights');
    });
    it('sends only the bounded metadata contract for client events and exposures', () => {
        const context = { code: contract.event.code, position: contract.event.position };
        api.event('DETAIL_VIEW', { ...context, surface: 'for-you', rankVersion: 'recs-v1' }).subscribe();
        const event = http.expectOne('/api/community/events');
        expect(Object.keys(event.request.body).sort()).toEqual(Object.keys(contract.event).sort());
        expect(event.request.body.commandId).toMatch(/^[a-f0-9-]{36}$/u); event.flush(null);
        api.exposures('for-you', 'recs-v1', [{ code: context.code, position: context.position }]).subscribe();
        const exposure = http.expectOne('/api/community/exposures');
        expect(Object.keys(exposure.request.body).sort()).toEqual(Object.keys(contract.exposure).sort());
        expect(exposure.request.body.items).toEqual(contract.exposure.items); exposure.flush(null);
    });
});
