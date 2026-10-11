import { Injectable, OnDestroy, inject } from '@angular/core';
import { Meta, Title } from '@angular/platform-browser';

/**
 * The head of a public deck page while its route is active. `referrer: no-referrer` keeps `/d/{code}` (a capability for a
 * link-only deck) from leaking to a third-party site the reader follows from here, and from the page's own requests; `robots:
 * noindex` is on until the page knows the deck is PUBLIC (link and invitation decks, and every screen that says «not found»,
 * must stay out of search results). Leaving the page sets the referrer policy back to the site's own before the tag goes (Chrome keeps the last applied policy otherwise), restores the shell's `robots` and title (constants, not a snapshot of the head, which carries the card of this URL when the web server has inserted one). Open Graph tags, the
 * canonical address and the crawler-facing `robots` are put into `index.html` by the web server (Share/14, docs/operations/link-previews.md); this
 * class is the same decision for the browser after the shell has loaded.
 */
/** The `Referrer-Policy` header the web server sends for every page. */
export const SITE_REFERRER_POLICY = 'strict-origin-when-cross-origin';
/**
 * The title and `robots` of the shell (the defaults of `index.html`). For a `/d/...` address the web server puts the card of THAT URL into the head
 * (a deck title, or `noindex` for a link-only deck), so what the document carries when this page opens is not the shell's value and must not be restored
 * later on another route.
 */
export const SHELL_TITLE = 'Mnema';
export const SHELL_ROBOTS = 'index,follow';

@Injectable()
export class PublicPageMeta implements OnDestroy {
    private readonly meta = inject(Meta);
    private readonly titles = inject(Title);

    constructor() {
        this.meta.updateTag({ name: 'referrer', content: 'no-referrer' });
        this.indexable(false);
    }

    /** Allows search engines only for a loaded PUBLIC deck. */
    indexable(allowed: boolean): void {
        if (allowed) this.restoreRobots();
        else this.meta.updateTag({ name: 'robots', content: 'noindex' });
    }

    title(text: string | null): void {
        this.titles.setTitle(text === null ? SHELL_TITLE : `${text} — ${SHELL_TITLE}`);
    }

    ngOnDestroy(): void {
        // Removing the tag is not enough: Chrome keeps the last policy it applied. Put the site policy back first (the
        // `Referrer-Policy` of the server, docs/operations/browser-security-headers.md), then drop the tag.
        this.meta.updateTag({ name: 'referrer', content: SITE_REFERRER_POLICY });
        this.meta.removeTag("name='referrer'");
        this.restoreRobots();
        this.titles.setTitle(SHELL_TITLE);
    }

    private restoreRobots(): void {
        this.meta.updateTag({ name: 'robots', content: SHELL_ROBOTS });
    }
}
