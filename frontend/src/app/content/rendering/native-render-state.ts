import { NativeDocument, NativeNode } from '../native-document';

export const NATIVE_RENDER_LIMITS = {
    maxDepth: 32,
    maxNodes: 10_000,
    maxScalarBytes: 32 * 1024,
    maxVisibleBytes: 1024 * 1024
} as const;

export type NativeDirection = 'auto' | 'ltr' | 'rtl';
export type NativeTextMark = 'strong' | 'em' | 'code';

interface RenderNodeBase {
    readonly id: string;
    readonly lang?: string;
    readonly dir?: NativeDirection;
}

export interface RenderContainerNode extends RenderNodeBase {
    readonly kind: 'doc' | 'paragraph' | 'blockquote' | 'bullet-list' | 'list-item';
    readonly content: readonly NativeRenderNode[];
}

export interface RenderOrderedListNode extends RenderNodeBase {
    readonly kind: 'ordered-list';
    readonly order?: number;
    readonly content: readonly NativeRenderNode[];
}

export interface RenderHeadingNode extends RenderNodeBase {
    readonly kind: 'heading';
    readonly level: number;
    readonly content: readonly NativeRenderNode[];
}

export interface RenderTextNode extends RenderNodeBase {
    readonly kind: 'text';
    readonly markedText: RenderMarkedText;
}

export interface RenderRubyNode extends RenderNodeBase {
    readonly kind: 'ruby';
    readonly base: string;
    readonly reading: string;
}

export interface RenderLinkNode extends RenderNodeBase {
    readonly kind: 'link';
    readonly href: string;
    readonly content: readonly NativeRenderNode[];
}

export interface RenderDividerNode extends RenderNodeBase {
    readonly kind: 'divider';
}

export interface RenderOpaqueNode extends RenderNodeBase {
    readonly kind: 'opaque';
    readonly display: 'block' | 'inline' | 'list-item';
}

export type NativeRenderNode = RenderContainerNode
    | RenderOrderedListNode
    | RenderHeadingNode
    | RenderTextNode
    | RenderRubyNode
    | RenderLinkNode
    | RenderDividerNode
    | RenderOpaqueNode;

export interface RenderTextValue {
    readonly kind: 'value';
    readonly value: string;
}

export interface RenderTextWrapper {
    readonly kind: 'mark';
    readonly mark: NativeTextMark;
    readonly child: RenderMarkedText;
}

export type RenderMarkedText = RenderTextValue | RenderTextWrapper;

export type NativeRenderState =
    | { readonly status: 'ready'; readonly root: RenderContainerNode }
    | { readonly status: 'unsupported-document'; readonly root: RenderOpaqueNode }
    | { readonly status: 'invalid' };

type RenderSlot = 'root' | 'block' | 'inline' | 'list-item';

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const NODE_TYPE = /^[a-z][a-z0-9_]{0,63}$/;
const SUPPORTED_TYPES = new Set([
    'doc',
    'paragraph',
    'heading',
    'blockquote',
    'bullet_list',
    'ordered_list',
    'list_item',
    'text',
    'ruby',
    'link',
    'divider'
]);
const BLOCK_TYPES = new Set(['paragraph', 'heading', 'blockquote', 'bullet_list', 'ordered_list', 'divider']);
const INLINE_TYPES = new Set(['text', 'ruby', 'link']);
const CORE_KEYS = new Set(['id', 'type', 'version', 'attrs', 'content']);
const COMMON_ATTR_KEYS = new Set(['lang', 'dir']);
const encoder = new TextEncoder();

class InvalidNativeDocument extends Error {}

class RenderBudget {
    private nodes = 0;
    private visibleBytes = 0;

    visit(depth: number): void {
        this.nodes += 1;
        if (depth > NATIVE_RENDER_LIMITS.maxDepth || this.nodes > NATIVE_RENDER_LIMITS.maxNodes) {
            throw new InvalidNativeDocument();
        }
    }

    scalar(value: string): string {
        const bytes = encoder.encode(value).byteLength;
        if (bytes > NATIVE_RENDER_LIMITS.maxScalarBytes) {
            throw new InvalidNativeDocument();
        }
        this.visibleBytes += bytes;
        if (this.visibleBytes > NATIVE_RENDER_LIMITS.maxVisibleBytes) {
            throw new InvalidNativeDocument();
        }
        return value;
    }
}

/**
 * Builds a bounded, inert view of an already-semantic native document.
 * Unknown attributes and descendants deliberately never enter the render model.
 */
export function buildNativeRenderState(document: NativeDocument): NativeRenderState {
    try {
        if (!isRecord(document) || document['formatVersion'] !== 1 || !hasOnlyKeys(document, ['formatVersion', 'root'])) {
            return { status: 'invalid' };
        }

        const rootValue = document['root'];
        if (!isRecord(rootValue) || rootValue['type'] !== 'doc') {
            return { status: 'invalid' };
        }

        const context: RenderContext = {
            budget: new RenderBudget(),
            ids: new Set<string>()
        };
        const root = buildNode(rootValue as unknown as NativeNode, 'root', 1, context, false);
        if (root.kind === 'opaque') {
            return { status: 'unsupported-document', root };
        }
        if (root.kind !== 'doc') {
            return { status: 'invalid' };
        }
        return { status: 'ready', root };
    } catch {
        return { status: 'invalid' };
    }
}

interface RenderContext {
    readonly budget: RenderBudget;
    readonly ids: Set<string>;
}

function buildNode(
    rawNode: NativeNode,
    slot: RenderSlot,
    depth: number,
    context: RenderContext,
    insideLink: boolean
): NativeRenderNode {
    context.budget.visit(depth);
    if (!isRecord(rawNode)) {
        throw new InvalidNativeDocument();
    }

    const id = readString(rawNode, 'id', context.budget);
    if (!UUID_V4.test(id) || context.ids.has(id.toLowerCase())) {
        throw new InvalidNativeDocument();
    }
    context.ids.add(id.toLowerCase());

    const type = readString(rawNode, 'type', context.budget);
    const version = rawNode['version'];
    const attrs = rawNode['attrs'];
    const content = rawNode['content'];
    if (!NODE_TYPE.test(type) || !Number.isInteger(version) || (version as number) < 1
        || (version as number) > 2_147_483_647 || !isRecord(attrs) || !Array.isArray(content)) {
        throw new InvalidNativeDocument();
    }

    if (!SUPPORTED_TYPES.has(type) || version !== 1) {
        return {
            kind: 'opaque',
            display: slot === 'inline' ? 'inline' : slot === 'list-item' ? 'list-item' : 'block',
            id
        };
    }

    if (!hasOnlyKeys(rawNode, CORE_KEYS)) {
        throw new InvalidNativeDocument();
    }
    assertTypeAllowedInSlot(type, slot);

    const metadata = readMetadata(attrs, context.budget);
    switch (type) {
        case 'doc':
            assertAttrs(attrs, COMMON_ATTR_KEYS);
            assertNonEmpty(content);
            return {
                kind: 'doc',
                id,
                ...metadata,
                content: buildChildren(content, 'block', depth, context, false)
            };
        case 'paragraph':
            assertAttrs(attrs, COMMON_ATTR_KEYS);
            return {
                kind: 'paragraph',
                id,
                ...metadata,
                content: buildChildren(content, 'inline', depth, context, insideLink)
            };
        case 'heading':
            assertAttrs(attrs, new Set([...COMMON_ATTR_KEYS, 'level']));
            return {
                kind: 'heading',
                id,
                ...metadata,
                level: readInteger(attrs, 'level', 1, 6),
                content: buildChildren(content, 'inline', depth, context, insideLink)
            };
        case 'blockquote':
            assertAttrs(attrs, COMMON_ATTR_KEYS);
            assertNonEmpty(content);
            return {
                kind: 'blockquote',
                id,
                ...metadata,
                content: buildChildren(content, 'block', depth, context, false)
            };
        case 'bullet_list':
            assertAttrs(attrs, COMMON_ATTR_KEYS);
            assertNonEmpty(content);
            return {
                kind: 'bullet-list',
                id,
                ...metadata,
                content: buildChildren(content, 'list-item', depth, context, false)
            };
        case 'ordered_list': {
            assertAttrs(attrs, new Set([...COMMON_ATTR_KEYS, 'order']));
            assertNonEmpty(content);
            const orderValue = attrs['order'];
            const order = orderValue === undefined ? undefined : readInteger(attrs, 'order', 1, 2_147_483_647);
            return {
                kind: 'ordered-list',
                id,
                ...metadata,
                ...(order === undefined ? {} : { order }),
                content: buildChildren(content, 'list-item', depth, context, false)
            };
        }
        case 'list_item': {
            assertAttrs(attrs, COMMON_ATTR_KEYS);
            assertNonEmpty(content);
            const children = buildChildren(content, 'block', depth, context, false);
            if (children[0]?.kind !== 'paragraph' && children[0]?.kind !== 'opaque') {
                throw new InvalidNativeDocument();
            }
            return { kind: 'list-item', id, ...metadata, content: children };
        }
        case 'text': {
            assertAttrs(attrs, new Set([...COMMON_ATTR_KEYS, 'text', 'marks']));
            assertEmpty(content);
            const text = readNonEmptyString(attrs, 'text', context.budget);
            const marks = readMarks(attrs['marks']);
            return { kind: 'text', id, ...metadata, markedText: wrapMarks(text, marks) };
        }
        case 'ruby':
            assertAttrs(attrs, new Set([...COMMON_ATTR_KEYS, 'base', 'reading']));
            assertEmpty(content);
            return {
                kind: 'ruby',
                id,
                ...metadata,
                base: readNonEmptyString(attrs, 'base', context.budget),
                reading: readNonEmptyString(attrs, 'reading', context.budget)
            };
        case 'link': {
            assertAttrs(attrs, new Set([...COMMON_ATTR_KEYS, 'href']));
            assertNonEmpty(content);
            if (insideLink) {
                throw new InvalidNativeDocument();
            }
            const href = readNonEmptyString(attrs, 'href', context.budget);
            if (!isAllowedNativeHref(href)) {
                throw new InvalidNativeDocument();
            }
            return {
                kind: 'link',
                id,
                ...metadata,
                href,
                content: buildChildren(content, 'inline', depth, context, true)
            };
        }
        case 'divider':
            assertAttrs(attrs, COMMON_ATTR_KEYS);
            assertEmpty(content);
            return { kind: 'divider', id, ...metadata };
        default:
            throw new InvalidNativeDocument();
    }
}

function buildChildren(
    content: readonly unknown[],
    slot: RenderSlot,
    parentDepth: number,
    context: RenderContext,
    insideLink: boolean
): readonly NativeRenderNode[] {
    return content.map(child => buildNode(child as NativeNode, slot, parentDepth + 1, context, insideLink));
}

function assertTypeAllowedInSlot(type: string, slot: RenderSlot): void {
    const allowed = slot === 'root'
        ? type === 'doc'
        : slot === 'block'
            ? BLOCK_TYPES.has(type)
            : slot === 'inline'
                ? INLINE_TYPES.has(type)
                : type === 'list_item';
    if (!allowed) {
        throw new InvalidNativeDocument();
    }
}

function readMetadata(attrs: Record<string, unknown>, budget: RenderBudget): { lang?: string; dir?: NativeDirection } {
    const langValue = attrs['lang'];
    const dirValue = attrs['dir'];
    const metadata: { lang?: string; dir?: NativeDirection } = {};
    if (langValue !== undefined) {
        if (typeof langValue !== 'string' || !isAllowedNativeLang(langValue)) {
            throw new InvalidNativeDocument();
        }
        metadata.lang = budget.scalar(langValue);
    }
    if (dirValue !== undefined) {
        if (dirValue !== 'auto' && dirValue !== 'ltr' && dirValue !== 'rtl') {
            throw new InvalidNativeDocument();
        }
        metadata.dir = dirValue;
    }
    return metadata;
}

function readMarks(value: unknown): readonly NativeTextMark[] {
    if (value === undefined) {
        return [];
    }
    if (!Array.isArray(value)) {
        throw new InvalidNativeDocument();
    }
    const marks: NativeTextMark[] = [];
    const seen = new Set<NativeTextMark>();
    for (const mark of value) {
        if (mark !== 'strong' && mark !== 'em' && mark !== 'code') {
            throw new InvalidNativeDocument();
        }
        if (seen.has(mark)) {
            throw new InvalidNativeDocument();
        }
        seen.add(mark);
        marks.push(mark);
    }
    return marks;
}

function wrapMarks(value: string, marks: readonly NativeTextMark[]): RenderMarkedText {
    let result: RenderMarkedText = { kind: 'value', value };
    for (let index = marks.length - 1; index >= 0; index -= 1) {
        result = { kind: 'mark', mark: marks[index]!, child: result };
    }
    return result;
}

export function isAllowedNativeLang(value: string): boolean {
    if (value.length === 0 || value.length > 64 || !hasOnlyAscii(value)) {
        return false;
    }
    const parts = value.split('-');
    if (!/^[a-z]{2,3}$/i.test(parts[0] ?? '')) {
        return false;
    }
    let index = 1;
    if (/^[a-z]{4}$/i.test(parts[index] ?? '')) {
        index += 1;
    }
    if (/^(?:[a-z]{2}|\d{3})$/i.test(parts[index] ?? '')) {
        index += 1;
    }
    const variants = new Set<string>();
    for (; index < parts.length; index += 1) {
        const variant = parts[index]!;
        if (!/^(?:[a-z0-9]{5,8}|\d[a-z0-9]{3})$/i.test(variant)) {
            return false;
        }
        const normalized = variant.toLowerCase();
        if (variants.has(normalized)) {
            return false;
        }
        variants.add(normalized);
    }
    return true;
}

function hasOnlyAscii(value: string): boolean {
    return Array.from(value).every(character => character.codePointAt(0)! <= 0x7f);
}

export function isAllowedNativeHref(value: string): boolean {
    if (value.length === 0 || value.length > 2048 || !/^[\x21-\x7e]+$/.test(value)
        || value.includes('\\') || /%(?![0-9a-f]{2})/i.test(value) || !/^https:\/\//i.test(value)) {
        return false;
    }

    const authorityEnd = value.slice(8).search(/[/?#]/);
    const authority = authorityEnd === -1 ? value.slice(8) : value.slice(8, authorityEnd + 8);
    if (authority.length === 0 || authority.includes('@')) {
        return false;
    }

    let host: string;
    let port: string | undefined;
    if (authority.startsWith('[')) {
        const bracket = authority.indexOf(']');
        if (bracket < 0 || authority.slice(1, bracket).includes('%')) {
            return false;
        }
        host = authority.slice(0, bracket + 1);
        const remainder = authority.slice(bracket + 1);
        if (remainder !== '') {
            if (!/^:\d+$/.test(remainder)) {
                return false;
            }
            port = remainder.slice(1);
        }
    } else {
        const colon = authority.lastIndexOf(':');
        if (colon >= 0) {
            if (authority.indexOf(':') !== colon) {
                return false;
            }
            host = authority.slice(0, colon);
            port = authority.slice(colon + 1);
            if (port.length === 0) {
                return false;
            }
        } else {
            host = authority;
        }
    }
    if (port !== undefined && (!/^\d+$/.test(port) || Number(port) < 1 || Number(port) > 65_535)) {
        return false;
    }

    try {
        const parsed = new URL(value);
        if (parsed.protocol !== 'https:' || parsed.username !== '' || parsed.password !== '') {
            return false;
        }
        if (host.startsWith('[')) {
            // URL parsing validates the literal but canonicalizes expanded and mapped IPv6 spellings.
            return true;
        }
    } catch {
        return false;
    }

    if (host.length === 0 || host.length > 253 || host.endsWith('.')) {
        return false;
    }
    const labels = host.split('.');
    if (labels.some(label => label.length === 0 || label.length > 63
        || !/^[a-z0-9-]+$/i.test(label) || label.startsWith('-') || label.endsWith('-')
        || (label.toLowerCase().startsWith('xn--') && !isValidALabel(label)))) {
        return false;
    }
    // Match URL/Java IPv4 classification without treating ordinary digit-ending DNS labels as IPv4.
    if (/^\d+$/.test(labels.at(-1)!)) {
        return isCanonicalIpv4(host);
    }
    try {
        return new URL(value).hostname.toLowerCase() === host.toLowerCase();
    } catch {
        return false;
    }
}

function isValidALabel(label: string): boolean {
    const decoded = decodePunycode(label.slice(4));
    if (decoded === null || ![...decoded].some(character => (character.codePointAt(0) ?? 0) >= 128)) {
        return false;
    }
    try {
        return new URL(`https://${decoded}/`).hostname.toLowerCase() === label.toLowerCase();
    } catch {
        return false;
    }
}

function decodePunycode(input: string): string | null {
    const base = 36;
    const output: number[] = [];
    const delimiter = input.lastIndexOf('-');
    let cursor = 0;
    if (delimiter >= 0) {
        for (const character of input.slice(0, delimiter)) {
            const codePoint = character.codePointAt(0);
            if (codePoint === undefined || codePoint >= 128) {
                return null;
            }
            output.push(codePoint);
        }
        cursor = delimiter + 1;
    }

    let n = 128;
    let delta = 0;
    let bias = 72;
    while (cursor < input.length) {
        const previousDelta = delta;
        let weight = 1;
        for (let thresholdIndex = base; ; thresholdIndex += base) {
            if (cursor >= input.length) {
                return null;
            }
            const digit = punycodeDigit(input.charCodeAt(cursor));
            cursor += 1;
            if (digit < 0 || digit > Math.floor((Number.MAX_SAFE_INTEGER - delta) / weight)) {
                return null;
            }
            delta += digit * weight;
            const threshold = thresholdIndex <= bias + 1 ? 1 : thresholdIndex >= bias + 26 ? 26 : thresholdIndex - bias;
            if (digit < threshold) {
                break;
            }
            const factor = base - threshold;
            if (weight > Math.floor(Number.MAX_SAFE_INTEGER / factor)) {
                return null;
            }
            weight *= factor;
        }

        const outputLength = output.length + 1;
        bias = adaptPunycodeBias(delta - previousDelta, outputLength, previousDelta === 0);
        const increment = Math.floor(delta / outputLength);
        if (increment > 0x10ffff - n) {
            return null;
        }
        n += increment;
        delta %= outputLength;
        if (n >= 0xd800 && n <= 0xdfff) {
            return null;
        }
        output.splice(delta, 0, n);
        delta += 1;
    }

    try {
        return String.fromCodePoint(...output);
    } catch {
        return null;
    }
}

function punycodeDigit(codePoint: number): number {
    if (codePoint >= 48 && codePoint <= 57) {
        return codePoint - 22;
    }
    if (codePoint >= 65 && codePoint <= 90) {
        return codePoint - 65;
    }
    if (codePoint >= 97 && codePoint <= 122) {
        return codePoint - 97;
    }
    return -1;
}

function adaptPunycodeBias(delta: number, pointCount: number, firstPoint: boolean): number {
    let remaining = firstPoint ? Math.floor(delta / 700) : Math.floor(delta / 2);
    remaining += Math.floor(remaining / pointCount);
    let iterations = 0;
    while (remaining > 455) {
        remaining = Math.floor(remaining / 35);
        iterations += 36;
    }
    return iterations + Math.floor((36 * remaining) / (remaining + 38));
}

function isCanonicalIpv4(host: string): boolean {
    const octets = host.split('.');
    return octets.length === 4 && octets.every(octet => /^(?:0|[1-9]\d{0,2})$/.test(octet) && Number(octet) <= 255);
}

function readString(record: Record<string, unknown>, key: string, budget: RenderBudget): string {
    const value = record[key];
    if (typeof value !== 'string') {
        throw new InvalidNativeDocument();
    }
    return budget.scalar(value);
}

function readNonEmptyString(record: Record<string, unknown>, key: string, budget: RenderBudget): string {
    const value = readString(record, key, budget);
    if (value.length === 0) {
        throw new InvalidNativeDocument();
    }
    return value;
}

function readInteger(record: Record<string, unknown>, key: string, minimum: number, maximum: number): number {
    const value = record[key];
    if (!Number.isInteger(value) || (value as number) < minimum || (value as number) > maximum) {
        throw new InvalidNativeDocument();
    }
    return value as number;
}

function assertAttrs(attrs: Record<string, unknown>, allowed: ReadonlySet<string>): void {
    if (!hasOnlyKeys(attrs, allowed)) {
        throw new InvalidNativeDocument();
    }
}

function assertEmpty(content: readonly unknown[]): void {
    if (content.length !== 0) {
        throw new InvalidNativeDocument();
    }
}

function assertNonEmpty(content: readonly unknown[]): void {
    if (content.length === 0) {
        throw new InvalidNativeDocument();
    }
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function hasOnlyKeys(record: Record<string, unknown>, allowed: Iterable<string>): boolean {
    const allowedKeys = allowed instanceof Set ? allowed : new Set(allowed);
    return Object.keys(record).every(key => allowedKeys.has(key));
}
