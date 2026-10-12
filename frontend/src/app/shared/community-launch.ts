import { InjectionToken } from '@angular/core';

/**
 * Whether the community catalogue is open to readers: the «Сообщество» item and the search entry in the masthead, and the link in the empty
 * «Мои колоды». The enabling pull request of epic 3 flips this to `true` together with the server switch `learning.community.catalog.enabled`, after
 * the owner's integrated check; until then the route `/community` exists but nothing in the product leads to it.
 */
export const COMMUNITY_LAUNCHED = false;

/** The flag as the app reads it. Specs provide the other value; the application uses the constant. */
export const COMMUNITY_LAUNCH = new InjectionToken<boolean>('COMMUNITY_LAUNCH', { providedIn: 'root', factory: () => COMMUNITY_LAUNCHED });
