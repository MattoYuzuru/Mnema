import { AppNotification, NotificationSeverity } from './notification.models';

/** A destination the client derives from the route key; the server never sends a URL. */
export interface NotificationLink {
    readonly label: string;
    readonly commands: readonly string[];
    /** Optional in-page anchor, e.g. the «ИИ-бюджет» block of the profile. */
    readonly fragment?: string;
}

export interface PresentedNotification {
    readonly text: string;
    readonly severity: NotificationSeverity;
    readonly link: NotificationLink | null;
}

type Plural = readonly [one: string, few: string, many: string];

const MATERIALS: Plural = ['материал', 'материала', 'материалов'];
const EXERCISES: Plural = ['упражнение', 'упражнения', 'упражнений'];
const NBSP = ' ';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u;

/** Russian plural form for a count (1 материал, 2 материала, 5 материалов, 11 материалов, 21 материал). */
export function plural(count: number, forms: Plural): string {
    const tens = count % 100;
    const last = count % 10;
    if (tens >= 11 && tens <= 14) return forms[2];
    if (last === 1) return forms[0];
    return last >= 2 && last <= 4 ? forms[1] : forms[2];
}

const BUCKETS: Readonly<Record<string, string>> = {
    CREDITS: 'Кредиты ИИ', STT: 'Распознавание речи', ASSESSMENT: 'Проверка ответов ИИ', PODCASTS: 'Подкасты',
    QUALITY_IMAGES: 'Качественные изображения', HIGH_FACTCHECK: 'Усиленная проверка фактов', SMART_PLAN: 'Умный план'
};
const UNITS: Readonly<Record<string, Plural>> = {
    CREDITS: ['кредит', 'кредита', 'кредитов'], MINUTES: ['минута', 'минуты', 'минут'],
    COUNT: ['использование', 'использования', 'использований']
};
const MEDIA: Readonly<Record<string, string>> = { IMAGE: 'изображение', AUDIO: 'аудио', VIDEO: 'видео' };
const GENERATION_FAILURES: Readonly<Record<string, string>> = {
    PROVIDER_UNAVAILABLE: 'ИИ-сервис сейчас недоступен, попробуйте позже',
    USAGE_LIMIT: 'не хватило лимита ИИ',
    ESTIMATE_EXCEEDED: 'запрос вышел за оценку расхода, отправьте его ещё раз',
    INVALID_OUTPUT: 'ИИ не справился с заданием, переформулируйте запрос',
    REFUSAL: 'ИИ отказался выполнять запрос, измените формулировку',
    SOURCE_UNAVAILABLE: 'исходный материал или заметка удалены',
    DEADLINE_EXCEEDED: 'ответ занял слишком много времени, попробуйте ещё раз',
    PLAN_FAILED: 'не удалось составить план, попробуйте ещё раз'
};

/**
 * Builds the sentence and destination of a notification from `kind + params` on the client. Returns `null` for an
 * unknown kind or params that do not fit the contract: such a notification shows nothing, yet still counts as unread.
 * The assistant is «Мнема»; the label of generated output is «ИИ». Every text names the outcome, so severity is never
 * the only signal.
 */
export function presentNotification(notification: AppNotification): PresentedNotification | null {
    const text = sentence(notification);
    return text === null ? null : { text, severity: notification.severity, link: routeLink(notification) };
}

function sentence(notification: AppNotification): string | null {
    const params = notification.params;
    switch (notification.kind) {
        case 'GENERATION_PLAN_READY': {
            const planned = integer(params, 'plannedCount');
            const noun = artifactNoun(params);
            return planned === null || noun === null ? null
                : `Мнема составила план: ${planned} ${plural(planned, noun)}. Проверьте его и запустите создание`;
        }
        case 'GENERATION_READY': {
            const ready = integer(params, 'artifactCount');
            const noun = artifactNoun(params);
            return ready === null || noun === null ? null
                : `Готово: ${ready} ${plural(ready, noun)} ${ready % 10 === 1 && ready % 100 !== 11 ? 'ждёт' : 'ждут'} проверки`;
        }
        case 'GENERATION_PARTIAL': {
            const approvable = integer(params, 'approvableCount');
            const failed = integer(params, 'failedCount');
            const noun = artifactNoun(params);
            return approvable === null || failed === null || noun === null ? null
                : `Готово частично: можно одобрить ${approvable} ${plural(approvable, noun)}, не получилось — ${failed}`;
        }
        case 'GENERATION_FAILED': {
            const code = text(params, 'errorCode');
            return `Создание не удалось — ${(code !== null ? GENERATION_FAILURES[code] : undefined) ??
                'откройте колоду и попробуйте ещё раз'}`;
        }
        case 'USAGE_LOW': return usageLow(params);
        case 'USAGE_EXHAUSTED': return usageExhausted(params);
        case 'GENERATION_SESSION_EXPIRING': {
            const pending = integer(params, 'pendingCount');
            const expires = day(params['expiresAt']);
            return pending === null || expires === null ? null
                : `Скоро удалятся черновики ИИ (${pending}). Опубликуйте нужное до ${expires}`;
        }
        case 'MEDIA_PROCESSING_FAILED': return mediaFailed(params);
        default: return null;
    }
}

function usageLow(params: Readonly<Record<string, unknown>>): string | null {
    const percent = integer(params, 'percent');
    const remaining = integer(params, 'remaining');
    const unit = text(params, 'unit');
    if (percent === null || remaining === null || unit === null || !(unit in UNITS)) return null;
    const renews = day(params['renewsAt']);
    return `Лимит «${bucket(params)}» использован на ${percent}${NBSP}%. ` +
        `Остаток: ${remaining} ${plural(remaining, UNITS[unit])}${renews === null ? '' : `, обновление ${renews}`}`;
}

function usageExhausted(params: Readonly<Record<string, unknown>>): string | null {
    const renews = day(params['renewsAt']);
    return `Лимит «${bucket(params)}» закончился. ` +
        (renews === null ? 'Подождать не поможет — смените тариф, чтобы продолжить' : `Он откроется снова ${renews}`);
}

function mediaFailed(params: Readonly<Record<string, unknown>>): string | null {
    const kind = MEDIA[text(params, 'mediaKind') ?? ''];
    if (kind === undefined) return null;
    switch (text(params, 'reason')) {
        case 'VERIFICATION_REJECTED': return `Не удалось принять ${kind}: файл не прошёл проверку. Загрузите другой файл`;
        case 'PROCESSING_FAILED': return `Не удалось обработать ${kind} — откройте материал и загрузите файл ещё раз`;
        case 'NO_RESULT': return `Не удалось подобрать ${kind} — добавьте его вручную`;
        case 'PROVIDER_UNAVAILABLE':
        case 'DEADLINE_EXCEEDED': return `Не удалось подготовить ${kind} — повторите попытку позже`;
        default: return null;
    }
}

/**
 * Route key to a client destination. WORKSHOP opens the Workshop of `sessionId` (`/decks/:deckId/workshop/:sessionId`); a
 * WORKSHOP notification without a usable session falls back to the deck page. PLANS has no plans page yet (paywall:
 * AI-19 #301), so it opens the «ИИ-бюджет» block of the profile (`/profile#ai-budget`); NONE stays a plain sentence.
 */
function routeLink(notification: AppNotification): NotificationLink | null {
    if (notification.route === 'PLANS') return { label: 'Открыть ИИ-бюджет', commands: ['/profile'], fragment: 'ai-budget' };
    if (notification.route !== 'WORKSHOP' && notification.route !== 'DECK') return null;
    const deckId = notification.params['deckId'];
    if (typeof deckId !== 'string' || !UUID.test(deckId)) return null;
    const sessionId = notification.params['sessionId'];
    if (notification.route === 'WORKSHOP' && typeof sessionId === 'string' && UUID.test(sessionId)) {
        return { label: 'Открыть мастерскую', commands: ['/decks', deckId, 'workshop', sessionId] };
    }
    return { label: 'Открыть колоду', commands: ['/decks', deckId] };
}

function artifactNoun(params: Readonly<Record<string, unknown>>): Plural | null {
    switch (text(params, 'sessionKind')) {
        case 'MATERIALS':
        case 'REVISE_ITEM': return MATERIALS;
        case 'EXERCISES':
        case 'REVISE_EXERCISE': return EXERCISES;
        default: return null;
    }
}

function bucket(params: Readonly<Record<string, unknown>>): string { return BUCKETS[text(params, 'bucket') ?? ''] ?? 'ИИ'; }

function text(params: Readonly<Record<string, unknown>>, key: string): string | null {
    const value = params[key];
    return typeof value === 'string' && value.length > 0 && value.length <= 64 ? value : null;
}

function integer(params: Readonly<Record<string, unknown>>, key: string): number | null {
    const value = params[key];
    return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : null;
}

/** «31 октября» in the reader's time zone; `null` for an absent or malformed instant. */
function day(value: unknown): string | null {
    if (typeof value !== 'string') return null;
    const time = Date.parse(value);
    return Number.isNaN(time) ? null : new Intl.DateTimeFormat('ru', { day: 'numeric', month: 'long' }).format(time);
}
