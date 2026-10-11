import { COMPLAINT_REASON_LABELS } from '../../shared/complaint.models';
import { ComplaintDecision, ComplaintRow, ComplaintTarget, JournalAction, Reason } from './admin-complaints.models';
import { dateTime } from './admin-presenters';

const DAY_MS = 86_400_000;

export const reasonName = (reason: Reason): string => COMPLAINT_REASON_LABELS[reason];

export const targetName: Readonly<Record<ComplaintTarget, string>> = { DECK: 'Колода', PROFILE: 'Профиль' };

/** What each decision did, as the queue and the detail say it. */
export const decisionNames: Readonly<Record<ComplaintDecision, string>> = {
    REJECTED: 'Жалоба отклонена',
    HIDDEN: 'Колода скрыта из Сообщества',
    TAKEN_DOWN: 'Материалы сняты по закону',
    ACCOUNT_REFERRED: 'Передано в справочник аккаунтов'
};

export const journalActionNames: Readonly<Record<JournalAction, string>> = {
    ...decisionNames,
    UNHIDDEN: 'Колода возвращена в Сообщество'
};

export const visibilityNames: Readonly<Record<string, string>> = {
    PRIVATE: 'Приватная', INVITE: 'По приглашению', LINK: 'По ссылке', PUBLIC: 'Публичная'
};

/** The reported thing in one line: the deck title (or «Колода удалена») or the profile login. */
export function targetTitle(row: Pick<ComplaintRow, 'targetKind' | 'deckTitle' | 'profileUsername' | 'profileAccountId'>): string {
    if (row.targetKind === 'PROFILE') return row.profileUsername === null ? 'Профиль' : `Профиль @${row.profileUsername}`;
    return row.deckTitle !== null && row.deckTitle.trim() !== '' ? row.deckTitle : 'Колода удалена или без названия';
}

/**
 * The review deadline in words, never in colour alone: «Просрочена на 3 дн.» for an open complaint past its due date (the server's flag
 * decides), «Срок через 12 дн.» before it, «Решена 12 октября…» once decided. The date itself is shown next to it by the caller.
 */
export function deadlineText(row: Pick<ComplaintRow, 'status' | 'overdue' | 'dueAt' | 'decidedAt'>, now: number): string {
    if (row.status === 'DECIDED') return row.decidedAt === null ? 'Решена' : `Решена ${dateTime(row.decidedAt)}`;
    const due = Date.parse(row.dueAt);
    if (row.overdue) {
        const days = Math.floor((now - due) / DAY_MS);
        return days >= 1 ? `Просрочена на ${days} дн.` : 'Просрочена';
    }
    const days = Math.ceil((due - now) / DAY_MS);
    return days <= 0 ? 'Срок истекает сегодня' : `Срок через ${days} дн.`;
}
