/**
 * Suggestions for the ORDER editor: they only propose item texts, the author always corrects them by hand. There
 * is no tokenizer of our own: words come from the platform's locale-aware `Intl.Segmenter`, and the editor
 * degrades to line splitting and manual editing where it is missing.
 */

/** Characters that join the two words around them when written without spaces: «e-mail», «don't», «rock’n’roll». */
const CONNECTORS = new Set(['-', '‐', '‑', '\'', '’']);

/** Whether the browser can split text into words. Checked per call so a missing `Intl.Segmenter` never breaks the form. */
export function wordSegmentationAvailable(): boolean {
    return typeof Intl !== 'undefined' && typeof (Intl as { Segmenter?: unknown }).Segmenter === 'function';
}

/**
 * Splits text into word-like parts, or returns null when `Intl.Segmenter` is missing. Punctuation stays with the
 * word before it («Hello,» «world!»); an opening quote or bracket stays with the word after it; a hyphen or
 * apostrophe between two words keeps them together. Scripts without spaces (Chinese, Japanese) are split by the
 * platform dictionary. Whitespace and line breaks only separate parts and are dropped.
 */
export function segmentWords(text: string, locale?: string): readonly string[] | null {
    if (!wordSegmentationAvailable()) return null;
    const segmenter = new Intl.Segmenter(locale, { granularity: 'word' });
    const parts: string[] = [];
    let leading = '';
    let attached = false;   // the previous part may still be extended: nothing but punctuation since its last letter
    let joinNext = false;   // a connector was just appended, the next word belongs to the same part
    for (const { segment, isWordLike } of segmenter.segment(text)) {
        if (isWordLike === true) {
            if (joinNext && parts.length > 0) parts[parts.length - 1] += segment;
            else { parts.push(leading + segment); leading = ''; }
            attached = true;
            joinNext = false;
            continue;
        }
        if (/^\s+$/u.test(segment)) { attached = false; joinNext = false; continue; }
        if (attached && parts.length > 0) {
            parts[parts.length - 1] += segment;
            joinNext = CONNECTORS.has(segment);
        } else {
            leading += segment;
        }
    }
    // Text made only of punctuation or symbols is still text the author typed: keep it as one part, never lose it.
    if (leading !== '') parts.push(leading);
    return parts;
}

/** Splits text into its non-blank lines. Indentation and inner spacing are kept, so code survives. */
export function segmentLines(text: string): readonly string[] {
    return text.split(/\r\n|\r|\n/u).filter(line => line.trim().length > 0);
}
