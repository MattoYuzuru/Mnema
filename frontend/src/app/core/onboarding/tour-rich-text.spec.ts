import { paragraphs, plainText, richText, slides } from './tour-rich-text';

describe('tour rich text', () => {
    it('reads the key spans of the approved texts into segments', () => {
        expect(richText('Здесь название, описание и все <span class="key">материалы</span> колоды.')).toEqual([
            { text: 'Здесь название, описание и все ', key: false },
            { text: 'материалы', key: true },
            { text: ' колоды.', key: false }
        ]);
    });

    it('reads several key words and a key word at either end', () => {
        expect(richText('<span class="key">ИИ</span> и <span class="key">Мастерская</span>')).toEqual([
            { text: 'ИИ', key: true }, { text: ' и ', key: false }, { text: 'Мастерская', key: true }
        ]);
    });

    it('keeps every other piece of markup as literal text: nothing is ever an element', () => {
        const hostile = '<img src=x onerror=alert(1)> <span class="key" onclick="x()">a</span> <script>b</script>';
        const segments = richText(hostile);
        expect(plainText(segments)).toBe(hostile);
        expect(segments.every(segment => !segment.key)).toBe(true);
    });

    it('drops an empty key span instead of drawing an empty highlight', () => {
        expect(richText('a<span class="key"></span>b')).toEqual([{ text: 'a', key: false }, { text: 'b', key: false }]);
    });

    it('has no segments for an empty text and one plain segment for plain text', () => {
        expect(richText('')).toEqual([]);
        expect(richText('Привет')).toEqual([{ text: 'Привет', key: false }]);
    });

    it('builds paragraphs and slides bodies', () => {
        expect(paragraphs('a', 'b')).toEqual({ kind: 'paragraphs', paragraphs: [[{ text: 'a', key: false }], [{ text: 'b', key: false }]] });
        const body = slides('x <span class="key">y</span>', 'z');
        expect(body.kind).toBe('slides');
        expect(body.kind === 'slides' && plainText(body.slides[0])).toBe('x y');
    });
});
