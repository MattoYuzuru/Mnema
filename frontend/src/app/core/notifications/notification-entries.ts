import { AppNotification } from './notification.models';
import { PresentedNotification, presentNotification } from './notification-presenter';

export interface InboxEntry {
    readonly notification: AppNotification;
    readonly presented: PresentedNotification;
}

/** The renderable subset of a list, in order; unknown kinds and params that do not fit are left out. */
export function entriesOf(items: readonly AppNotification[]): readonly InboxEntry[] {
    return items.flatMap(notification => {
        const presented = presentNotification(notification);
        return presented === null ? [] : [{ notification, presented }];
    });
}
