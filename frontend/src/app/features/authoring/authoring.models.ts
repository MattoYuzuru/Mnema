import { NativeDocument } from '../../content/native-document';
import { isCanonicalCommandId, isCanonicalEntityId, isCanonicalVersion } from '../own-decks/own-deck.models';

export const ITEM_PAGE_SIZE = 20;
export const AUTHORING_PAGE_SIZE = 20;

export interface ItemRecordSummary {
    readonly memberKey: string;
    readonly itemRevisionId: string;
    readonly itemVersion: string;
    readonly formatVersion: 1;
    readonly createdAt: string;
    readonly updatedAt: string;
}

export interface ItemSummary extends ItemRecordSummary { readonly ordinal: number; }

export interface ItemPage {
    readonly deckId: string;
    readonly deckRevisionId: string;
    readonly deckVersion: string;
    readonly total: number;
    readonly items: readonly ItemSummary[];
    readonly nextCursor: string | null;
}

export interface ItemDetail extends ItemRecordSummary {
    readonly ordinal: number | null;
    readonly deckId: string;
    readonly deckRevisionId: string;
    readonly deckVersion: string;
    readonly document: NativeDocument;
}

export interface ItemChangeResult {
    readonly operation: 'create' | 'save' | 'delete' | 'reorder';
    readonly memberKey: string;
    readonly itemRevisionId: string | null;
    readonly itemVersion: string;
    readonly ordinal: number | null;
}

export interface ItemAcknowledgement {
    readonly commandId: string;
    readonly deckId: string;
    readonly deckRevisionId: string;
    readonly deckVersion: string;
    readonly memberCount: number;
    readonly changes: readonly ItemChangeResult[];
}

export interface ItemWriteResult {
    readonly acknowledgement: ItemAcknowledgement;
    readonly replayed: boolean;
}

export interface DraftSummary {
    readonly draftId: string;
    readonly deckId: string;
    readonly memberKey: string | null;
    readonly baseRevisionId: string | null;
    readonly rowVersion: string;
    readonly contentBytes: number;
    readonly createdAt: string;
    readonly acknowledgedAt: string;
    readonly expiresAt: string;
}

export interface DraftDetail extends DraftSummary { readonly document: NativeDocument; }
export interface DraftPage { readonly items: readonly DraftSummary[]; readonly nextCursor: string | null; }
export interface DraftAcknowledgement { readonly commandId: string; readonly draft: DraftDetail; }
export interface DraftWriteResult { readonly acknowledgement: DraftAcknowledgement; readonly replayed: boolean; }

export interface CaptureConversion {
    readonly commandId: string;
    readonly memberKey: string;
    readonly itemRevisionId: string;
    readonly convertedAt: string;
}

export interface CaptureNote {
    readonly noteId: string;
    readonly deckId: string;
    readonly rowVersion: string;
    readonly source: string;
    readonly text: string;
    readonly contentBytes: number;
    readonly archived: boolean;
    readonly createdAt: string;
    readonly updatedAt: string;
    readonly conversion: CaptureConversion | null;
}

export interface CapturePage { readonly items: readonly CaptureNote[]; readonly nextCursor: string | null; }

export interface CaptureAcknowledgement { readonly commandId: string; readonly capture: CaptureNote; }
export interface CaptureWriteResult { readonly acknowledgement: CaptureAcknowledgement; readonly replayed: boolean; }
export interface CaptureConversionAcknowledgement {
    readonly commandId: string;
    readonly noteId: string;
    readonly noteVersion: string;
    readonly sourcePreserved: true;
    readonly publication: ItemAcknowledgement;
}

export class AuthoringProtocolError extends Error {
    constructor(message: string) {
        super(message);
        this.name = 'AuthoringProtocolError';
    }
}

export function requireObject(value: unknown, exactKeys: readonly string[]): Record<string, unknown> {
    if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new AuthoringProtocolError('Expected object.');
    const object = value as Record<string, unknown>;
    const keys = Object.keys(object);
    if (keys.length !== exactKeys.length || keys.some(key => !exactKeys.includes(key))) {
        throw new AuthoringProtocolError('Unexpected response shape.');
    }
    return object;
}

export function requireEntity(value: unknown): string {
    if (typeof value !== 'string' || !isCanonicalEntityId(value)) throw new AuthoringProtocolError('Invalid entity ID.');
    return value.toLowerCase();
}

export function requireCommand(value: unknown): string {
    if (typeof value !== 'string' || !isCanonicalCommandId(value)) throw new AuthoringProtocolError('Invalid command ID.');
    return value.toLowerCase();
}

export function requireVersion(value: unknown): string {
    if (typeof value !== 'string' || !isCanonicalVersion(value)) throw new AuthoringProtocolError('Invalid version.');
    return value;
}

export function requireCount(value: unknown, maximum = Number.MAX_SAFE_INTEGER): number {
    if (!Number.isSafeInteger(value) || (value as number) < 0 || (value as number) > maximum) {
        throw new AuthoringProtocolError('Invalid count.');
    }
    return value as number;
}

export function requireInstant(value: unknown): string {
    if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value)
        || !Number.isFinite(Date.parse(value))) throw new AuthoringProtocolError('Invalid timestamp.');
    return value;
}

export function requireCursor(value: unknown): string | null {
    if (value === null) return null;
    if (typeof value !== 'string' || value.length === 0 || value.length > 4096) throw new AuthoringProtocolError('Invalid cursor.');
    return value;
}

export function newCommandId(): string {
    const value = crypto.randomUUID();
    if (!isCanonicalCommandId(value)) throw new AuthoringProtocolError('Browser command ID is invalid.');
    return value;
}
