import { HttpErrorResponse } from '@angular/common/http';
import { AdminProtocolError, DeliveryState, TicketStatus, day } from './admin.models';
export const ticketStatuses: Readonly<Record<TicketStatus, string>> = {
    open: 'Новое',
    working: 'В работе',
    waiting: 'Ждём пользователя',
    closed: 'Решено'
};
export const ticketCategories: Readonly<Record<string, string>> = {
    bug: 'Ошибка',
    idea: 'Идея',
    question: 'Вопрос',
    other: 'Другое'
};
export const deliveryNames: Readonly<Record<DeliveryState, string>> = {
    queued: 'В очереди',
    sending: 'Отправляется',
    sent: 'Принято Telegram',
    failed: 'Не отправлено',
    uncertain: 'Доставка неизвестна'
};
export const operationNames: Readonly<Record<string, string>> = {
    TEXT: 'Генерация текста',
    ASSESS: 'ИИ-проверка ответа',
    IMAGE_SEARCH: 'Подбор изображений',
    VIDEO: 'Видео',
    SEARCH: 'Поиск в интернете',
    ASSESSMENT: 'Проверка ответов',
    PODCASTS: 'Подкасты',
    QUALITY_IMAGES: 'Качественные изображения',
    HIGH_FACTCHECK: 'Проверка фактов',
    MATERIAL_SHORT: 'Краткие материалы',
    MATERIAL_MEDIUM: 'Обычные материалы',
    MATERIAL_LONG: 'Подробные материалы',
    EXERCISE: 'Упражнения',
    EXERCISES: 'Упражнения',
    SMART_PLAN: 'Планы обучения',
    FACT_CHECK: 'Проверка фактов',
    IMAGE: 'Изображения',
    QUALITY_IMAGE: 'Качественные изображения',
    TTS: 'Озвучка',
    STT: 'Распознавание речи',
    PODCAST: 'Подкасты',
    ANSWER_CHECK: 'Проверка ответов'
};
export const operationName = (value: string): string => operationNames[value] ?? value;
export const dateTime = (value: string | null): string => value === null ? 'Нет данных' : new Intl.DateTimeFormat('ru-RU', {
    dateStyle: 'medium',
    timeStyle: 'short',
    timeZone: 'Europe/Moscow'
}).format(new Date(value));
export const moneyUsd = (micros: number): string => new Intl.NumberFormat('ru-RU', {
    style: 'currency',
    currency: 'USD',
    minimumFractionDigits: 2,
    maximumFractionDigits: 4
}).format(micros / 1000000);
export const number = (value: number | null): string => value === null ? 'Нет данных' : new Intl.NumberFormat('ru-RU', {
    maximumFractionDigits: 2
}).format(value);
export const percent = (value: number | null): string => value === null ? 'Нет данных' : new Intl.NumberFormat('ru-RU', {
    style: 'percent',
    maximumFractionDigits: 1
}).format(value);
export const bytes = (value: number): string => `${number(value / 1024 / 1024)} МиБ`;
export function unknownOutcome(error: unknown): boolean {
    return error instanceof AdminProtocolError || !(error instanceof HttpErrorResponse) || error.status === 0 || error.status >= 500 || error.status >= 200 && error.status < 300;
}

export function isForbidden(error: unknown): boolean {
    return error instanceof HttpErrorResponse && (error.status === 401 || error.status === 403);
}

export function errorText(error: unknown, fallback: string): string {
    return isForbidden(error) ? 'Доступ закрыт. Обновите страницу, чтобы проверить права.' : error instanceof HttpErrorResponse && error.status === 429 ? 'Слишком много запросов. Подождите и повторите.' : fallback;
}

export function todayUtc(): string {
    return new Date().toISOString().slice(0, 10);
}

export function addDays(value: string, days: number): string {
    day(value);
    const d = new Date(`${value}T00:00:00Z`);
    d.setUTCDate(d.getUTCDate() + days);
    return d.toISOString().slice(0, 10);
}

export function defaultPeriod(): {
    from: string;
    through: string;
} {
    const through = todayUtc();
    return {
        from: addDays(through, -29),
        through
    };
}
/** The UI end is inclusive; the server's UTC interval is half-open and bounded to 90 days. */
export function reportPeriod(from: string, through: string): {
    from: string;
    to: string;
} {
    day(from);
    day(through);
    const to = addDays(through, 1);
    const days = (Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86400000;
    if (days < 1 || days > 90 || through > todayUtc())
        throw new Error('Выберите период до 90 дней, не позже сегодняшнего дня.');
    return {
        from,
        to
    };
}

export const accountStatus = (value: string): string => ({
    ACTIVE: 'Активен',
    BANNED: 'Заблокирован'
} as Record<string, string>)[value] ?? value;
export const deletionStatus = (value: string): string => ({
    ACTIVE: 'Не запрошено',
    PENDING_DELETION: 'Ожидает удаления',
    PURGING: 'Удаляется',
    PURGED: 'Удалён'
} as Record<string, string>)[value] ?? value;
export const entitlementSource = (value: string): string => ({
    CONFIG: 'Базовый доступ',
    PROMO: 'Промокод',
    BILLING: 'Биллинг'
} as Record<string, string>)[value] ?? value;
export const mediaState = (value: string): string => ({
    PENDING_UPLOAD: 'Ожидает загрузки',
    VERIFYING: 'Проверяется',
    PROCESSING: 'Обрабатывается',
    READY: 'Готово',
    FAILED_RETRYABLE: 'Ошибка, возможен повтор',
    REJECTED: 'Отклонено',
    DELETED: 'Удалено'
} as Record<string, string>)[value] ?? value;
export function operationUnits(operation: string, units: number): string {
    if (operation === 'STT')
        return `${number(units)} с`;
    if (['ASSESSMENT', 'PODCASTS', 'QUALITY_IMAGES', 'HIGH_FACTCHECK', 'SMART_PLAN'].includes(operation))
        return `${number(units)} раз`;
    return units > 0 ? `${number(units)} ед.` : 'Не учитываются';
}
