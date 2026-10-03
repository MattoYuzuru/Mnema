import { NativeDocument } from '../../content/native-document';
import { NativeRenderNode, RenderMarkedText, buildNativeRenderState } from '../../content/rendering/native-render-state';

/** One run of the diff: unchanged text, text taken out of the old version, or text put into the new one. */
export interface DiffSegment { readonly kind: 'same' | 'del' | 'ins'; readonly text: string; }

/** A paragraph of the diff, in reading order. */
export interface DiffParagraph { readonly segments: readonly DiffSegment[]; }

/** The top-level blocks of a document as the Workshop reasons about them: id, render kind and plain text lines. */
export interface BlockInfo { readonly id: string; readonly kind: string; readonly lines: readonly string[]; }

const MEDIA_KINDS: readonly string[] = ['image', 'audio', 'video'];

export function isMediaKind(kind: string | undefined): boolean {
    return kind !== undefined && MEDIA_KINDS.includes(kind);
}

function markedText(marked: RenderMarkedText): string {
    return marked.kind === 'value' ? marked.value : markedText(marked.child);
}

function inlineText(nodes: readonly NativeRenderNode[]): string {
    return nodes.map(node => {
        switch (node.kind) {
            case 'text': return markedText(node.markedText);
            case 'ruby': return node.base;
            case 'link': return inlineText(node.content);
            default: return '';
        }
    }).join('');
}

/** Plain text of a block, one string per line; media blocks have none (a rewrite never touches them). */
function linesOf(node: NativeRenderNode): string[] {
    switch (node.kind) {
        case 'paragraph':
        case 'heading': return [inlineText(node.content)];
        case 'blockquote': return node.content.flatMap(linesOf);
        case 'bullet-list':
        case 'ordered-list': {
            const start = node.kind === 'ordered-list' ? node.order ?? 1 : 0;
            return node.content.flatMap((item, position) => {
                const own = 'content' in item ? item.content.flatMap(linesOf) : [];
                return own.length === 0 ? [] : [`${node.kind === 'ordered-list' ? `${start + position}. ` : '• '}${own.join(' ')}`];
            });
        }
        case 'code-block': return [node.source];
        case 'table': return [node.caption, node.columns.join(' | '), ...node.rows.map(row => row.join(' | '))].filter(line => line.length > 0);
        case 'youtube':
        case 'mermaid': return [node.title];
        default: return [];
    }
}

/** The top-level blocks of `document` in order; empty for a document the renderer refuses. */
export function blocksOf(document: NativeDocument): readonly BlockInfo[] {
    const state = buildNativeRenderState(document);
    return state.status !== 'ready' ? [] : state.root.content.map(node => ({ id: node.id, kind: node.kind, lines: linesOf(node) }));
}

interface Token { readonly text: string; readonly key: string; }

const BREAK = '\u0000¶';
/** Above this many table cells the exact diff is not worth its memory: the whole text is shown as replaced. */
const MAX_CELLS = 4_000_000;

function tokensOf(lines: readonly string[]): Token[] {
    const tokens: Token[] = [];
    lines.forEach((line, position) => {
        if (position > 0) tokens.push({ text: '', key: BREAK });
        for (const word of line.match(/\S+\s*/gu) ?? []) tokens.push({ text: word, key: word.trim() });
    });
    return tokens;
}

/** Longest common subsequence of the keys, as the matched index pairs. */
function commonPairs(before: readonly Token[], after: readonly Token[]): readonly (readonly [number, number])[] {
    const rows = before.length + 1;
    const columns = after.length + 1;
    if (rows * columns > MAX_CELLS) return [];
    const table = new Uint16Array(rows * columns);
    for (let row = before.length - 1; row >= 0; row--) {
        for (let column = after.length - 1; column >= 0; column--) {
            table[row * columns + column] = before[row]!.key === after[column]!.key
                ? table[(row + 1) * columns + column + 1]! + 1
                : Math.max(table[(row + 1) * columns + column]!, table[row * columns + column + 1]!);
        }
    }
    const pairs: [number, number][] = [];
    let row = 0;
    let column = 0;
    while (row < before.length && column < after.length) {
        if (before[row]!.key === after[column]!.key) { pairs.push([row, column]); row++; column++; }
        else if (table[(row + 1) * columns + column]! >= table[row * columns + column + 1]!) row++;
        else column++;
    }
    return pairs;
}

/**
 * The word diff of two versions of a fragment, as paragraphs of runs: a small LCS over words (no dependency). A paragraph
 * break is a token like any other, so a rewrite that merges or splits paragraphs shows it. Words keep the whitespace that follows
 * them; a run of the same kind is one segment, so `<del>` and `<ins>` come in as few pieces as possible.
 */
export function diffLines(before: readonly string[], after: readonly string[]): readonly DiffParagraph[] {
    const old = tokensOf(before);
    const next = tokensOf(after);
    const pairs = commonPairs(old, next);
    const runs: { kind: DiffSegment['kind']; token: Token }[] = [];
    let row = 0;
    let column = 0;
    const flush = (toRow: number, toColumn: number): void => {
        for (; row < toRow; row++) runs.push({ kind: 'del', token: old[row]! });
        for (; column < toColumn; column++) runs.push({ kind: 'ins', token: next[column]! });
    };
    if (pairs.length === 0) flush(old.length, next.length);
    for (const [pairRow, pairColumn] of pairs) {
        flush(pairRow, pairColumn);
        // The whitespace after a word may differ between the versions: keep the longer, so a neighbouring insertion never touches it.
        const before = old[pairRow]!;
        const after = next[pairColumn]!;
        runs.push({ kind: 'same', token: before.text.length > after.text.length ? before : after });
        row = pairRow + 1;
        column = pairColumn + 1;
    }
    if (pairs.length > 0) flush(old.length, next.length);

    const paragraphs: DiffSegment[][] = [[]];
    for (const { kind, token } of runs) {
        const current = paragraphs[paragraphs.length - 1]!;
        if (token.key === BREAK) {
            if (kind !== 'del') paragraphs.push([]);
            continue;
        }
        const last = current[current.length - 1];
        if (last?.kind === kind) current[current.length - 1] = { kind, text: last.text + token.text };
        else current.push({ kind, text: token.text });
    }
    return paragraphs.filter(segments => segments.length > 0).map(segments => {
        const last = segments[segments.length - 1]!;
        return { segments: [...segments.slice(0, -1), { kind: last.kind, text: last.text.trimEnd() }] };
    });
}

/** Whether the diff holds any change at all. */
export function hasChanges(paragraphs: readonly DiffParagraph[]): boolean {
    return paragraphs.some(paragraph => paragraph.segments.some(segment => segment.kind !== 'same'));
}
