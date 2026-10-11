import publication from '../../../../../../contracts/decks/publication.json';
import { PublicationState, PublicationWriteResult, Topic, parsePublicationState, parseTopics } from './publication.models';

/** States of contracts/decks/publication.json, parsed by the production parser, with overrides for one spec. */
export function neverPublished(overrides: Partial<PublicationState> = {}): PublicationState {
    return { ...parsePublicationState(publication.state.never.body), ...overrides };
}

export function sharedState(overrides: Partial<PublicationState> = {}): PublicationState {
    return { ...parsePublicationState(publication.state.public.body), ...overrides };
}

export function topicDirectory(): readonly Topic[] {
    return parseTopics(publication.topics.body);
}

export function written(publicationState: PublicationState, commandId: string, replayed = false): PublicationWriteResult {
    return {
        acknowledgement: { commandId, changed: true, publication: publicationState },
        etag: replayed ? null : `"${publicationState.rowVersion}"`,
        replayed
    };
}

export const PROFILE_CHECKLIST_READY = { description: true, topic: false, language: false, publicProfile: true } as const;
