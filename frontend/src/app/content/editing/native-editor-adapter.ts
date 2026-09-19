import {
    DOMOutputSpec,
    DOMParser as ProseMirrorDomParser,
    MarkSpec,
    Node as ProseMirrorNode,
    NodeSpec,
    Schema
} from 'prosemirror-model';
import { Plugin, PluginKey, Transaction } from 'prosemirror-state';

import { NativeDocument, NativeJson, NativeNode } from '../native-document';
import { readNativeDocument } from '../native-document-boundary';
import { buildNativeRenderState, isAllowedNativeHref } from '../rendering/native-render-state';

export class NativeEditorAdapterError extends Error {
    constructor(message = 'Invalid native document for editing.') {
        super(message);
        this.name = 'NativeEditorAdapterError';
    }
}

export interface NativeEditorImport {
    readonly editable: boolean;
    readonly document: ProseMirrorNode | null;
    readonly source: NativeDocument;
}

interface IdentityAttrs {
    readonly id: string;
    readonly version: number;
    readonly lang: string | null;
    readonly dir: string | null;
    readonly boundary: boolean;
    readonly marksPresent: boolean;
}

type NativeSlot = 'root' | 'block' | 'inline' | 'list-item';

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const TYPE = /^[a-z][a-z0-9_]{0,63}$/;
const KNOWN_TYPES = new Set([
    'doc', 'paragraph', 'heading', 'blockquote', 'bullet_list', 'ordered_list',
    'list_item', 'text', 'ruby', 'link', 'divider'
]);
const textIdentityKey = new PluginKey('mnema-text-identity');

const commonAttrs = {
    id: { default: null },
    version: { default: 1 },
    lang: { default: null },
    dir: { default: null }
};

const nodes: Record<string, NodeSpec> = {
    doc: { content: 'block+', attrs: commonAttrs },
    paragraph: {
        content: 'inline*', group: 'block', attrs: commonAttrs,
        parseDOM: [{ tag: 'p' }],
        toDOM: node => ['p', metadataDomAttrs(node.attrs), 0]
    },
    heading: {
        content: 'inline*', group: 'block', defining: true,
        attrs: { ...commonAttrs, level: { default: 2 } },
        parseDOM: [1, 2, 3, 4, 5, 6].map(level => ({ tag: `h${level}`, attrs: { level } })),
        toDOM: node => [`h${node.attrs['level'] as number}`, metadataDomAttrs(node.attrs), 0]
    },
    blockquote: {
        content: 'block+', group: 'block', defining: true, attrs: commonAttrs,
        parseDOM: [{ tag: 'blockquote' }], toDOM: node => ['blockquote', metadataDomAttrs(node.attrs), 0]
    },
    bullet_list: {
        content: 'list_item_node+', group: 'block', attrs: commonAttrs,
        parseDOM: [{ tag: 'ul' }], toDOM: node => ['ul', metadataDomAttrs(node.attrs), 0]
    },
    ordered_list: {
        content: 'list_item_node+', group: 'block', attrs: {
            ...commonAttrs, order: { default: 1 }, orderPresent: { default: false }
        },
        parseDOM: [{
            tag: 'ol',
            getAttrs: element => ({ order: Number((element as HTMLElement).getAttribute('start') ?? '1') || 1 })
        }],
        toDOM: node => ['ol', { ...metadataDomAttrs(node.attrs), start: node.attrs['order'] as number }, 0]
    },
    list_item: {
        content: 'paragraph block*', group: 'list_item_node', defining: true, attrs: commonAttrs,
        parseDOM: [{ tag: 'li' }], toDOM: node => ['li', metadataDomAttrs(node.attrs), 0]
    },
    divider: {
        group: 'block', atom: true, selectable: true, attrs: commonAttrs,
        parseDOM: [{ tag: 'hr' }], toDOM: node => ['hr', metadataDomAttrs(node.attrs)]
    },
    ruby: {
        inline: true, group: 'inline', atom: true, selectable: true,
        attrs: { ...commonAttrs, base: {}, reading: {} },
        toDOM: node => ['ruby', metadataDomAttrs(node.attrs),
            String(node.attrs['base']), ['rp', '('], ['rt', String(node.attrs['reading'])], ['rp', ')']]
    },
    link_node: {
        inline: true, group: 'inline', content: 'inline*', attrs: { ...commonAttrs, href: {} },
        parseDOM: [{
            tag: 'a[href]',
            getAttrs: element => {
                const href = (element as HTMLElement).getAttribute('href');
                return href !== null && isAllowedNativeHref(href) ? { href } : false;
            }
        }],
        toDOM: node => ['a', { ...metadataDomAttrs(node.attrs), href: String(node.attrs['href']) }, 0]
    },
    unsupported_block: {
        group: 'block', atom: true, selectable: true, attrs: { payload: {} },
        toDOM: node => unsupportedDom(node, 'Неподдерживаемый блок')
    },
    unsupported_inline: {
        inline: true, group: 'inline', atom: true, selectable: true, attrs: { payload: {} },
        toDOM: node => unsupportedDom(node, 'Неподдерживаемый фрагмент')
    },
    unsupported_list_item: {
        group: 'list_item_node', atom: true, selectable: true, attrs: { payload: {} },
        toDOM: node => ['li', { class: 'mnema-unsupported', 'aria-label': 'Неподдерживаемый пункт' },
            safeOpaqueLabel(node.attrs['payload'])]
    },
    text: { group: 'inline' }
};

const marks: Record<string, MarkSpec> = {
    strong: { parseDOM: [{ tag: 'strong' }, { tag: 'b' }], toDOM: () => ['strong', 0] },
    em: { parseDOM: [{ tag: 'em' }, { tag: 'i' }], toDOM: () => ['em', 0] },
    code: { parseDOM: [{ tag: 'code' }], toDOM: () => ['code', 0] },
    mnema_text: {
        attrs: {
            id: {}, version: { default: 1 }, lang: { default: null }, dir: { default: null },
            boundary: { default: false }, marksPresent: { default: false }
        },
        inclusive: true,
        excludes: '',
        toDOM: () => ['span', { 'data-mnema-text': '' }, 0]
    }
};

export const nativeEditorSchema = new Schema({ nodes, marks });

/** Keeps native identities private, unique and stable within the originating undo event. */
export const nativeTextIdentityPlugin = new Plugin({
    key: textIdentityKey,
    appendTransaction(transactions, _oldState, newState): Transaction | null {
        if (!transactions.some(transaction => transaction.docChanged) || transactions.some(transaction => transaction.getMeta(textIdentityKey))) {
            return null;
        }
        const identity = nativeEditorSchema.marks['mnema_text']!;
        const seen = new Set<string>();
        const textReplacements: Array<{ from: number; to: number; attrs: IdentityAttrs }> = [];
        const nodeReplacements: Array<{ position: number; attrs: Record<string, unknown> }> = [];
        newState.doc.descendants((node, position) => {
            if (!node.isText) {
                if (node.type.name.startsWith('unsupported_')) {
                    const payload = node.attrs['payload'];
                    const opaqueIds = nativeIds(payload);
                    if (opaqueIds === null || opaqueIds.some(id => seen.has(id))) {
                        nodeReplacements.push({ position, attrs: { ...node.attrs, payload: reidentifyNative(payload) } });
                    } else {
                        opaqueIds.forEach(id => seen.add(id));
                    }
                } else {
                    const id = typeof node.attrs['id'] === 'string' ? node.attrs['id'] as string : '';
                    if (!UUID_V4.test(id) || seen.has(id.toLowerCase())) {
                        nodeReplacements.push({ position, attrs: { ...node.attrs, id: crypto.randomUUID() } });
                    } else {
                        seen.add(id.toLowerCase());
                    }
                }
                return true;
            }
            const current = node.marks.find(mark => mark.type === identity);
            const id = typeof current?.attrs['id'] === 'string' ? current.attrs['id'] as string : '';
            if (!UUID_V4.test(id) || seen.has(id.toLowerCase())) {
                textReplacements.push({
                    from: position,
                    to: position + node.nodeSize,
                    attrs: {
                        id: crypto.randomUUID(),
                        version: readPositiveInteger(current?.attrs['version'], 1),
                        lang: readNullableString(current?.attrs['lang']),
                        dir: readNullableString(current?.attrs['dir']),
                        boundary: false,
                        marksPresent: current?.attrs['marksPresent'] === true
                    }
                });
            } else {
                seen.add(id.toLowerCase());
            }
            return true;
        });
        if (textReplacements.length === 0 && nodeReplacements.length === 0) return null;
        // Appended normalization is intentionally part of the originating history event,
        // so undo restores both content and native identities together.
        const transaction = newState.tr.setMeta(textIdentityKey, true);
        for (const replacement of nodeReplacements) {
            transaction.setNodeMarkup(replacement.position, undefined, replacement.attrs);
        }
        for (const replacement of textReplacements) {
            transaction.removeMark(replacement.from, replacement.to, identity);
            transaction.addMark(replacement.from, replacement.to, identity.create(replacement.attrs));
        }
        return transaction;
    }
});

export function importNativeDocument(document: NativeDocument): NativeEditorImport {
    let source: NativeDocument;
    try { source = readNativeDocument(document); } catch { throw new NativeEditorAdapterError(); }
    if (source.root.version !== 1) return { editable: false, document: null, source };
    const renderState = buildNativeRenderState(source);
    if (renderState.status !== 'ready') throw new NativeEditorAdapterError();
    const pm = importNode(source.root, 'root');
    if (pm.type !== nativeEditorSchema.topNodeType) throw new NativeEditorAdapterError();
    return { editable: true, document: pm, source };
}

export function exportNativeDocument(document: ProseMirrorNode): NativeDocument {
    if (document.type !== nativeEditorSchema.topNodeType) throw new NativeEditorAdapterError();
    try { return readNativeDocument({ formatVersion: 1 as const, root: exportNode(document) }); }
    catch { throw new NativeEditorAdapterError(); }
}

export function createEmptyNativeDocument(): NativeDocument {
    return {
        formatVersion: 1,
        root: {
            id: crypto.randomUUID(), type: 'doc', version: 1, attrs: {},
            content: [{ id: crypto.randomUUID(), type: 'paragraph', version: 1, attrs: {}, content: [] }]
        }
    };
}

export function parseSafePastedHtml(html: string): ProseMirrorNode {
    const parsed = sanitizePastedDocument(html);
    return ProseMirrorDomParser.fromSchema(nativeEditorSchema).parse(parsed.body);
}

export function sanitizePastedHtml(html: string): string {
    return sanitizePastedDocument(html).body.innerHTML;
}

function sanitizePastedDocument(html: string): globalThis.Document {
    const parsed = new window.DOMParser().parseFromString(html, 'text/html');
    parsed.querySelectorAll('script,style,svg,math,iframe,object,embed,form,input,button,textarea,select,option,img,audio,video,source')
        .forEach(element => element.remove());
    parsed.body.querySelectorAll('*').forEach(element => {
        for (const attribute of Array.from(element.attributes)) {
            const name = attribute.name.toLowerCase();
            if (name.startsWith('on') || name === 'style' || name === 'src' || name === 'srcdoc') {
                element.removeAttribute(attribute.name);
            }
        }
        if (element instanceof HTMLAnchorElement && !isAllowedNativeHref(element.getAttribute('href') ?? '')) {
            element.removeAttribute('href');
        }
    });
    return parsed;
}

function importNode(node: NativeNode, slot: NativeSlot): ProseMirrorNode {
    if (!KNOWN_TYPES.has(node.type) || node.version !== 1) {
        const opaqueType = slot === 'inline' ? 'unsupported_inline'
            : slot === 'list-item' ? 'unsupported_list_item' : 'unsupported_block';
        return nativeEditorSchema.nodes[opaqueType]!.create({ payload: cloneJson(node) });
    }
    const metadata = nativeMetadata(node);
    switch (node.type) {
        case 'doc':
            return nativeEditorSchema.node('doc', metadata, node.content.map(child => importNode(child, 'block')));
        case 'paragraph':
            return nativeEditorSchema.node('paragraph', metadata, node.content.map(child => importNode(child, 'inline')));
        case 'heading':
            return nativeEditorSchema.node('heading', { ...metadata, level: node.attrs['level'] },
                node.content.map(child => importNode(child, 'inline')));
        case 'blockquote':
            return nativeEditorSchema.node('blockquote', metadata, node.content.map(child => importNode(child, 'block')));
        case 'bullet_list':
            return nativeEditorSchema.node('bullet_list', metadata, node.content.map(child => importNode(child, 'list-item')));
        case 'ordered_list':
            return nativeEditorSchema.node('ordered_list', {
                ...metadata, order: node.attrs['order'] ?? 1, orderPresent: Object.hasOwn(node.attrs, 'order')
            },
                node.content.map(child => importNode(child, 'list-item')));
        case 'list_item':
            return nativeEditorSchema.node('list_item', metadata, node.content.map(child => importNode(child, 'block')));
        case 'divider':
            return nativeEditorSchema.node('divider', metadata);
        case 'ruby':
            return nativeEditorSchema.node('ruby', { ...metadata, base: node.attrs['base'], reading: node.attrs['reading'] });
        case 'link':
            return nativeEditorSchema.node('link_node', { ...metadata, href: node.attrs['href'] },
                node.content.map(child => importNode(child, 'inline')));
        case 'text': {
            const identity = nativeEditorSchema.mark('mnema_text', {
                ...metadata, boundary: true, marksPresent: Object.hasOwn(node.attrs, 'marks')
            });
            const nativeMarks = Array.isArray(node.attrs['marks']) ? node.attrs['marks'] : [];
            const visible = nativeMarks.map(mark => nativeEditorSchema.mark(String(mark)));
            return nativeEditorSchema.text(String(node.attrs['text']), [identity, ...visible]);
        }
        default:
            throw new NativeEditorAdapterError();
    }
}

function exportNode(node: ProseMirrorNode): NativeNode {
    if (node.isText) return exportText(node);
    if (node.type.name.startsWith('unsupported_')) return cloneJson(node.attrs['payload']) as unknown as NativeNode;
    const attrs: Record<string, NativeJson> = {};
    const id = requireUuid(node.attrs['id']);
    const version = readPositiveInteger(node.attrs['version'], 1);
    addMetadata(attrs, node.attrs);
    let type = node.type.name;
    if (type === 'link_node') type = 'link';
    switch (type) {
        case 'heading': attrs['level'] = readPositiveInteger(node.attrs['level'], 2); break;
        case 'ordered_list':
            if (node.attrs['orderPresent'] === true) attrs['order'] = readPositiveInteger(node.attrs['order'], 1);
            break;
        case 'ruby':
            attrs['base'] = String(node.attrs['base']);
            attrs['reading'] = String(node.attrs['reading']);
            break;
        case 'link': attrs['href'] = String(node.attrs['href']); break;
    }
    return { id, type, version, attrs, content: node.content.content.map(exportNode) };
}

function exportText(node: ProseMirrorNode): NativeNode {
    const identity = node.marks.find(mark => mark.type.name === 'mnema_text');
    if (identity === undefined || node.text === undefined || node.text.length === 0) throw new NativeEditorAdapterError();
    const attrs: Record<string, NativeJson> = { text: node.text };
    const visibleMarks = node.marks.filter(mark => mark.type.name !== 'mnema_text').map(mark => mark.type.name);
    if (visibleMarks.length > 0 || identity.attrs['marksPresent'] === true) attrs['marks'] = visibleMarks;
    addMetadata(attrs, identity.attrs);
    return {
        id: requireUuid(identity.attrs['id']), type: 'text', version: readPositiveInteger(identity.attrs['version'], 1),
        attrs, content: []
    };
}

function nativeMetadata(node: NativeNode): Record<string, unknown> {
    return {
        id: node.id,
        version: node.version,
        lang: typeof node.attrs['lang'] === 'string' ? node.attrs['lang'] : null,
        dir: typeof node.attrs['dir'] === 'string' ? node.attrs['dir'] : null
    };
}

function addMetadata(target: Record<string, NativeJson>, source: Record<string, unknown>): void {
    if (typeof source['lang'] === 'string') target['lang'] = source['lang'];
    if (typeof source['dir'] === 'string') target['dir'] = source['dir'];
}

function metadataDomAttrs(attrs: Record<string, unknown>): Record<string, string> {
    const result: Record<string, string> = {};
    if (typeof attrs['lang'] === 'string') result['lang'] = attrs['lang'];
    if (typeof attrs['dir'] === 'string') result['dir'] = attrs['dir'];
    return result;
}

function unsupportedDom(node: ProseMirrorNode, label: string): DOMOutputSpec {
    return ['span', { class: 'mnema-unsupported', role: 'note', 'aria-label': label }, safeOpaqueLabel(node.attrs['payload'])];
}

function safeOpaqueLabel(value: unknown): string {
    if (!isRecord(value)) return 'Неподдерживаемое содержимое';
    const type = typeof value['type'] === 'string' && TYPE.test(value['type']) ? value['type'] : 'unknown';
    const version = Number.isInteger(value['version']) ? value['version'] : '?';
    return `Неподдерживаемое содержимое: ${type}@${version}`;
}

function nativeIds(value: unknown): readonly string[] | null {
    if (!isRecord(value)) return null;
    const ids: string[] = [];
    const visit = (node: unknown): boolean => {
        if (!isRecord(node) || typeof node['id'] !== 'string' || !UUID_V4.test(node['id'])
            || !Array.isArray(node['content'])) return false;
        ids.push(node['id'].toLowerCase());
        return node['content'].every(visit);
    };
    return visit(value) && new Set(ids).size === ids.length ? ids : null;
}

function reidentifyNative(value: unknown): unknown {
    const copied = cloneJson(value);
    const visit = (node: unknown): void => {
        if (!isRecord(node) || !Array.isArray(node['content'])) throw new NativeEditorAdapterError();
        node['id'] = crypto.randomUUID();
        node['content'].forEach(visit);
    };
    visit(copied);
    return copied;
}

function cloneJson<T>(value: T): T {
    return structuredClone(value);
}

function requireUuid(value: unknown): string {
    if (typeof value !== 'string' || !UUID_V4.test(value)) throw new NativeEditorAdapterError();
    return value;
}

function readPositiveInteger(value: unknown, fallback: number): number {
    return Number.isInteger(value) && (value as number) > 0 ? value as number : fallback;
}

function readNullableString(value: unknown): string | null {
    return typeof value === 'string' ? value : null;
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return value !== null && typeof value === 'object' && !Array.isArray(value);
}
