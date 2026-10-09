import { boundedText } from './auth-protocol';

/** Bio is plain multiline text, unlike the single-line identity/protocol fields. */
export function isProfileBio(value: unknown): value is string {
    return typeof value === 'string' && value.length <= 200 &&
        boundedText(value.replace(/[\t\n\r]/gu, ''), 200);
}

/** Preserve paragraphs, but do not let whitespace create an oversized profile layout. */
export function normalizeProfileBio(value: string): string {
    return value.replace(/\r\n?/gu, '\n').split('\n')
        .map(line => line.replace(/[\t\p{Zs}]+/gu, ' ').replace(/^ +| +$/gu, ''))
        .join('\n').replace(/^\n+|\n+$/gu, '').replace(/\n{3,}/gu, '\n\n');
}

export function validProfileBioEdit(value: string): boolean {
    return isProfileBio(value) && normalizeProfileBio(value).split('\n').length <= 6;
}
