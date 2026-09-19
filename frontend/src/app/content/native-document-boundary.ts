import { NativeDocument } from './native-document';
import { buildNativeRenderState } from './rendering/native-render-state';

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const TYPE = /^[a-z][a-z0-9_]{0,63}$/;
const encoder = new TextEncoder();

export class NativeDocumentBoundaryError extends Error {
    constructor() { super('Invalid native document.'); this.name = 'NativeDocumentBoundaryError'; }
}

/** Clones and validates an untrusted native-v1 value without importing an editor runtime. */
export function readNativeDocument(value: unknown): NativeDocument {
    const document = structuredClone(value) as NativeDocument;
    validateEnvelope(document);
    if (buildNativeRenderState(document).status === 'invalid') throw new NativeDocumentBoundaryError();
    return document;
}

function validateEnvelope(document: NativeDocument): void {
    if (!isRecord(document) || document['formatVersion'] !== 1 || Object.keys(document).length !== 2
        || !isRecord(document['root'])) throw new NativeDocumentBoundaryError();
    let serialized: string;
    try { serialized = JSON.stringify(document); } catch { throw new NativeDocumentBoundaryError(); }
    if (encoder.encode(serialized).byteLength > 1024 * 1024) throw new NativeDocumentBoundaryError();
    validateJson(document, 1);

    const ids = new Set<string>();
    let count = 0;
    const visit = (node: unknown, depth: number): void => {
        if (!isRecord(node) || depth > 32 || ++count > 10_000) throw new NativeDocumentBoundaryError();
        const id = node['id'];
        const type = node['type'];
        const version = node['version'];
        const attrs = node['attrs'];
        const content = node['content'];
        if (typeof id !== 'string' || !UUID_V4.test(id) || ids.has(id.toLowerCase())
            || typeof type !== 'string' || !TYPE.test(type) || !Number.isInteger(version)
            || (version as number) < 1 || (version as number) > 2_147_483_647
            || !isRecord(attrs) || !Array.isArray(content)) throw new NativeDocumentBoundaryError();
        ids.add(id.toLowerCase());
        content.forEach(child => visit(child, depth + 1));
    };
    if (document.root.type !== 'doc') throw new NativeDocumentBoundaryError();
    visit(document.root, 1);
}

function validateJson(value: unknown, depth: number): void {
    if (depth > 128) throw new NativeDocumentBoundaryError();
    if (typeof value === 'string') {
        if (encoder.encode(value).byteLength > 32 * 1024) throw new NativeDocumentBoundaryError();
        return;
    }
    if (value === null || typeof value === 'boolean') return;
    if (typeof value === 'number') {
        if (!Number.isFinite(value)) throw new NativeDocumentBoundaryError();
        return;
    }
    if (Array.isArray(value)) { value.forEach(entry => validateJson(entry, depth + 1)); return; }
    if (!isRecord(value)) throw new NativeDocumentBoundaryError();
    for (const [key, entry] of Object.entries(value)) {
        if (encoder.encode(key).byteLength > 32 * 1024) throw new NativeDocumentBoundaryError();
        validateJson(entry, depth + 1);
    }
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return value !== null && typeof value === 'object' && !Array.isArray(value);
}
