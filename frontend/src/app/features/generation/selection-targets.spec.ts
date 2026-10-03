import { documentOf, nativeNode } from '../../content/rendering/native-renderer.fixtures';
import { QUOTE_LENGTH, clearTarget, endRect, paintTarget, quoteOf, readSelection, runBetween } from './selection-targets';

const ids = ['b0', 'b1', 'b2', 'b3', 'b4'];

/** A host shaped like the renderer's output: `.native-document` with one block per id, each with its own text. */
function build(blocks: readonly { id: string; tag: string; text: string }[]): HTMLElement {
    const host = document.createElement('div');
    host.innerHTML = `<article class="native-document">${blocks.map(block => `<${block.tag} data-node-id="${block.id}">${block.text}</${block.tag}>`).join('')}</article>`;
    document.body.appendChild(host);
    return host;
}

const select = (from: Node, fromOffset: number, to: Node, toOffset: number): Selection => {
    const selection = document.getSelection()!;
    const range = document.createRange();
    range.setStart(from, fromOffset);
    range.setEnd(to, toOffset);
    selection.removeAllRanges();
    selection.addRange(range);
    return selection;
};

describe('selection to blocks', () => {
    let host: HTMLElement;
    const text = (id: string): Node => host.querySelector(`[data-node-id="${id}"]`)!.firstChild!;
    const kinds = new Map([['b0', 'heading'], ['b1', 'paragraph'], ['b2', 'paragraph'], ['b3', 'image'], ['b4', 'paragraph']]);

    beforeEach(() => {
        host = build([{ id: 'b0', tag: 'h2', text: 'Заголовок' }, { id: 'b1', tag: 'p', text: 'Первый абзац про глаголы.' },
            { id: 'b2', tag: 'p', text: 'Второй абзац про частицы.' }, { id: 'b3', tag: 'figure', text: 'Подпись' }, { id: 'b4', tag: 'p', text: 'Последний.' }]);
    });
    afterEach(() => { host.remove(); document.getSelection()?.removeAllRanges(); });

    it('widens a selection inside one paragraph to that block, and names its neighbours', () => {
        const target = readSelection(host, select(text('b1'), 7, text('b1'), 12), ids, kinds)!;
        expect(target.nodeIds).toEqual(['b1']);
        expect([target.anchorBefore, target.anchorAfter]).toEqual(['b0', 'b2']);
        expect(target.quote).toBe('абзац');
        expect(target.range.startContainer).toBe(host.querySelector('.native-document'));
    });

    it('takes every block a selection across blocks touches, and the media block between them is part of the run', () => {
        const target = readSelection(host, select(text('b2'), 3, text('b4'), 4), ids, kinds)!;
        expect(target.nodeIds).toEqual(['b2', 'b3', 'b4']);
        expect([target.anchorBefore, target.anchorAfter]).toEqual(['b1', null]);
    });

    it('does not count a block that only the edge of the selection reaches (a triple click ends at the start of the next block)', () => {
        const target = readSelection(host, select(text('b1'), 0, text('b2'), 0), ids, kinds)!;
        expect(target.nodeIds).toEqual(['b1']);
        const backwards = readSelection(host, select(text('b1'), 'Первый абзац про глаголы.'.length, text('b2'), 6), ids, kinds)!;
        expect(backwards.nodeIds).toEqual(['b2']);
    });

    it('takes the last paragraph when the selection runs on below the material (a triple click ends in whatever follows it)', () => {
        const after = document.createElement('p');
        after.textContent = 'История правок';
        host.appendChild(after);
        const target = readSelection(host, select(text('b4'), 0, after.firstChild!, 0), ids, kinds)!;
        expect(target.nodeIds).toEqual(['b4']);
        expect(target.anchorAfter).toBeNull();
        // Starting above the material and ending in it is another gesture: nothing is offered.
        const before = document.createElement('p');
        before.textContent = 'Заголовок страницы';
        host.parentElement!.insertBefore(before, host);
        expect(readSelection(host, select(before.firstChild!, 2, text('b1'), 4), ids, kinds)).toBeNull();
        before.remove();
    });

    it('offers nothing for no selection, a collapsed or blank one, one outside the document, or one that holds media only', () => {
        expect(readSelection(host, null, ids, kinds)).toBeNull();
        const selection = document.getSelection()!;
        selection.removeAllRanges();
        expect(readSelection(host, selection, ids, kinds)).toBeNull();
        expect(readSelection(host, select(text('b1'), 3, text('b1'), 3), ids, kinds)).toBeNull();
        host.querySelector('[data-node-id="b2"]')!.firstChild!.textContent = '   ';
        expect(readSelection(host, select(text('b2'), 0, text('b2'), 3), ids, kinds)).toBeNull();
        const outside = document.createElement('p');
        outside.textContent = 'снаружи';
        document.body.appendChild(outside);
        expect(readSelection(host, select(outside.firstChild!, 0, outside.firstChild!, 4), ids, kinds)).toBeNull();
        outside.remove();
        expect(readSelection(host, select(text('b3'), 0, text('b3'), 4), ids, kinds)).toBeNull();
    });

    it('ignores a block it does not know (not in the document order)', () => {
        expect(readSelection(host, select(text('b1'), 1, text('b1'), 5), ['b0', 'b2'], kinds)).toBeNull();
    });
});

describe('the quote, the run between anchors and the highlight', () => {
    it('cuts a long quote on one line', () => {
        expect(quoteOf('  один\n\nдва   три ')).toBe('один два три');
        const long = quoteOf('слово '.repeat(60));
        expect(long.length).toBeLessThanOrEqual(QUOTE_LENGTH);
        expect(long.endsWith('…')).toBe(true);
    });

    it('finds the blocks strictly between two anchors, at the edges of the document, and none when an anchor is gone', () => {
        const order = ['a', 'b', 'c', 'd', 'e'];
        expect(runBetween(order, 'a', 'd')).toEqual(['b', 'c']);
        expect(runBetween(order, null, 'c')).toEqual(['a', 'b']);
        expect(runBetween(order, 'c', null)).toEqual(['d', 'e']);
        expect(runBetween(order, null, null)).toEqual(order);
        expect(runBetween(order, 'x', 'd')).toEqual([]);
        expect(runBetween(order, 'a', 'x')).toEqual([]);
        expect(runBetween(order, 'd', 'a')).toEqual([]);
    });

    it('has no layout in jsdom: zeros', () => {
        const range = document.createRange();
        expect(endRect(range)).toEqual({ top: 0, bottom: 0, left: 0, right: 0 });
    });

    it('paints with the CSS Custom Highlight API when there is one and says no when there is not', () => {
        const range = document.createRange();
        expect(paintTarget(range)).toBe(false);
        const registry = new Map<string, unknown>();
        class FakeHighlight { constructor(public readonly range: Range) {} }
        vi.stubGlobal('Highlight', FakeHighlight);
        vi.stubGlobal('CSS', { highlights: registry });
        expect(paintTarget(range)).toBe(true);
        expect((registry.get('mnema-ai-target') as FakeHighlight).range).toBe(range);
        clearTarget();
        expect(registry.has('mnema-ai-target')).toBe(false);
        expect(documentOf([nativeNode('paragraph')]).root.content).toHaveLength(1);
    });
});
