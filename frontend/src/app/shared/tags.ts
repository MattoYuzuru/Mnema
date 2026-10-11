/** The tag rules of publication metadata (contracts/decks/publication.json): the server applies the same normalization. */
export const MAX_TAGS = 5;
export const TAG_MAX_CODE_POINTS = 32;

/** The server's pattern `[\p{L}\p{M}\p{N} _+#.-]+` (PublicationMetadata): letters, combining marks, digits, space and _ + # . - */
const TAG_ALPHABET = /^[\p{L}\p{M}\p{N} _+#.-]+$/u;

export type TagProblem = 'empty' | 'long' | 'chars' | 'duplicate' | 'full';

/** Trim, NFKC, lowercase, inner whitespace collapsed to one space: the form the server stores and compares. */
export function normalizeTag(raw: string): string {
    return raw.normalize('NFKC').trim().toLowerCase().replace(/\s+/gu, ' ');
}

/** `null` when {@link tag} (already normalized) may be added to {@link existing}. */
export function tagProblem(tag: string, existing: readonly string[], max = MAX_TAGS): TagProblem | null {
    if (tag.length === 0) return 'empty';
    if (existing.length >= max) return 'full';
    if (Array.from(tag).length > TAG_MAX_CODE_POINTS) return 'long';
    if (!TAG_ALPHABET.test(tag)) return 'chars';
    if (existing.includes(tag)) return 'duplicate';
    return null;
}

export const TAG_PROBLEM_TEXT: Readonly<Record<TagProblem, string>> = {
    empty: 'Введите тег.',
    long: `Тег длиннее ${TAG_MAX_CODE_POINTS} символов.`,
    chars: 'В теге можно использовать буквы, цифры, пробел и знаки - _ + # .',
    duplicate: 'Такой тег уже есть.',
    full: `Можно добавить не больше ${MAX_TAGS} тегов.`
};
