export interface ProductEvent {
    readonly eventId: string;
    readonly title: string;
    readonly bodyMarkdown: string;
    readonly eventDate: string;
    readonly publishedAt: string | null;
}

export interface ManagedEvent extends ProductEvent {
    readonly published: boolean;
    readonly rowVersion: string;
    readonly createdAt: string;
    readonly updatedAt: string;
}

export interface EventPage<T extends ProductEvent> {
    readonly items: readonly T[];
    readonly nextCursor: string | null;
}

export interface EventEdit {
    readonly commandId: string;
    readonly title: string;
    readonly bodyMarkdown: string;
    readonly eventDate: string;
    readonly published: boolean;
}

export class EventsProtocolError extends Error {}

function record(value: unknown): Record<string, unknown> {
    if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new EventsProtocolError('Invalid event response');
    return value as Record<string, unknown>;
}

function text(value: unknown, maximum: number): string {
    if (typeof value !== 'string' || !value.trim() || value.length > maximum) throw new EventsProtocolError('Invalid event text');
    return value;
}

function instant(value: unknown): string {
    const result = text(value, 40);
    if (!/^\d{4}-\d{2}-\d{2}T/u.test(result) || Number.isNaN(Date.parse(result))) throw new EventsProtocolError('Invalid event timestamp');
    return result;
}

export function eventDate(value: unknown): string {
    const result = text(value, 10);
    if (!/^\d{4}-\d{2}-\d{2}$/u.test(result) || Number.isNaN(Date.parse(`${result}T00:00:00Z`)) ||
        new Date(`${result}T00:00:00Z`).toISOString().slice(0, 10) !== result) throw new EventsProtocolError('Invalid event date');
    return result;
}

export function parseEvent(value: unknown): ProductEvent {
    const item = record(value);
    const id = text(item['eventId'], 36);
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu.test(id)) throw new EventsProtocolError('Invalid event id');
    return {
        eventId: id, title: text(item['title'], 160), bodyMarkdown: text(item['bodyMarkdown'], 16000),
        eventDate: eventDate(item['eventDate']), publishedAt: item['publishedAt'] === null ? null : instant(item['publishedAt'])
    };
}

export function parseManagedEvent(value: unknown): ManagedEvent {
    const item = record(value);
    const version = text(item['rowVersion'], 20);
    if (typeof item['published'] !== 'boolean' || !/^(0|[1-9]\d{0,18})$/u.test(version)) throw new EventsProtocolError('Invalid event state');
    return { ...parseEvent(value), published: item['published'], rowVersion: version,
        createdAt: instant(item['createdAt']), updatedAt: instant(item['updatedAt']) };
}

export function parseEventPage<T extends ProductEvent>(value: unknown, parse: (value: unknown) => T): EventPage<T> {
    const page = record(value);
    if (!Array.isArray(page['items']) || page['items'].length > 50) throw new EventsProtocolError('Invalid event page');
    const items = page['items'].map(parse);
    if (new Set(items.map(item => item.eventId)).size !== items.length) throw new EventsProtocolError('Duplicate event');
    const nextCursor = page['nextCursor'] === null ? null : text(page['nextCursor'], 256);
    return { items, nextCursor };
}

export function parseEventEnvelope(value: unknown): ManagedEvent { return parseManagedEvent(record(value)['event']); }

export function formatEventDate(value: string): string {
    return new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'long', year: 'numeric', timeZone: 'UTC' })
        .format(new Date(`${value}T00:00:00Z`));
}
