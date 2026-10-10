import fixture from '../../../contracts/identity/public-profile.json';
import { AuthFailure } from './auth-protocol';
import { AUTHOR_CARD_BATCH_MAX, PUBLIC_PROFILE_TEXT_VERSION, parseAuthorCard, parseAuthorCardBatch, parsePublicProfileConsent } from './public-profile';

/** contracts/identity/public-profile.json is shared with the Identity integration tests: every example must parse, extras must not. */
describe('Public profile wire contract (contracts/identity/public-profile.json)', () => {
    it('keeps the constants of the contract', () => {
        expect(PUBLIC_PROFILE_TEXT_VERSION).toBe(fixture.textVersion);
        expect(AUTHOR_CARD_BATCH_MAX).toBe(fixture.batchMaxIds);
        expect(fixture.consentUpdate.textVersion).toBe(PUBLIC_PROFILE_TEXT_VERSION);
    });

    it('parses the default and the granted consent', () => {
        expect(parsePublicProfileConsent(fixture.consentDefault)).toEqual(fixture.consentDefault);
        expect(parsePublicProfileConsent(fixture.consentGranted)).toEqual(fixture.consentGranted);
    });

    it('parses the card and the batch answer, keeping the request order', () => {
        expect(parseAuthorCard(fixture.card)).toEqual(fixture.card);
        const cards = parseAuthorCardBatch(fixture.batch.response, fixture.batch.request);
        expect(cards).toEqual(fixture.batch.response.profiles);
        expect(parseAuthorCardBatch({ profiles: [] }, fixture.batch.request)).toEqual([]);
    });

    it.each([
        ['an unknown field', { ...fixture.consentGranted, extra: true }],
        ['a missing field', { ...fixture.consentGranted, publishReady: undefined }],
        ['a mistyped flag', { ...fixture.consentGranted, showBio: 'true' }],
        ['a badly formed version', { ...fixture.consentGranted, textVersion: '10.10.2026' }],
        ['a badly formed time', { ...fixture.consentGranted, updatedAt: 'yesterday' }],
        ['a field flag after withdrawal', { ...fixture.consentDefault, showAvatar: true }],
        ['publication readiness without consent', { ...fixture.consentDefault, publishReady: true }],
        ['an array', [fixture.consentGranted]]
    ])('rejects a consent with %s', (_name, value) => {
        expect(() => parsePublicProfileConsent(value)).toThrow(AuthFailure);
    });

    it.each([
        ['an unknown field', { ...fixture.card, email: 'a@b.test' }],
        ['a missing field', { ...fixture.card, bio: undefined }],
        ['a non-UUID account id', { ...fixture.card, accountId: 'anna' }],
        ['a login outside the allowed alphabet', { ...fixture.card, profileUsername: 'an na' }],
        ['a too short login', { ...fixture.card, profileUsername: 'an' }],
        ['a control character in the name', { ...fixture.card, displayName: 'Анна\n' }],
        ['an oversized bio', { ...fixture.card, bio: 'я'.repeat(201) }],
        ['a mistyped avatar flag', { ...fixture.card, avatarPresent: 1 }]
    ])('rejects a card with %s', (_name, value) => {
        expect(() => parseAuthorCard(value)).toThrow(AuthFailure);
    });

    it('rejects a batch with an unrequested, repeated or surplus card or an extra field', () => {
        const [card] = fixture.batch.response.profiles;
        const other = { ...card, accountId: '0192f3a4-5b6c-7d8e-9f01-111111111111' };
        expect(() => parseAuthorCardBatch({ profiles: [other] }, fixture.batch.request)).toThrow(AuthFailure);
        expect(() => parseAuthorCardBatch({ profiles: [card, card] }, fixture.batch.request)).toThrow(AuthFailure);
        expect(() => parseAuthorCardBatch({ profiles: [card], total: 1 }, fixture.batch.request)).toThrow(AuthFailure);
        expect(() => parseAuthorCardBatch({ profiles: card }, fixture.batch.request)).toThrow(AuthFailure);
        expect(() => parseAuthorCardBatch({ profiles: [card, card, card] }, [card.accountId])).toThrow(AuthFailure);
    });

    it('trims a multiline bio the way the profile editor does', () => {
        expect(parseAuthorCard({ ...fixture.card, bio: '  Учусь  \n\n\n\nкаждый день ' }).bio).toBe('Учусь\n\nкаждый день');
    });

    it('describes the not-found answer', () => {
        expect(fixture.notFound).toEqual({ status: 404, code: 'profile_not_found' });
    });
});
