import { AccessFailure, DeckInvite, GRANTS_LIMIT, InviteStatus } from './access.models';
import type { PublicationLevel } from '../publication/publication.models';
import { plural } from '../../../core/notifications/notification-presenter';

/** The words of the «Доступ» panel (docs/product/community-decks.md; plain language, no long dashes). */
export const ACCESS_TEXT = {
    levelLegend: 'Кто видит колоду',
    levelHints: {
        PRIVATE: 'Только вы.',
        LINK: 'Любой, у кого есть ссылка, в том числе без аккаунта.',
        INVITE: 'Вы и те, кого вы пригласите: ссылкой, по логину или по запросу.',
        PUBLIC: 'Все. В каталоге колода появится, если выполнен порог качества.'
    } satisfies Record<PublicationLevel, string>,
    levelNames: { PRIVATE: 'Приватная', LINK: 'По ссылке', INVITE: 'По приглашению', PUBLIC: 'Публичная' } satisfies Record<PublicationLevel, string>,
    apply: 'Применить',
    applying: 'Применяем…',
    cancel: 'Отмена',
    levelChanged: (level: string): string => `Доступ изменён: ${level}`,
    changeTitle: 'Изменить доступ',
    publishesHead: 'Читатели увидят текущую версию колоды: она будет опубликована сейчас.',
    toPrivate: 'Колоду увидите только вы.',
    toLink: 'Колоду откроет любой, у кого есть ссылка, в том числе без аккаунта.',
    toInvite: 'Колоду откроют только те, кого вы пригласите.',
    toPublic: 'Публичную колоду увидят все, а рядом будет ваш логин. Для этого нужно заполнить несколько пунктов.',
    makePublic: 'Сделать публичной…',
    rotates: 'Ссылка изменится, и старые ссылки перестанут работать.',
    keepsLink: 'Ссылка останется прежней.',
    invitesDie: 'Пригласительные ссылки перестанут работать, а после повышения уровня их придётся создать заново.',
    keepsGrants: 'Выданные доступы сохранятся, но откроют колоду только при уровне «По приглашению».',
    leavesCatalog: 'Колода пропадёт из каталога и из профиля автора.',
    requestsSwitch: 'Принимать запросы доступа',
    requestsOnHint: 'На экране «нет доступа» появится кнопка «Запросить доступ». Вы получите уведомление, не чаще раза в день на колоду.',
    requestsOffHint: 'Запросы не принимаются: на экране «нет доступа» кнопки нет.',
    requestsSaved: 'Настройка запросов сохранена',
    levelSectionLabel: 'Доступ к колоде',
    inviteOnlyHint: 'Приглашения, запросы и список людей появятся, когда колода будет доступна по приглашению.',
    heading: 'Доступ',
    requestsHeading: 'Запросы доступа',
    requestsEmpty: 'Новых запросов нет. Когда кто-то попросит доступ, запрос появится здесь.',
    requestsOffNotice: 'Запросы выключены: новые не приходят. Открытые можно решить.',
    requestFrom: 'Запрос от',
    approve: 'Принять',
    reject: 'Отклонить',
    approving: 'Принимаем…',
    rejecting: 'Отклоняем…',
    approved: (who: string): string => `Доступ открыт: ${who}`,
    rejected: 'Запрос отклонён. Человек об этом не узнает.',
    invitesHeading: 'Пригласительные ссылки',
    invitesLede: 'Ссылка открывает колоду тому, кто вошёл в аккаунт. Её можно сделать одноразовой или многоразовой, со сроком, и отозвать.',
    usesLabel: 'Сколько раз можно открыть',
    expiryLabel: 'Срок действия',
    createInvite: 'Создать ссылку',
    creatingInvite: 'Создаём…',
    newInviteHeading: 'Новая ссылка',
    newInviteOnce: 'Ссылка показана один раз. После «Готово» открыть её снова будет нельзя: скопируйте её или отправьте сейчас. Потерянную ссылку отзовите и создайте новую.',
    newInviteLabel: 'Пригласительная ссылка',
    newInviteDone: 'Готово, я сохранил ссылку',
    newInviteCreated: 'Ссылка создана',
    invitesEmpty: 'Ссылок пока нет.',
    invitesList: 'Созданные ссылки',
    expiry: 'Срок:',
    createdOn: 'создана',
    inviteNetwork: 'Не удалось связаться с сервером. Проверьте список ниже: если в нём появилась лишняя ссылка, отзовите её.',
    revoke: 'Отозвать',
    revoking: 'Отзываем…',
    revoked: 'Ссылка отозвана. Те, кто уже вошёл по ней, сохраняют доступ.',
    inviteNeedsLevel: 'Сначала выберите уровень доступа «По ссылке» или «По приглашению»: у приватной колоды приглашений нет.',
    inviteLimit: 'Действующих ссылок уже 20. Отзовите ненужные и создайте новую.',
    peopleHeading: 'Кто имеет доступ',
    peopleEmpty: 'Пока никого. Люди появятся здесь, когда вы примете запрос или пригласите по логину.',
    grantedOn: 'с',
    closeAccess: 'Закрыть доступ',
    closing: 'Закрываем…',
    accessClosed: 'Доступ закрыт. Копии, которые человек уже сделал, остаются у него. По пригласительной ссылке он не вернётся 30 дней, а по логину вы можете пригласить его снова сразу.',
    loginHeading: 'Пригласить по логину',
    loginLabel: 'Логин',
    loginHint: 'Только пользователи с публичным профилем.',
    loginInvite: 'Пригласить',
    loginInviting: 'Проверяем…',
    loginInvalid: 'Логин: от 3 до 50 символов, латиница, цифры, точка, дефис и подчёркивание.',
    loginNotFound: 'Такого пользователя нет, или у него не открыт публичный профиль.',
    loginSelf: 'Это ваш логин: у вас и так есть доступ.',
    loginBusy: 'Слишком много проверок. Подождите минуту и повторите.',
    loginNeedsInvite: 'Приглашать по логину можно, когда колода доступна по приглашению. Выберите этот уровень выше.',
    grantsFull: `У колоды уже ${GRANTS_LIMIT} человек с доступом. Закройте доступ тем, кому он не нужен, и повторите.`,
    inviteStorageFull: 'Слишком много сохранённых ссылок. Отзовите ненужные и повторите позже.',
    loginUnavailable: 'Не удалось проверить логин. Повторите чуть позже.',
    loginGranted: (login: string): string => `Доступ открыт: @${login}`,
    noProfile: 'Пользователь без публичного профиля',
    nameFailed: 'Имя не загрузилось',
    nameLoading: 'Загружаем имя…',
    loading: 'Загружаем…',
    loadError: 'Не удалось загрузить список.',
    retry: 'Повторить',
    loadingMore: 'Загружаем следующие записи…',
    moreError: 'Не удалось загрузить следующие записи.',
    network: 'Не удалось связаться с сервером. Проверьте соединение и повторите.',
    unknown: 'Не получилось. Повторите чуть позже.',
    gone: 'Этого больше нет. Мы обновили список.',
    opensInNewTab: ' (откроется в новой вкладке)'
} as const;

const RANK: Readonly<Record<PublicationLevel, number>> = { PRIVATE: 0, INVITE: 1, LINK: 2, PUBLIC: 3 };

/** What the owner is told before a level change is applied: the new audience and what happens to the links. */
export function levelChangeLines(from: PublicationLevel, to: PublicationLevel): readonly string[] {
    const audience = { PRIVATE: ACCESS_TEXT.toPrivate, LINK: ACCESS_TEXT.toLink, INVITE: ACCESS_TEXT.toInvite, PUBLIC: ACCESS_TEXT.toPublic }[to];
    if (to === 'PUBLIC') return [audience];
    if (RANK[to] > RANK[from]) {
        // Raising the level keeps the code; leaving «Приватная» publishes the head for the first readers.
        return from === 'PRIVATE' ? [audience, ACCESS_TEXT.publishesHead] : [audience, ACCESS_TEXT.keepsLink];
    }
    return [
        audience,
        ...(from === 'PUBLIC' ? [ACCESS_TEXT.leavesCatalog] : []),
        ACCESS_TEXT.rotates,
        ACCESS_TEXT.invitesDie,
        ...(to === 'PRIVATE' && from === 'INVITE' ? [ACCESS_TEXT.keepsGrants] : [])
    ];
}

const USES = ['раз', 'раза', 'раз'] as const;

/** «Один раз», «5 раз» for the use limit of the create form and a list row. */
export function usesOption(maxUses: number | null): string {
    if (maxUses === null) return 'Без ограничения';
    if (maxUses === 1) return 'Один раз';
    return `${maxUses} ${plural(maxUses, USES)}`;
}

export function expiryOption(days: number | null): string {
    if (days === null) return 'Без срока';
    return `${days} ${plural(days, ['день', 'дня', 'дней'])}`;
}

const STATUS_WORD: Readonly<Record<InviteStatus, string>> = {
    ACTIVE: 'Действует', EXPIRED: 'Срок вышел', EXHAUSTED: 'Использована', REVOKED: 'Отозвана', STALE: 'Устарела'
};

export function inviteStatusWord(status: InviteStatus): string {
    return STATUS_WORD[status];
}

/** «Использовано 2 из 5» or «Использовано 3 раза» for an unlimited link. */
export function inviteUsesText(invite: DeckInvite): string {
    if (invite.status === 'STALE') return 'Ссылка устарела: доступ был понижен. Создайте новую.';
    if (invite.maxUses === null) return `Открыли: ${invite.uses}`;
    return invite.maxUses === 1 ? (invite.uses === 0 ? 'Одноразовая, ещё не открыта' : 'Одноразовая, открыта') : `Открыли: ${invite.uses} из ${invite.maxUses}`;
}

export function accessProblemText(failure: Pick<AccessFailure, 'status'> & Partial<AccessFailure> | null): string {
    if (failure === null || failure.status === 0) return ACCESS_TEXT.network;
    if (failure.status === 404) return ACCESS_TEXT.gone;
    if (failure.status === 422 && failure.limit === GRANTS_LIMIT) return ACCESS_TEXT.grantsFull;
    return ACCESS_TEXT.unknown;
}
