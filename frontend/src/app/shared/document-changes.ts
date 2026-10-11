import type { NativeDocument, NativeJson, NativeNode } from '../content/native-document';
import { type DiffParagraph, blocksOf, diffLines, hasChanges } from '../content/word-diff';

/** What happened to a top-level block between the reader's version and the author's. */
export type BlockChange = 'changed' | 'added' | 'removed';

/** Which column of a comparison a document is drawn in. */
export type CompareSide = 'mine' | 'author';

/** The changes of one side of a comparison, by the stable node id of the top-level blocks. */
export interface DocumentChanges {
    /** Blocks to mark: `changed` on both sides, `added` only in the author's version, `removed` only in the reader's. */
    readonly marks: ReadonlyMap<string, BlockChange>;
    /** Paragraphs whose words changed: the diff of the reader's text against the author's, drawn in place of the block. */
    readonly diffs: ReadonlyMap<string, readonly DiffParagraph[]>;
}

export const NO_CHANGES: DocumentChanges = { marks: new Map(), diffs: new Map() };

/** The node as a string that does not depend on the order of the keys, so two equal nodes compare equal. */
function canonical(value: NativeJson): string {
    if (value === null || typeof value !== 'object') return JSON.stringify(value);
    if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
    const object = value as { readonly [key: string]: NativeJson };
    return `{${Object.keys(object).sort().map(key => `${JSON.stringify(key)}:${canonical(object[key]!)}`).join(',')}}`;
}

function languageOf(document: NativeDocument): string | undefined {
    const lang = document.root.attrs['lang'];
    return typeof lang === 'string' ? lang : undefined;
}

/**
 * Compares the top-level blocks of `document` (drawn in column `side`) with the other side's version by node id. A block present on one
 * side only is `added` (author's side) or `removed` (reader's side); a block present on both with different content is `changed`, and
 * when both are paragraphs with different words the word diff is computed as well, so the column can show the words that changed.
 * `counterpart: null` means the other side does not exist (a new or a deleted unit): nothing is marked, the whole column is one block.
 * Blocks that moved inside the document are not reported.
 */
export function compareDocuments(document: NativeDocument, counterpart: NativeDocument | null, side: CompareSide): DocumentChanges {
    if (counterpart === null) return NO_CHANGES;
    const own = new Map(document.root.content.map(node => [node.id, node] as const));
    const other = new Map(counterpart.root.content.map(node => [node.id, node] as const));
    const marks = new Map<string, BlockChange>();
    const diffs = new Map<string, readonly DiffParagraph[]>();

    const mine = side === 'mine' ? document : counterpart;
    const author = side === 'mine' ? counterpart : document;
    const mineBlocks = new Map(blocksOf(mine).map(block => [block.id, block] as const));
    const authorBlocks = new Map(blocksOf(author).map(block => [block.id, block] as const));
    const locale = languageOf(author) ?? languageOf(mine);

    own.forEach((node: NativeNode, id) => {
        const twin = other.get(id);
        if (twin === undefined) { marks.set(id, side === 'mine' ? 'removed' : 'added'); return; }
        if (canonical(node) === canonical(twin)) return;
        marks.set(id, 'changed');
        const before = mineBlocks.get(id);
        const after = authorBlocks.get(id);
        if (before?.kind === 'paragraph' && after?.kind === 'paragraph') {
            const paragraphs = diffLines(before.lines, after.lines, locale);
            if (hasChanges(paragraphs)) diffs.set(id, paragraphs);
        }
    });
    return { marks, diffs };
}
