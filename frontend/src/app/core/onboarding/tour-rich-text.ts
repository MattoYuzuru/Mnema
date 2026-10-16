import { RichText, TourBody } from './tour.models';

const KEY_SPAN = /<span class="key">(.*?)<\/span>/gu;

/**
 * Reads the approved step texts, where a key word is written `<span class="key">…</span>`, into segments that the template renders
 * as text nodes. Nothing is ever set as HTML and no sanitiser is bypassed: that one tag is the whole markup, and any other `<`
 * stays literal text.
 */
export function richText(source: string): RichText {
    const segments: { text: string; key: boolean }[] = [];
    let last = 0;
    for (const match of source.matchAll(KEY_SPAN)) {
        if (match.index > last) segments.push({ text: source.slice(last, match.index), key: false });
        if (match[1].length > 0) segments.push({ text: match[1], key: true });
        last = match.index + match[0].length;
    }
    if (last < source.length) segments.push({ text: source.slice(last), key: false });
    return segments;
}

/** The plain text of segments, for the accessible name and the live region. */
export function plainText(text: RichText): string {
    return text.map(segment => segment.text).join('');
}

export function paragraphs(...sources: readonly string[]): TourBody {
    return { kind: 'paragraphs', paragraphs: sources.map(richText) };
}

export function slides(...sources: readonly string[]): TourBody {
    return { kind: 'slides', slides: sources.map(richText) };
}
