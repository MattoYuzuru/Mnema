import { ManagedEvent } from './events.models';

export const TEST_EVENT: ManagedEvent = {
    eventId: '0a000000-0000-4000-8000-000000000043', title: 'Новый редактор',
    bodyMarkdown: 'Теперь **удобнее** создавать материалы.\n\n[Открыть Мнему](/decks)',
    eventDate: '2026-10-07', publishedAt: '2026-10-07T12:00:00Z', published: true, rowVersion: '1',
    createdAt: '2026-10-07T12:00:00Z', updatedAt: '2026-10-07T12:00:00Z'
};

export const publicEvent = () => {
    const { eventId, title, bodyMarkdown, eventDate, publishedAt } = TEST_EVENT;
    return { eventId, title, bodyMarkdown, eventDate, publishedAt };
};
