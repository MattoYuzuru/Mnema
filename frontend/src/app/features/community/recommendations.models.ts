import { CommunityDeckPage, CommunityTopic, parseCommunityDeckPage } from './community.models';
import { PublicDeckFailure } from '../public-deck/public-deck.models';

export interface InterestProfile {
    readonly rowVersion: string;
    readonly onboardingComplete: boolean;
    readonly selectedTopics: readonly CommunityTopic[];
    readonly inferredTopics: readonly CommunityTopic[];
    readonly availableTopics: readonly CommunityTopic[];
    readonly forYouEnabled: boolean;
    readonly eventsEnabled: boolean;
}
export interface RecommendationPage extends CommunityDeckPage {
    readonly rankVersion: 'recs-v1';
    readonly explanationTopic: CommunityTopic | null;
}
function fail(): never { throw new PublicDeckFailure('unavailable'); }
function exact(value: unknown, keys: readonly string[]): Record<string, unknown> {
    if (value === null || typeof value !== 'object' || Array.isArray(value)) return fail();
    const fields = value as Record<string, unknown>;
    if (Object.keys(fields).length !== keys.length || keys.some(key => !Object.hasOwn(fields, key))) return fail();
    return fields;
}
function topic(value: unknown): CommunityTopic {
    const f = exact(value, ['id', 'name', 'parentId']);
    const id = (v: unknown): v is string => typeof v === 'string' && /^[\p{L}\p{N}][\p{L}\p{N}_.:-]{0,63}$/u.test(v);
    if (!id(f['id']) || typeof f['name'] !== 'string' || f['name'].length === 0 || Array.from(f['name']).length > 100
        || (f['parentId'] !== null && !id(f['parentId']))) return fail();
    return { id: f['id'], name: f['name'], parentId: f['parentId'] as string | null };
}
function topics(value: unknown, max: number): readonly CommunityTopic[] {
    if (!Array.isArray(value) || value.length > max) return fail();
    const result = value.map(topic);
    if (new Set(result.map(item => item.id)).size !== result.length) return fail();
    return result;
}
export function parseInterestProfile(value: unknown): InterestProfile {
    const f = exact(value, ['rowVersion', 'onboardingComplete', 'selectedTopics', 'inferredTopics', 'availableTopics', 'forYouEnabled', 'eventsEnabled']);
    if (typeof f['rowVersion'] !== 'string' || !/^(?:0|[1-9][0-9]{0,18})$/u.test(f['rowVersion'])
        || typeof f['onboardingComplete'] !== 'boolean' || typeof f['forYouEnabled'] !== 'boolean' || typeof f['eventsEnabled'] !== 'boolean') return fail();
    return { rowVersion: f['rowVersion'], onboardingComplete: f['onboardingComplete'], selectedTopics: topics(f['selectedTopics'], 20),
        inferredTopics: topics(f['inferredTopics'], 20), availableTopics: topics(f['availableTopics'], 1000),
        forYouEnabled: f['forYouEnabled'], eventsEnabled: f['eventsEnabled'] };
}
export function parseRecommendationPage(value: unknown, limit = 48): RecommendationPage {
    const f = exact(value, ['rankVersion', 'explanationTopic', 'decks', 'total', 'totalCapped', 'nextCursor']);
    if (f['rankVersion'] !== 'recs-v1') return fail();
    const page = parseCommunityDeckPage({ decks: f['decks'], total: f['total'], totalCapped: f['totalCapped'], nextCursor: f['nextCursor'] }, limit);
    if (new Set(page.decks.map(card => card.code)).size !== page.decks.length) return fail();
    return { ...page, rankVersion: 'recs-v1', explanationTopic: f['explanationTopic'] === null ? null : topic(f['explanationTopic']) };
}
