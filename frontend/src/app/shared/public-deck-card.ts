import { plural } from '../core/notifications/notification-presenter';

/** The audience counts of a public deck stay hidden below this many: a small number would point at a person. */
export const MIN_SHOWN_COUNT = 10;

const FRACTION = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 1 });

const NBSP = '\u00a0';
interface Scale { readonly from: number; readonly short: string; readonly forms: readonly [string, string, string] }
const SCALES: readonly Scale[] = [
    { from: 1_000_000_000, short: 'млрд', forms: ['миллиард', 'миллиарда', 'миллиардов'] },
    { from: 1_000_000, short: 'млн', forms: ['миллион', 'миллиона', 'миллионов'] },
    { from: 1_000, short: 'тыс.', forms: ['тысяча', 'тысячи', 'тысяч'] }
];

/** A count as a card shows it and as a screen reader should hear it. */
export interface AudienceCount {
    /** «1,2 тыс.» with a non-breaking space, for the eye. */
    readonly short: string;
    /** «1,2 тысячи», the full words, for assistive technology (an abbreviation is read letter by letter). */
    readonly spoken: string;
}

/**
 * «Добавили» and «Учат сейчас» of a card. Below {@link MIN_SHOWN_COUNT} nothing is shown (`null`); above it the number
 * is rounded down to two significant digits, so a card never claims more than is true and never shows an exact audience:
 * 10 → «10», 99 → «99», 1234 → «1,2 тыс.», 12 345 → «12 тыс.», 123 456 → «120 тыс.», 2 500 000 → «2,5 млн».
 * The scale word is separated by a non-breaking space; the spoken form declines it («1,2 тысячи», «12 тысяч», «1 тысяча»).
 */
export function audienceCount(count: number): AudienceCount | null {
    if (!Number.isFinite(count) || count < MIN_SHOWN_COUNT) return null;
    const whole = Math.floor(count);
    const step = 10 ** Math.max(0, String(whole).length - 2);
    const rounded = Math.floor(whole / step) * step;
    const scale = SCALES.find(({ from }) => rounded >= from);
    if (scale === undefined) return { short: String(rounded), spoken: String(rounded) };
    const scaled = rounded / scale.from;
    const number = FRACTION.format(scaled);
    // A fraction takes the genitive singular («1,2 тысячи»); a whole number declines as any count.
    const word = Number.isInteger(scaled) ? plural(scaled, scale.forms) : scale.forms[1];
    return { short: `${number}${NBSP}${scale.short}`, spoken: `${number} ${word}` };
}

/** «12 материалов · 34 упражнения». */
export function sizeLine(materials: number, exercises: number): string {
    return `${materials} ${plural(materials, ['материал', 'материала', 'материалов'])} · ${exercises} ${plural(exercises, ['упражнение', 'упражнения', 'упражнений'])}`;
}

/** «Обновлена 3 октября»; the year is added when it is not the current one. An unreadable date gives `null`. */
export function updatedLabel(iso: string, now: Date = new Date()): string | null {
    const time = Date.parse(iso);
    if (Number.isNaN(time)) return null;
    const date = new Date(time);
    const format = new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'long', year: date.getFullYear() === now.getFullYear() ? undefined : 'numeric' });
    return `Обновлена ${format.format(date).replace(/\s*г\.$/u, '')}`;
}

/** What a media badge of a card says. */
export type DeckMedia = 'image' | 'audio' | 'video';
export const MEDIA_LABELS: Readonly<Record<DeckMedia, string>> = { image: 'картинки', audio: 'аудио', video: 'видео' };

/**
 * The public author of a card. `username: null` is a hidden or withdrawn profile (no consent): the chip then draws
 * nothing and the card stays whole.
 */
export interface PublicDeckAuthor {
    readonly username: string | null;
    readonly avatarSrc: string | null;
}

/** What the catalogue card shows. Display model only; the wire shape arrives with the catalogue endpoint. */
export interface PublicDeckCard {
    readonly id: string;
    readonly title: string;
    readonly description: string;
    /** The topic and the language, as they are named for the learner («Языки», «Испанский»). */
    readonly topic: string;
    readonly language: string;
    readonly author: PublicDeckAuthor;
    readonly materialCount: number;
    readonly exerciseCount: number;
    readonly media: readonly DeckMedia[];
    readonly addedCount: number;
    readonly learningNowCount: number;
    readonly updatedAt: string;
    /** The public address `/d/{код}/{slug}`, absolute, for «Поделиться». */
    readonly shareUrl: string;
}
