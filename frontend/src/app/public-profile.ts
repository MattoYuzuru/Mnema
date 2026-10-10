import { AuthFailure, boundedText, objectValue } from './auth-protocol';
import { isProfileBio, normalizeProfileBio } from './profile-bio';

/**
 * Public profile wire shapes (Identity, `contracts/identity/public-profile.json`). Parsing is strict: a field that is
 * missing, mistyped or unknown is a protocol failure, never a silently ignored extra.
 */

/** The date of the approved consent text; the server rejects a different version with 409 `consent_text_outdated`. */
export const PUBLIC_PROFILE_TEXT_VERSION = '2026-10-10';
/** Most ids one batch request may carry. */
export const AUTHOR_CARD_BATCH_MAX = 50;

export interface PublicProfileConsent {
    enabled: boolean;
    showDisplayName: boolean;
    showAvatar: boolean;
    showBio: boolean;
    textVersion: string;
    /** Consent is on and the account has a login: a deck may be published. */
    publishReady: boolean;
    updatedAt: string | null;
}

export type PublicProfileConsentUpdate = Pick<PublicProfileConsent, 'enabled' | 'showDisplayName' | 'showAvatar' | 'showBio' | 'textVersion'>;

/** A public author card: exists only for an account with consent and a login. */
export interface AuthorCard {
    accountId: string;
    profileUsername: string;
    displayName: string | null;
    bio: string | null;
    avatarPresent: boolean;
}

export const ACCOUNT_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u;
export const PROFILE_USERNAME = /^[A-Za-z0-9_.-]{3,50}$/u;
const INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/u;
const TEXT_VERSION = /^\d{4}-\d{2}-\d{2}$/u;

function exactFields(value: unknown, names: readonly string[]): Record<string, unknown> {
    const fields = objectValue(value);
    const present = Object.keys(fields);
    if (present.length !== names.length || !names.every(name => Object.hasOwn(fields, name))) throw new AuthFailure('protocol');
    return fields;
}

export function parsePublicProfileConsent(value: unknown): PublicProfileConsent {
    const f = exactFields(value, ['enabled', 'showDisplayName', 'showAvatar', 'showBio', 'textVersion', 'publishReady', 'updatedAt']);
    if (typeof f['enabled'] !== 'boolean' || typeof f['showDisplayName'] !== 'boolean' || typeof f['showAvatar'] !== 'boolean' ||
        typeof f['showBio'] !== 'boolean' || typeof f['publishReady'] !== 'boolean' ||
        typeof f['textVersion'] !== 'string' || !TEXT_VERSION.test(f['textVersion']) ||
        !(f['updatedAt'] === null || (typeof f['updatedAt'] === 'string' && INSTANT.test(f['updatedAt']) && !Number.isNaN(Date.parse(f['updatedAt'])))) ||
        // Withdrawal clears every field flag, and publication needs the consent itself.
        (!f['enabled'] && (f['showDisplayName'] || f['showAvatar'] || f['showBio'] || f['publishReady']))) throw new AuthFailure('protocol');
    return { enabled: f['enabled'], showDisplayName: f['showDisplayName'], showAvatar: f['showAvatar'], showBio: f['showBio'],
        textVersion: f['textVersion'], publishReady: f['publishReady'], updatedAt: f['updatedAt'] };
}

export function parseAuthorCard(value: unknown): AuthorCard {
    const f = exactFields(value, ['accountId', 'profileUsername', 'displayName', 'bio', 'avatarPresent']);
    if (typeof f['accountId'] !== 'string' || !ACCOUNT_ID.test(f['accountId']) ||
        typeof f['profileUsername'] !== 'string' || !PROFILE_USERNAME.test(f['profileUsername']) ||
        !(f['displayName'] === null || boundedText(f['displayName'], 200)) ||
        !(f['bio'] === null || isProfileBio(f['bio'])) || typeof f['avatarPresent'] !== 'boolean') throw new AuthFailure('protocol');
    return { accountId: f['accountId'], profileUsername: f['profileUsername'], displayName: f['displayName'],
        bio: f['bio'] === null ? null : normalizeProfileBio(f['bio']), avatarPresent: f['avatarPresent'] };
}

/** `{"profiles":[card,…]}`: at most `ids` cards, each requested at most once (non-public ids are simply absent). */
export function parseAuthorCardBatch(value: unknown, requested: readonly string[]): AuthorCard[] {
    const profiles = exactFields(value, ['profiles'])['profiles'];
    if (!Array.isArray(profiles) || profiles.length > requested.length) throw new AuthFailure('protocol');
    const cards = profiles.map(parseAuthorCard);
    const seen = new Set<string>();
    for (const card of cards) {
        if (!requested.includes(card.accountId) || seen.has(card.accountId)) throw new AuthFailure('protocol');
        seen.add(card.accountId);
    }
    return cards;
}
