import { documentOf, nativeNode } from '../../content/rendering/native-renderer.fixtures';
import { blocksOf, diffLines, hasChanges, isMediaKind } from './word-diff';

const paragraph = (text: string, id?: string) => nativeNode('paragraph', {}, [nativeNode('text', { text, marks: [] })], { id });
const kinds = (segments: readonly { kind: string; text: string }[]) => segments.map(segment => `${segment.kind}:${segment.text}`);

describe('plain text of the top-level blocks', () => {
    it('reads paragraphs, headings, lists, quotes, code, tables and titles as lines, and media as none', () => {
        const document = documentOf([
            nativeNode('heading', { level: 2 }, [nativeNode('text', { text: 'Заголовок', marks: ['strong'] })]),
            nativeNode('paragraph', {}, [nativeNode('text', { text: 'Слово ', marks: [] }), nativeNode('ruby', { base: '漢字', reading: 'かんじ' }),
                nativeNode('link', { href: 'https://example.test/' }, [nativeNode('text', { text: ' ссылка', marks: ['em'] })])]),
            nativeNode('bullet_list', {}, [nativeNode('list_item', {}, [paragraph('первый')]), nativeNode('list_item', {}, [paragraph('второй')])]),
            nativeNode('ordered_list', { order: 3 }, [nativeNode('list_item', {}, [paragraph('третий')])]),
            nativeNode('blockquote', {}, [paragraph('цитата')]),
            nativeNode('code_block', { lang: 'sql', source: 'select 1;' }),
            nativeNode('table', { caption: 'Подпись', columns: ['A', 'B'], rows: [['1', '2']] }),
            nativeNode('image', { assetId: '31901995-16ea-4f8b-8301-5d8e03004c72', alt: 'схема' }),
            nativeNode('divider')
        ]);
        const blocks = blocksOf(document);
        expect(blocks.map(block => block.kind)).toEqual(['heading', 'paragraph', 'bullet-list', 'ordered-list', 'blockquote', 'code-block', 'table', 'image', 'divider']);
        expect(blocks[0]!.lines).toEqual(['Заголовок']);
        expect(blocks[1]!.lines).toEqual(['Слово 漢字 ссылка']);
        expect(blocks[2]!.lines).toEqual(['• первый', '• второй']);
        expect(blocks[3]!.lines).toEqual(['3. третий']);
        expect(blocks[4]!.lines).toEqual(['цитата']);
        expect(blocks[5]!.lines).toEqual(['select 1;']);
        expect(blocks[6]!.lines).toEqual(['Подпись', 'A | B', '1 | 2']);
        expect(blocks[7]!.lines).toEqual([]);
        expect(blocks[8]!.lines).toEqual([]);
        expect(blocks.map(block => block.id)).toEqual(document.root.content.map(node => node.id));
        expect(blocks.filter(block => isMediaKind(block.kind)).map(block => block.kind)).toEqual(['image']);
        expect(isMediaKind(undefined)).toBe(false);
    });

    it('is empty for a document the renderer refuses', () => {
        expect(blocksOf({ formatVersion: 1, root: nativeNode('paragraph') } as never)).toEqual([]);
    });
});

describe('the word diff', () => {
    it('says nothing changed for the same text', () => {
        const paragraphs = diffLines(['Один два три.', 'Четыре.'], ['Один два три.', 'Четыре.']);
        expect(hasChanges(paragraphs)).toBe(false);
        expect(paragraphs.map(entry => kinds(entry.segments))).toEqual([['same:Один два три.'], ['same:Четыре.']]);
    });

    it('shows an added sentence as one insertion and a replaced word as a deletion and an insertion', () => {
        const added = diffLines(['Первый абзац.'], ['Первый абзац. Переписано: Проще.']);
        expect(added).toHaveLength(1);
        expect(kinds(added[0]!.segments)).toEqual(['same:Первый абзац. ', 'ins:Переписано: Проще.']);
        const replaced = diffLines(['Кошка сидит на окне.'], ['Кошка лежит на окне.']);
        expect(kinds(replaced[0]!.segments)).toEqual(['same:Кошка ', 'del:сидит ', 'ins:лежит ', 'same:на окне.']);
        expect(hasChanges(replaced)).toBe(true);
    });

    it('shows a removed tail, an emptied text and a text that appeared from nothing', () => {
        expect(kinds(diffLines(['а б в'], ['а'])[0]!.segments)).toEqual(['same:а ', 'del:б в']);
        expect(kinds(diffLines(['а б'], [])[0]!.segments)).toEqual(['del:а б']);
        expect(kinds(diffLines([], ['а б'])[0]!.segments)).toEqual(['ins:а б']);
        expect(diffLines([], [])).toEqual([]);
    });

    it('keeps paragraph breaks: a split gives two paragraphs, a merge one, and a break that went is not drawn', () => {
        const split = diffLines(['Раз два три'], ['Раз два', 'три']);
        expect(split.map(entry => kinds(entry.segments))).toEqual([['same:Раз два'], ['same:три']]);
        const merged = diffLines(['Раз два', 'три'], ['Раз два три']);
        expect(merged).toHaveLength(1);
        expect(merged[0]!.segments.every(segment => segment.kind === 'same')).toBe(true);
    });

    it('falls back to «all replaced» when the exact diff would be too large, and still reads in order', () => {
        const long = Array.from({ length: 2100 }, (_, index) => `слово${index}`).join(' ');
        const other = Array.from({ length: 2100 }, (_, index) => `иное${index}`).join(' ');
        const paragraphs = diffLines([long], [other]);
        expect(paragraphs).toHaveLength(1);
        expect(paragraphs[0]!.segments.map(segment => segment.kind)).toEqual(['del', 'ins']);
    });
});
