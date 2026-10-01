import { segmentLines, segmentWords, wordSegmentationAvailable } from './order-segmentation';

describe('ORDER segmentation helpers', () => {
    /** Runs `body` as if the browser had no `Intl.Segmenter`, then restores it. */
    function withoutSegmenter(body: () => void): void {
        const intl = Intl as unknown as { Segmenter?: unknown };
        const original = intl.Segmenter;
        intl.Segmenter = undefined;
        try { body(); } finally { intl.Segmenter = original; }
    }

    describe('words', () => {
        it('keeps punctuation attached to the word it follows and drops only whitespace', () => {
            expect(segmentWords('Это очень, очень важно.')).toEqual(['Это', 'очень,', 'очень', 'важно.']);
            expect(segmentWords('Hello,   world!\nHow are you?')).toEqual(['Hello,', 'world!', 'How', 'are', 'you?']);
            expect(segmentWords('Wait... what?!')).toEqual(['Wait...', 'what?!']);
        });

        it('never loses a character of the original text except whitespace', () => {
            const text = 'Mr. Smith said: "It\'s 3.14 — e-mail me, please!"';
            expect(segmentWords(text)!.join('')).toBe(text.replace(/\s+/gu, ''));
        });

        it('keeps an opening quote with the next word and a hyphen or apostrophe inside one word', () => {
            expect(segmentWords('«Привет», сказал он.')).toEqual(['«Привет»,', 'сказал', 'он.']);
            expect(segmentWords('Send an e-mail, don\'t wait')).toEqual(['Send', 'an', 'e-mail,', 'don\'t', 'wait']);
        });

        it('keeps repeated words as separate parts, in text order', () => {
            expect(segmentWords('очень очень очень')).toEqual(['очень', 'очень', 'очень']);
        });

        it('splits scripts written without spaces into more than one part and keeps the final punctuation', () => {
            const chinese = segmentWords('我喜欢学习中文。', 'zh')!;
            expect(chinese.length).toBeGreaterThan(2);
            expect(chinese.join('')).toBe('我喜欢学习中文。');
            expect(chinese.at(-1)!.endsWith('。')).toBeTrue();
            const japanese = segmentWords('私は日本語を勉強します。', 'ja')!;
            expect(japanese.length).toBeGreaterThan(2);
            expect(japanese.join('')).toBe('私は日本語を勉強します。');
        });

        it('keeps text made only of punctuation as one part instead of dropping it', () => {
            expect(segmentWords('?!')).toEqual(['?!']);
            expect(segmentWords('   ')).toEqual([]);
        });

        it('reports that it cannot split words and returns null, without throwing, when Intl.Segmenter is missing', () => {
            expect(wordSegmentationAvailable()).toBeTrue();
            withoutSegmenter(() => {
                expect(wordSegmentationAvailable()).toBeFalse();
                expect(segmentWords('Это очень важно.')).toBeNull();
            });
            expect(segmentWords('Это важно.')).toEqual(['Это', 'важно.']);
        });
    });

    describe('lines', () => {
        it('splits on any line break, drops blank lines and keeps indentation for code', () => {
            expect(segmentLines('первая\r\n\r\n  вторая\nтретья\r\n')).toEqual(['первая', '  вторая', 'третья']);
            expect(segmentLines('for (;;) {\n    x++;\n}')).toEqual(['for (;;) {', '    x++;', '}']);
        });

        it('returns nothing for blank text', () => { expect(segmentLines(' \n\t\n')).toEqual([]); });
    });
});
