import { InjectionToken } from '@angular/core';

/** Opens a `mailto:` address in the visitor's mail client. A token keeps the browser navigation out of unit tests. */
export type MailNavigator = (mailto: string) => void;

export const MAIL_NAVIGATOR = new InjectionToken<MailNavigator>('MAIL_NAVIGATOR', {
    providedIn: 'root',
    factory: () => mailto => window.location.assign(mailto)
});
