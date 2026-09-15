export const OWN_DECK_PAGE_SIZE = 20;
export const DECK_TITLE_MAX_CODE_POINTS = 200;
export const DECK_TITLE_MAX_BYTES = 800;
export const DECK_DESCRIPTION_MAX_BYTES = 4096;
export const DECK_COMMAND_MAX_BYTES = 8192;

const MAX_VERSION = 9223372036854775806n;
const COMMAND_ID_FOR_SIZE = '00000000-0000-4000-8000-000000000000';
const encoder = new TextEncoder();

export interface DeckMetadata {
    readonly title: string;
    readonly description: string;
}

export interface OwnDeck {
    readonly deckId: string;
    readonly revisionId: string;
    readonly rowVersion: string;
    readonly sequence: string;
    readonly metadata: DeckMetadata;
    readonly visibility: 'private';
    readonly createdAt: string;
    readonly updatedAt: string;
    readonly memberCount: number;
    readonly exerciseCount: number;
}

export interface OwnDeckPage {
    readonly items: readonly OwnDeck[];
    readonly nextCursor: string | null;
}

export interface DeckCommand {
    readonly commandId: string;
    readonly metadata: DeckMetadata;
}

export interface DeckAcknowledgement {
    readonly commandId: string;
    readonly deck: OwnDeck;
}

export interface DeckWriteResult {
    readonly acknowledgement: DeckAcknowledgement;
    readonly etag: string | null;
    readonly replayed: boolean;
    readonly location: string | null;
}

export interface DeckFailure {
    readonly kind: 'network' | 'http' | 'protocol';
    readonly status: number;
    readonly code: string | null;
}

export interface DeckMetadataValidation {
    readonly valid: boolean;
    readonly titleBlank: boolean;
    readonly titleCodePoints: number;
    readonly titleBytes: number;
    readonly descriptionBytes: number;
    readonly requestBytes: number;
    readonly unicodeValid: boolean;
}

export class OwnDeckProtocolError extends Error {
    constructor(message: string) {
        super(message);
        this.name = 'OwnDeckProtocolError';
    }
}

export function validateDeckMetadata(metadata: DeckMetadata): DeckMetadataValidation {
    const titleCodePoints = Array.from(metadata.title).length;
    const titleBytes = encoder.encode(metadata.title).length;
    const descriptionBytes = encoder.encode(metadata.description).length;
    const unicodeValid = isValidUnicode(metadata.title) && isValidUnicode(metadata.description);
    const requestBytes = encoder.encode(JSON.stringify({
        commandId: COMMAND_ID_FOR_SIZE,
        metadata
    })).length;
    const titleBlank = metadata.title.length === 0
        || Array.from(metadata.title).every(character => isJavaWhitespace(character.codePointAt(0)!));
    const valid = unicodeValid && !titleBlank
        && titleCodePoints <= DECK_TITLE_MAX_CODE_POINTS
        && titleBytes <= DECK_TITLE_MAX_BYTES
        && descriptionBytes <= DECK_DESCRIPTION_MAX_BYTES
        && requestBytes <= DECK_COMMAND_MAX_BYTES;
    return { valid, titleBlank, titleCodePoints, titleBytes, descriptionBytes, requestBytes, unicodeValid };
}

export function createDeckCommand(metadata: DeckMetadata): DeckCommand {
    const validation = validateDeckMetadata(metadata);
    if (!validation.valid) throw new OwnDeckProtocolError('Deck metadata is outside the shared command contract.');
    const commandId = crypto.randomUUID();
    if (!isCanonicalCommandId(commandId)) throw new OwnDeckProtocolError('Browser did not produce a canonical UUIDv4 command ID.');
    return { commandId, metadata: { ...metadata } };
}

export function isCanonicalEntityId(value: string): boolean {
    return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value)
        && value !== '00000000-0000-0000-0000-000000000000';
}

export function isCanonicalCommandId(value: string): boolean {
    return /^[0-9a-f]{8}-[0-9a-f]{4}-[47][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
}

export function isCanonicalVersion(value: string): boolean {
    if (!/^(0|[1-9][0-9]{0,18})$/.test(value)) return false;
    try {
        return BigInt(value) <= MAX_VERSION;
    } catch {
        return false;
    }
}

export function expectedEtag(rowVersion: string): string {
    if (!isCanonicalVersion(rowVersion)) throw new OwnDeckProtocolError('Cannot form an ETag from an invalid row version.');
    return `"${rowVersion}"`;
}

function isValidUnicode(value: string): boolean {
    for (let index = 0; index < value.length; index += 1) {
        const code = value.charCodeAt(index);
        if (code === 0 || (code >= 0xdc00 && code <= 0xdfff)) return false;
        if (code >= 0xd800 && code <= 0xdbff) {
            index += 1;
            if (index === value.length) return false;
            const next = value.charCodeAt(index);
            if (next < 0xdc00 || next > 0xdfff) return false;
        }
    }
    return true;
}

function isJavaWhitespace(codePoint: number): boolean {
    return (codePoint >= 0x0009 && codePoint <= 0x000d)
        || (codePoint >= 0x001c && codePoint <= 0x0020)
        || codePoint === 0x1680
        || (codePoint >= 0x2000 && codePoint <= 0x2006)
        || (codePoint >= 0x2008 && codePoint <= 0x200a)
        || codePoint === 0x2028
        || codePoint === 0x2029
        || codePoint === 0x205f
        || codePoint === 0x3000;
}
