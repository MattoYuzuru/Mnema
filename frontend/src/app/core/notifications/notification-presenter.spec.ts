import { AppNotification } from './notification.models';
import { plural, presentNotification } from './notification-presenter';

const DECK = '11111111-1111-4111-8111-111111111111';
const make = (kind: string, params: Record<string, unknown>, extra: Partial<AppNotification> = {}): AppNotification => ({
    notificationId: '0a000000-0000-4000-8000-000000000001', seq: '1', kind, severity: 'INFO', params, route: 'NONE',
    createdAt: '2026-10-02T09:00:00Z', expiresAt: '2026-11-01T09:00:00Z', ...extra
});
const text = (notification: AppNotification): string | undefined => presentNotification(notification)?.text;

describe('notification presenter', () => {
    it('declines Russian plurals', () => {
        expect(([1, 2, 5, 11, 12, 14, 21, 22, 25, 101, 111] as const).map(n => plural(n, ['материал', 'материала', 'материалов'])))
            .toEqual(['материал', 'материала', 'материалов', 'материалов', 'материалов', 'материалов', 'материал', 'материала',
                'материалов', 'материал', 'материалов']);
    });

    it('builds generation sentences from kind and params', () => {
        const ready = (artifactCount: number, sessionKind = 'MATERIALS'): string | undefined =>
            text(make('GENERATION_READY', { deckId: DECK, sessionId: DECK, sessionKind, artifactCount, approvableCount: artifactCount }));
        expect(ready(9)).toBe('Готово: 9 материалов ждут проверки');
        expect(ready(1)).toBe('Готово: 1 материал ждёт проверки');
        expect(ready(21)).toBe('Готово: 21 материал ждёт проверки');
        expect(ready(11)).toBe('Готово: 11 материалов ждут проверки');
        expect(ready(3, 'EXERCISES')).toBe('Готово: 3 упражнения ждут проверки');
        expect(text(make('GENERATION_PLAN_READY', { sessionKind: 'EXERCISES', plannedCount: 12 })))
            .toBe('Мнема составила план: 12 упражнений. Проверьте его и запустите создание');
        expect(text(make('GENERATION_PARTIAL', { sessionKind: 'MATERIALS', approvableCount: 7, failedCount: 1 })))
            .toBe('Готово частично: можно одобрить 7 материалов, не получилось — 1');
    });

    it('names the reason of a failed generation and falls back for an unknown code', () => {
        const failed = (errorCode: string): string | undefined => text(make('GENERATION_FAILED', { sessionKind: 'MATERIALS', errorCode }));
        expect(failed('PROVIDER_UNAVAILABLE')).toBe('Создание не удалось — ИИ-сервис сейчас недоступен, попробуйте позже');
        expect(failed('USAGE_LIMIT')).toBe('Создание не удалось — не хватило лимита ИИ');
        expect(failed('SOMETHING_ELSE')).toBe('Создание не удалось — откройте колоду и попробуйте ещё раз');
        expect(text(make('GENERATION_FAILED', {}))).toBe('Создание не удалось — откройте колоду и попробуйте ещё раз');
    });

    it('formats usage and expiry with plurals and a localized day', () => {
        const nbsp = '\u00A0';
        expect(text(make('USAGE_LOW', { bucket: 'CREDITS', percent: 82, unit: 'CREDITS', remaining: 65, renewsAt: '2026-10-31T12:00:00Z', plan: 'PLUS' })))
            .toBe(`Лимит «Кредиты ИИ» использован на 82${nbsp}%. Остаток: 65 кредитов, обновление 31 октября`);
        expect(text(make('USAGE_LOW', { bucket: 'STT', percent: 90, unit: 'MINUTES', remaining: 1, renewsAt: null, plan: 'FREE' })))
            .toBe(`Лимит «Распознавание речи» использован на 90${nbsp}%. Остаток: 1 минута`);
        expect(text(make('USAGE_EXHAUSTED', { bucket: 'PODCASTS', window: 'WEEK', renewsAt: '2026-10-04T12:00:00Z', plan: 'FREE' })))
            .toBe('Лимит «Подкасты» закончился. Он откроется снова 4 октября');
        expect(text(make('USAGE_EXHAUSTED', { bucket: 'CREDITS', window: 'MONTH', renewsAt: null, plan: 'FREE' })))
            .toBe('Лимит «Кредиты ИИ» закончился. Подождать не поможет — смените тариф, чтобы продолжить');
        expect(text(make('GENERATION_SESSION_EXPIRING', { deckId: DECK, sessionId: DECK, expiresAt: '2026-11-01T12:00:00Z', pendingCount: 4 })))
            .toBe('Скоро удалятся черновики ИИ (4). Опубликуйте нужное до 1 ноября');
    });

    it('names the outcome of a media failure', () => {
        const media = (mediaKind: string, reason: string): string | undefined => text(make('MEDIA_PROCESSING_FAILED', { mediaKind, reason }));
        expect(media('AUDIO', 'PROCESSING_FAILED')).toBe('Не удалось обработать аудио — откройте материал и загрузите файл ещё раз');
        expect(media('IMAGE', 'VERIFICATION_REJECTED')).toBe('Не удалось принять изображение: файл не прошёл проверку. Загрузите другой файл');
        expect(media('VIDEO', 'NO_RESULT')).toBe('Не удалось подобрать видео — добавьте его вручную');
        expect(media('IMAGE', 'PROVIDER_UNAVAILABLE')).toBe('Не удалось подготовить изображение — повторите попытку позже');
        expect(media('PDF', 'PROCESSING_FAILED')).toBeUndefined();
        expect(media('AUDIO', 'MYSTERY')).toBeUndefined();
    });

    it('shows nothing for an unknown kind or params that do not fit', () => {
        expect(presentNotification(make('FROM_THE_FUTURE', { a: 1 }))).toBeNull();
        expect(presentNotification(make('GENERATION_READY', { sessionKind: 'MATERIALS', artifactCount: '5' }))).toBeNull();
        expect(presentNotification(make('GENERATION_READY', { sessionKind: 'OTHER', artifactCount: 5 }))).toBeNull();
        expect(presentNotification(make('USAGE_LOW', { percent: 80, unit: 'FURLONGS', remaining: 1 }))).toBeNull();
        expect(presentNotification(make('GENERATION_SESSION_EXPIRING', { expiresAt: 'soon', pendingCount: 1 }))).toBeNull();
    });

    it('maps route keys to client destinations only when they are routable', () => {
        const link = (route: AppNotification['route'], params: Record<string, unknown>): unknown =>
            presentNotification(make('GENERATION_READY', { sessionKind: 'MATERIALS', artifactCount: 2, ...params }, { route }))?.link;
        expect(link('DECK', { deckId: DECK })).toEqual({ label: 'Открыть колоду', commands: ['/decks', DECK] });
        // WORKSHOP opens the deck page until the workshop route exists (AI-06 #289).
        expect(link('WORKSHOP', { deckId: DECK, sessionId: DECK })).toEqual({ label: 'Открыть колоду', commands: ['/decks', DECK] });
        // PLANS has no plans page yet: it opens the «ИИ-бюджет» block of the profile.
        expect(link('PLANS', { deckId: DECK })).toEqual({ label: 'Открыть ИИ-бюджет', commands: ['/profile'], fragment: 'ai-budget' });
        expect(link('NONE', { deckId: DECK })).toBeNull();
        expect(link('DECK', { deckId: '../x' })).toBeNull();
        expect(link('DECK', {})).toBeNull();
    });
});
