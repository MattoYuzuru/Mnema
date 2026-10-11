import { documentOf, nativeNode } from '../content/rendering/native-renderer.fixtures';
import { compareDocuments } from './document-changes';
import { AUTHOR, MINE, divider, nodeId, paragraph } from './update-test-data';

describe('compareDocuments', () => {
    it('marks an edited paragraph as changed on both sides, with the word diff', () => {
        const mine = compareDocuments(MINE, AUTHOR, 'mine');
        const author = compareDocuments(AUTHOR, MINE, 'author');
        expect(mine.marks.get(nodeId(1))).toBe('changed');
        expect(author.marks.get(nodeId(1))).toBe('changed');
        const segments = author.diffs.get(nodeId(1))![0]!.segments;
        expect(segments.some(segment => segment.kind === 'ins' && segment.text.includes('устойчивые'))).toBe(true);
        expect(mine.diffs.get(nodeId(1))).toEqual(author.diffs.get(nodeId(1)));
    });

    it('leaves an untouched block alone', () => {
        expect(compareDocuments(MINE, AUTHOR, 'mine').marks.has(nodeId(2))).toBe(false);
        expect(compareDocuments(AUTHOR, MINE, 'author').marks.has(nodeId(2))).toBe(false);
    });

    it('calls a block only the author has «added» and a block only the reader has «removed»', () => {
        expect(compareDocuments(AUTHOR, MINE, 'author').marks.get(nodeId(4))).toBe('added');
        expect(compareDocuments(MINE, AUTHOR, 'mine').marks.get(nodeId(3))).toBe('removed');
        expect(compareDocuments(AUTHOR, MINE, 'author').diffs.has(nodeId(4))).toBe(false);
    });

    it('marks a changed non-text block without a word diff', () => {
        const before = documentOf([nativeNode('code_block', { lang: 'sql', source: 'select 1;' }, [], { id: nodeId(5) })]);
        const after = documentOf([nativeNode('code_block', { lang: 'sql', source: 'select 2;' }, [], { id: nodeId(5) })]);
        const result = compareDocuments(before, after, 'mine');
        expect(result.marks.get(nodeId(5))).toBe('changed');
        expect(result.diffs.size).toBe(0);
    });

    it('marks a paragraph whose marks changed but whose words did not, without a diff', () => {
        const plain = documentOf([paragraph(6, 'Слово')]);
        const bold = documentOf([nativeNode('paragraph', {}, [nativeNode('text', { text: 'Слово', marks: ['strong'] }, [], { id: nodeId(1006) })], { id: nodeId(6) })]);
        const result = compareDocuments(plain, bold, 'mine');
        expect(result.marks.get(nodeId(6))).toBe('changed');
        expect(result.diffs.size).toBe(0);
    });

    it('does not depend on the order of keys, and marks nothing without a counterpart', () => {
        const left = documentOf([nativeNode('table', { caption: 'T', columns: ['A'], rows: [['1']] }, [], { id: nodeId(7) })]);
        const right = documentOf([nativeNode('table', { rows: [['1']], columns: ['A'], caption: 'T' }, [], { id: nodeId(7) })]);
        expect(compareDocuments(left, right, 'mine').marks.size).toBe(0);
        expect(compareDocuments(MINE, null, 'mine').marks.size).toBe(0);
        expect(compareDocuments(documentOf([divider(8)]), null, 'author').diffs.size).toBe(0);
    });
});
