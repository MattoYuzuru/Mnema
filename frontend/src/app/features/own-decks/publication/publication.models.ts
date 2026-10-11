import { MAX_TAGS, TAG_MAX_CODE_POINTS } from '../../../shared/tags';
import {
    AuthoringProtocolError,
    requireCommand,
    requireCount,
    requireEntity,
    requireInstant,
    requireObject,
    requireVersion
} from '../../authoring/authoring.models';

/** contracts/decks/publication.json: the one wire contract of the owner's publication of a deck (Share/8). */
export const PUBLICATION_LEVELS = ['PRIVATE', 'INVITE', 'LINK', 'PUBLIC'] as const;
export type PublicationLevel = (typeof PUBLICATION_LEVELS)[number];

export const CONTENT_LEVELS = ['A1', 'A2', 'B1', 'B2', 'C1', 'C2', 'BEGINNER', 'INTERMEDIATE', 'ADVANCED'] as const;
export type ContentLevel = (typeof CONTENT_LEVELS)[number];

export const CHECKLIST_KEYS = ['description', 'topic', 'language', 'publicProfile', 'blockedMedia'] as const;
export type ChecklistKey = (typeof CHECKLIST_KEYS)[number];

export const RELEASE_NOTE_MAX_CODE_POINTS = 500;
const LANGUAGE_PATTERN = /^[a-z]{2,3}$/;
const LINK_PATTERN = /^\/d\/[A-Za-z0-9_-]{4,64}(?:\/[A-Za-z0-9._~%-]{1,160})?$/;
const CODE_PATTERN = /^[A-Za-z0-9_-]{4,64}$/;
const MAX_BLOCKED = 50;
const MAX_UNPUBLISHED = 100_000;

export interface PublicationMetadata {
    readonly topicId: string | null;
    /** The language of the deck's text. */
    readonly contentLanguage: string | null;
    /** The language being learned; the checklist passes it through unchanged. */
    readonly targetLanguage: string | null;
    readonly level: ContentLevel | null;
    readonly tags: readonly string[];
}

export interface CatalogThreshold {
    readonly met: boolean;
    readonly materials: number;
    readonly exercises: number;
    readonly needMaterials: number;
    readonly needExercises: number;
}

/** A material (`memberKey`) or an exercise (`exerciseId`) of the deck head that uses non-commercial stock media. */
export interface BlockedMedia {
    readonly memberKey: string | null;
    readonly exerciseId: string | null;
    readonly reason: 'NC_LICENSE';
}

export interface PublicationChecklist {
    readonly description: boolean;
    readonly topic: boolean;
    readonly language: boolean;
    readonly publicProfile: boolean;
    readonly catalogThreshold: CatalogThreshold;
    readonly blockedMedia: readonly BlockedMedia[];
}

export interface PublicationState {
    readonly deckId: string;
    readonly visibility: PublicationLevel;
    readonly publicCode: string | null;
    readonly link: string | null;
    readonly publishedRevisionId: string | null;
    readonly publishedAt: string | null;
    readonly headRevisionId: string;
    /** `null` while the deck was never published. */
    readonly unpublishedChanges: number | null;
    readonly metadata: PublicationMetadata;
    readonly suggested: { readonly contentLanguage: string | null; readonly topicIds: readonly string[] };
    readonly releaseNote: string | null;
    readonly requestsEnabled: boolean;
    readonly checklist: PublicationChecklist;
    readonly rowVersion: string;
}

export interface PublishRequest {
    readonly expectedHeadRevisionId: string;
    readonly releaseNote: string | null;
}

/** The state after the command is exactly this body (not a merge). */
export interface PublicationCommand {
    readonly commandId: string;
    readonly visibility: PublicationLevel;
    readonly metadata: PublicationMetadata;
    readonly requestsEnabled: boolean;
    readonly publish: PublishRequest | null;
}

export interface PublicationAcknowledgement {
    readonly commandId: string;
    readonly changed: boolean;
    readonly publication: PublicationState;
}

export interface PublicationWriteResult {
    readonly acknowledgement: PublicationAcknowledgement;
    readonly etag: string | null;
    /** An exact retry: the embedded state is historical, so the caller re-reads it. */
    readonly replayed: boolean;
}

export interface Topic {
    readonly topicId: string;
    readonly nameRu: string;
    readonly nameEn: string;
    readonly ordinal: number;
    readonly children: readonly Topic[];
}

/** What a failed publication request carries: the status, the stable problem `code` and, for a 409, the checklist keys that failed. */
export interface PublicationFailure {
    readonly status: number;
    readonly code: string | null;
    readonly failed: readonly ChecklistKey[];
}

export function parsePublicationState(value: unknown): PublicationState {
    const object = requireObject(value, [
        'deckId', 'visibility', 'publicCode', 'link', 'publishedRevisionId', 'publishedAt', 'headRevisionId', 'unpublishedChanges',
        'metadata', 'suggested', 'releaseNote', 'requestsEnabled', 'checklist', 'rowVersion'
    ]);
    const visibility = oneOf(object['visibility'], PUBLICATION_LEVELS);
    const publicCode = nullable(object['publicCode'], code => {
        if (typeof code !== 'string' || !CODE_PATTERN.test(code)) throw new AuthoringProtocolError('Invalid public code.');
        return code;
    });
    const link = nullable(object['link'], text => {
        if (typeof text !== 'string' || !LINK_PATTERN.test(text)) throw new AuthoringProtocolError('Invalid public link.');
        return text;
    });
    if ((visibility === 'PRIVATE') !== (publicCode === null) || (publicCode === null) !== (link === null)) {
        throw new AuthoringProtocolError('Level, public code and link disagree.');
    }
    if (publicCode !== null && !link!.startsWith(`/d/${publicCode}`)) throw new AuthoringProtocolError('Link does not carry the code.');
    const publishedRevisionId = nullable(object['publishedRevisionId'], requireEntity);
    const publishedAt = nullable(object['publishedAt'], requireInstant);
    if ((publishedRevisionId === null) !== (publishedAt === null)) throw new AuthoringProtocolError('Published revision and time disagree.');
    const unpublishedChanges = nullable(object['unpublishedChanges'], count => requireCount(count, MAX_UNPUBLISHED));
    if ((unpublishedChanges === null) !== (publishedRevisionId === null)) throw new AuthoringProtocolError('Unpublished changes need a publication.');
    if (visibility !== 'PRIVATE' && publishedRevisionId === null) throw new AuthoringProtocolError('A shared deck has a published revision.');
    const suggested = requireObject(object['suggested'], ['contentLanguage', 'topicIds']);
    const topicIds = suggested['topicIds'];
    if (!Array.isArray(topicIds) || topicIds.length > 3 || topicIds.some(id => typeof id !== 'string' || id.length === 0 || id.length > 64)) {
        throw new AuthoringProtocolError('Invalid suggested topics.');
    }
    const releaseNote = nullable(object['releaseNote'], note => {
        if (typeof note !== 'string' || Array.from(note).length > RELEASE_NOTE_MAX_CODE_POINTS) throw new AuthoringProtocolError('Invalid release note.');
        return note;
    });
    if (typeof object['requestsEnabled'] !== 'boolean') throw new AuthoringProtocolError('Invalid requests switch.');
    return {
        deckId: requireEntity(object['deckId']),
        visibility, publicCode, link, publishedRevisionId, publishedAt,
        headRevisionId: requireEntity(object['headRevisionId']),
        unpublishedChanges,
        metadata: parseMetadata(object['metadata']),
        suggested: { contentLanguage: nullable(suggested['contentLanguage'], requireLanguage), topicIds: topicIds as string[] },
        releaseNote,
        requestsEnabled: object['requestsEnabled'],
        checklist: parseChecklist(object['checklist']),
        rowVersion: requireVersion(object['rowVersion'])
    };
}

export function parsePublicationAcknowledgement(value: unknown): PublicationAcknowledgement {
    const object = requireObject(value, ['commandId', 'changed', 'publication']);
    if (typeof object['changed'] !== 'boolean') throw new AuthoringProtocolError('Invalid changed flag.');
    return {
        commandId: requireCommand(object['commandId']),
        changed: object['changed'],
        publication: parsePublicationState(object['publication'])
    };
}

export function parseTopics(value: unknown): readonly Topic[] {
    const object = requireObject(value, ['topics']);
    return parseTopicLevel(object['topics'], 0);
}

/** The `PublicationFailure` of an HTTP error, or `null` when the error is not an HTTP answer (a lost connection is status 0). */
export function publicationFailureOf(error: unknown): PublicationFailure | null {
    if (error === null || typeof error !== 'object' || !('status' in error) || typeof error.status !== 'number') return null;
    const body = 'error' in error ? error.error : null;
    const code = body !== null && typeof body === 'object' && 'code' in body && typeof body.code === 'string' ? body.code : null;
    const listed = body !== null && typeof body === 'object' && 'failed' in body && Array.isArray(body.failed) ? body.failed : [];
    const failed = CHECKLIST_KEYS.filter(key => listed.includes(key));
    return { status: error.status, code, failed };
}

/** Number of code points of a release note after trimming: the unit of the 500 limit. */
export function releaseNoteLength(note: string): number {
    return Array.from(note.trim()).length;
}

/** The note as it goes to the server: blank is `null`. */
export function releaseNoteValue(note: string): string | null {
    const trimmed = note.trim();
    return trimmed.length === 0 ? null : trimmed;
}

/** The checklist keys the stored state does not satisfy. `topic` and `language` also count when the form value is empty. */
export function failedChecklist(checklist: PublicationChecklist, overrides: { readonly topic?: boolean; readonly language?: boolean } = {}): readonly ChecklistKey[] {
    const failed: ChecklistKey[] = [];
    if (!checklist.description) failed.push('description');
    if (!(overrides.topic ?? checklist.topic)) failed.push('topic');
    if (!(overrides.language ?? checklist.language)) failed.push('language');
    if (!checklist.publicProfile) failed.push('publicProfile');
    if (checklist.blockedMedia.length > 0) failed.push('blockedMedia');
    return failed;
}

export function newPublicationCommandId(): string {
    const value = crypto.randomUUID();
    return requireCommand(value);
}

/** Leaves of the directory: a second-level topic, or a top-level topic without children («Другое»). */
export function topicLeaves(topics: readonly Topic[]): readonly Topic[] {
    return topics.flatMap(topic => topic.children.length === 0 ? [topic] : topic.children);
}

function parseMetadata(value: unknown): PublicationMetadata {
    const object = requireObject(value, ['topicId', 'contentLanguage', 'targetLanguage', 'level', 'tags']);
    const tags = object['tags'];
    if (!Array.isArray(tags) || tags.length > MAX_TAGS || new Set(tags).size !== tags.length
        || tags.some(tag => typeof tag !== 'string' || tag.length === 0 || Array.from(tag).length > TAG_MAX_CODE_POINTS)) {
        throw new AuthoringProtocolError('Invalid tags.');
    }
    return {
        topicId: nullable(object['topicId'], id => {
            if (typeof id !== 'string' || id.length === 0 || id.length > 64) throw new AuthoringProtocolError('Invalid topic.');
            return id;
        }),
        contentLanguage: nullable(object['contentLanguage'], requireLanguage),
        targetLanguage: nullable(object['targetLanguage'], requireLanguage),
        level: nullable(object['level'], level => oneOf(level, CONTENT_LEVELS)),
        tags: tags as string[]
    };
}

function parseChecklist(value: unknown): PublicationChecklist {
    const object = requireObject(value, ['description', 'topic', 'language', 'publicProfile', 'catalogThreshold', 'blockedMedia']);
    for (const key of ['description', 'topic', 'language', 'publicProfile'] as const) {
        if (typeof object[key] !== 'boolean') throw new AuthoringProtocolError(`Invalid checklist item ${key}.`);
    }
    const threshold = requireObject(object['catalogThreshold'], ['met', 'materials', 'exercises', 'needMaterials', 'needExercises']);
    if (typeof threshold['met'] !== 'boolean') throw new AuthoringProtocolError('Invalid catalog threshold.');
    const blocked = object['blockedMedia'];
    if (!Array.isArray(blocked) || blocked.length > MAX_BLOCKED) throw new AuthoringProtocolError('Invalid blocked media.');
    return {
        description: object['description'] as boolean,
        topic: object['topic'] as boolean,
        language: object['language'] as boolean,
        publicProfile: object['publicProfile'] as boolean,
        catalogThreshold: {
            met: threshold['met'],
            materials: requireCount(threshold['materials'], MAX_UNPUBLISHED),
            exercises: requireCount(threshold['exercises'], MAX_UNPUBLISHED),
            needMaterials: requireCount(threshold['needMaterials'], MAX_UNPUBLISHED),
            needExercises: requireCount(threshold['needExercises'], MAX_UNPUBLISHED)
        },
        blockedMedia: blocked.map(parseBlocked)
    };
}

function parseBlocked(value: unknown): BlockedMedia {
    if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new AuthoringProtocolError('Invalid blocked media entry.');
    const subject = 'memberKey' in value ? 'memberKey' : 'exerciseId';
    const object = requireObject(value, [subject, 'reason']);
    if (object['reason'] !== 'NC_LICENSE') throw new AuthoringProtocolError('Unknown blocked media reason.');
    const id = requireEntity(object[subject]);
    return { memberKey: subject === 'memberKey' ? id : null, exerciseId: subject === 'exerciseId' ? id : null, reason: 'NC_LICENSE' };
}

function parseTopicLevel(value: unknown, depth: number): readonly Topic[] {
    if (!Array.isArray(value) || value.length > 200) throw new AuthoringProtocolError('Invalid topics.');
    const topics = value.map(entry => {
        const object = requireObject(entry, depth === 0 ? ['topicId', 'nameRu', 'nameEn', 'ordinal', 'children'] : ['topicId', 'nameRu', 'nameEn', 'ordinal']);
        for (const key of ['topicId', 'nameRu', 'nameEn'] as const) {
            if (typeof object[key] !== 'string' || (object[key] as string).length === 0 || (object[key] as string).length > 200) {
                throw new AuthoringProtocolError(`Invalid topic ${key}.`);
            }
        }
        return {
            topicId: object['topicId'] as string, nameRu: object['nameRu'] as string, nameEn: object['nameEn'] as string,
            ordinal: requireCount(object['ordinal'], 1_000_000),
            children: depth === 0 ? parseTopicLevel(object['children'], 1) : []
        };
    });
    if (new Set(topics.map(topic => topic.topicId)).size !== topics.length) throw new AuthoringProtocolError('Duplicate topic.');
    return topics;
}

function oneOf<T extends string>(value: unknown, allowed: readonly T[]): T {
    if (typeof value !== 'string' || !(allowed as readonly string[]).includes(value)) throw new AuthoringProtocolError('Unexpected value.');
    return value as T;
}

function nullable<T>(value: unknown, parse: (value: unknown) => T): T | null {
    return value === null ? null : parse(value);
}

function requireLanguage(value: unknown): string {
    if (typeof value !== 'string' || !LANGUAGE_PATTERN.test(value)) throw new AuthoringProtocolError('Invalid language.');
    return value;
}
