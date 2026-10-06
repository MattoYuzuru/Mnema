import { insertTranscript, selectionOf, withTranscript } from './insert-transcript';

describe('withTranscript', () => {
    it('puts the text at the caret with a space on each side where the neighbours are not separated', () => {
        expect(withTranscript('Hello world', { start: 5, end: 5 }, 'big')).toEqual({ value: 'Hello big world', caret: 9, inserted: ' big' });
        expect(withTranscript('Hello ', { start: 6, end: 6 }, 'big')).toMatchObject({ value: 'Hello big', caret: 9 });
        expect(withTranscript('', { start: 0, end: 0 }, 'big')).toMatchObject({ value: 'big', caret: 3 });
    });

    it('replaces the selection and appends after the text when the caret is at the end', () => {
        expect(withTranscript('one two three', { start: 4, end: 7 }, 'ZZ').value).toBe('one ZZ three');
        expect(withTranscript('abc', { start: 3, end: 3 }, 'def').value).toBe('abc def');
    });

    it('stays within maxLength', () => {
        const result = withTranscript('abc', { start: 3, end: 3 }, 'defghij', 8);
        expect(result.value.length).toBeLessThanOrEqual(8);
        expect(result.value.startsWith('abc def')).toBe(true);
        expect(withTranscript('abcdefgh', { start: 8, end: 8 }, 'x', 8).value).toBe('abcdefgh');
    });
});

describe('insertTranscript', () => {
    it('sets the value, tells listeners, focuses the field and leaves the caret after the text', () => {
        const area = document.createElement('textarea');
        document.body.append(area);
        area.value = 'Start end';
        area.setSelectionRange(5, 5);
        const heard: string[] = [];
        area.addEventListener('input', () => heard.push(area.value));
        const inserted = insertTranscript(area, selectionOf(area), 'middle');
        expect(inserted).toBe('middle');
        expect(area.value).toBe('Start middle end');
        expect(heard).toEqual(['Start middle end']);
        expect(document.activeElement).toBe(area);
        expect(area.selectionStart).toBe(12);
        expect(area.selectionEnd).toBe(12);
        area.remove();
    });

    it('respects the maxlength attribute of the field', () => {
        const area = document.createElement('textarea');
        area.maxLength = 10;
        area.value = 'abcd';
        insertTranscript(area, { start: 4, end: 4 }, 'efghijklmn');
        expect(area.value.length).toBeLessThanOrEqual(10);
    });
});
