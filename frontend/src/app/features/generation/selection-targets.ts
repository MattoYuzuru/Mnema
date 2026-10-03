import { isMediaKind } from './word-diff';

/** The blocks a selection of the material touches, widened to whole top-level blocks. */
export interface SelectionTarget {
    /** Node ids of the blocks the selection touches, in document order, media blocks inside the run included. */
    readonly nodeIds: readonly string[];
    /** The block just before the run in the document, and the one just after: they bound the run in any later revision. */
    readonly anchorBefore: string | null;
    readonly anchorAfter: string | null;
    /** What is selected, on one line, for the window («Выделено: «…»»). */
    readonly quote: string;
    /** From the start of the first block to the end of the last: what the highlight paints. */
    readonly range: Range;
    /** The selection as the user made it: put back when the window is closed with Esc. */
    readonly selected: Range;
    /** Where the selection ends on the screen (viewport pixels); the group and the window sit under it. */
    readonly rect: { readonly top: number; readonly bottom: number; readonly left: number; readonly right: number };
}

export const QUOTE_LENGTH = 90;
const HIGHLIGHT = 'mnema-ai-target';

/** A text of one line, cut at `QUOTE_LENGTH` characters. */
export function quoteOf(text: string): string {
    const line = text.replace(/\s+/gu, ' ').trim();
    return line.length > QUOTE_LENGTH ? `${line.slice(0, QUOTE_LENGTH - 1).trimEnd()}…` : line;
}

function textBetween(startNode: Node, startOffset: number, endNode: Node, endOffset: number): string {
    const range = document.createRange();
    range.setStart(startNode, startOffset);
    range.setEnd(endNode, endOffset);
    return range.toString().trim();
}

/** Where the selection ends on screen; zeros where the engine has no layout (jsdom). */
export function endRect(range: Range): SelectionTarget['rect'] {
    if (typeof range.getClientRects !== 'function') return { top: 0, bottom: 0, left: 0, right: 0 };
    const rects = [...range.getClientRects()].filter(rect => rect.width > 0 || rect.height > 0);
    const last = rects[rects.length - 1] ?? range.getBoundingClientRect();
    return { top: last.top, bottom: last.bottom, left: last.left, right: last.right };
}

/**
 * Reads the selection as a run of top-level blocks. A block that only the very edge of the selection touches (a triple click ends at
 * the start of the next block) is not part of it. Returns `null` for no selection, a selection that starts outside `host`, one of whitespace
 * only, or one that holds media blocks only (those have their own actions). `order` is the document's top-level block ids.
 */
export function readSelection(host: HTMLElement, selection: Selection | null, order: readonly string[],
                              kinds: ReadonlyMap<string, string>): SelectionTarget | null {
    if (selection === null || selection.rangeCount === 0 || selection.isCollapsed || selection.toString().trim().length === 0) return null;
    const range = selection.getRangeAt(0);
    // A selection starts inside the material. It may end below it: a triple click on the last paragraph ends in whatever follows.
    if (!host.contains(range.startContainer)) return null;
    const blocks = [...host.querySelectorAll<HTMLElement>('.native-document > [data-node-id]')].filter(block => range.intersectsNode(block));
    const first = blocks[0];
    const last = blocks[blocks.length - 1];
    if (first === undefined || last === undefined) return null;
    if (blocks.length > 1 && textBetween(range.startContainer, range.startOffset, first, first.childNodes.length) === '') blocks.shift();
    if (blocks.length > 1 && textBetween(last, 0, range.endContainer, range.endOffset) === '') blocks.pop();
    const nodeIds = blocks.map(block => block.dataset['nodeId']!).filter(id => order.includes(id));
    if (nodeIds.length === 0 || nodeIds.every(id => isMediaKind(kinds.get(id)))) return null;
    const from = order.indexOf(nodeIds[0]!);
    const to = order.indexOf(nodeIds[nodeIds.length - 1]!);
    // The widened run is what the server needs: consecutive blocks, nothing skipped.
    const run = order.slice(from, to + 1);
    const whole = document.createRange();
    whole.setStartBefore(blocks[0]!);
    whole.setEndAfter(blocks[blocks.length - 1]!);
    return { nodeIds: run, anchorBefore: order[from - 1] ?? null, anchorAfter: order[to + 1] ?? null, quote: quoteOf(selection.toString()),
        range: whole, selected: range.cloneRange(), rect: endRect(range) };
}

interface HighlightRegistry { set(name: string, highlight: unknown): unknown; delete(name: string): boolean; }

function registry(): HighlightRegistry | null {
    const css = (globalThis as { CSS?: { highlights?: HighlightRegistry } }).CSS;
    const available = typeof (globalThis as { Highlight?: unknown }).Highlight === 'function';
    return available && css?.highlights !== undefined ? css.highlights : null;
}

/**
 * Paints `range` with the CSS Custom Highlight API (name `mnema-ai-target`, styled in `global_styles.css`). Returns `false` when the
 * browser has none: the caller then marks the blocks with a class. The highlight is not a selection, so it survives focus moving
 * into the window.
 */
export function paintTarget(range: Range): boolean {
    const highlights = registry();
    if (highlights === null) return false;
    const Highlight = (globalThis as unknown as { Highlight: new (...ranges: Range[]) => unknown }).Highlight;
    highlights.set(HIGHLIGHT, new Highlight(range));
    return true;
}

export function clearTarget(): void {
    registry()?.delete(HIGHLIGHT);
}

/** The blocks strictly between the two anchors of a run in `order`; `null` anchors mean the edge of the document, a missing one gives none. */
export function runBetween(order: readonly string[], anchorBefore: string | null, anchorAfter: string | null): readonly string[] {
    const from = anchorBefore === null ? 0 : order.indexOf(anchorBefore) + 1;
    const to = anchorAfter === null ? order.length : order.indexOf(anchorAfter);
    if ((anchorBefore !== null && from === 0) || (anchorAfter !== null && to < 0) || to < from) return [];
    return order.slice(from, to);
}
