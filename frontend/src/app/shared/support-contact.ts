import { InjectionToken } from '@angular/core';

import { appConfig } from '../app.config';

export interface TelegramContact {
    readonly username: string;
    readonly url: string;
}

/** BotFather's username format keeps the public contact on the intended t.me host. */
export function telegramContact(value: unknown): TelegramContact | null {
    if (typeof value !== 'string' || !/^[a-z0-9_]{2,29}bot$/i.test(value)) return null;
    return { username: value, url: `https://t.me/${value}` };
}

export const SUPPORT_CONTACT = new InjectionToken<TelegramContact | null>('SUPPORT_CONTACT', {
    providedIn: 'root',
    factory: () => telegramContact(appConfig.supportTelegramUsername)
});
