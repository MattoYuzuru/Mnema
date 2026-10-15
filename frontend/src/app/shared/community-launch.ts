import { InjectionToken } from '@angular/core';

import { appConfig } from '../app.config';

/**
 * Whether the community catalogue is open to readers: the «Сообщество» item and the search entry in the masthead, and the link in the empty
 * «Мои колоды». On since the catalogue launch, together with the server switch `learning.community.catalog.enabled`; setting it back to `false`
 * hides the entry points again while the route `/community` stays.
 */
export const COMMUNITY_LAUNCHED = true;

/**
 * The flag as the app reads it. Specs provide the other value; the application uses the constant, except that a loopback host (development, the browser
 * harness `--catalog`) may open it at runtime with `communityLaunched: true` in `app-config.js`; `resolveAppConfig` drops that key on every other host.
 */
export const COMMUNITY_LAUNCH = new InjectionToken<boolean>('COMMUNITY_LAUNCH', { providedIn: 'root', factory: () => isCommunityLaunched(appConfig) });

/** The constant, or the runtime opening of a loopback host (`AppConfig.communityLaunched` survives `resolveAppConfig` only there). */
export function isCommunityLaunched(config: { readonly communityLaunched?: boolean }): boolean {
    return COMMUNITY_LAUNCHED || config.communityLaunched === true;
}
