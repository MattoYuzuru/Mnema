import { UrlMatcher, UrlSegment } from '@angular/router';

/**
 * `/d/:code/:slug` and `/d/:code` as one route, so the page is not rebuilt when it replaces a wrong or missing slug with the
 * canonical one. The code is not checked here: a malformed one is the «Колода не найдена» screen, not a redirect home. The
 * slug (PUBLIC decks only) is the transliterated title and only ever decorates the address.
 */
export const publicDeckMatcher: UrlMatcher = (segments: UrlSegment[]) => {
    if ((segments.length !== 2 && segments.length !== 3) || segments[0].path !== 'd') return null;
    const posParams: Record<string, UrlSegment> = { code: segments[1] };
    if (segments.length === 3) posParams['slug'] = segments[2];
    return { consumed: segments, posParams };
};
