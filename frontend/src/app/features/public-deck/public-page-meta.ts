import { Injectable, OnDestroy, inject } from '@angular/core';
import { Meta, Title } from '@angular/platform-browser';

/**
 * The head of a public deck page while its route is active. `referrer: no-referrer` keeps `/d/{code}` (a capability for a
 * link-only deck) from leaking to a third-party site the reader follows from here, and from the page's own requests; `robots:
 * noindex` is on until the page knows the deck is PUBLIC (link and invitation decks, and every screen that says «not found»,
 * must stay out of search results). Leaving the page sets the referrer policy back to the site's own before the tag goes (Chrome keeps the last applied policy otherwise), restores `robots` and the title. Open Graph and
 * the server-side robots header are Share/14.
 */
/** The `Referrer-Policy` header the web server sends for every page. */
export const SITE_REFERRER_POLICY = 'strict-origin-when-cross-origin';

@Injectable()
export class PublicPageMeta implements OnDestroy {
    private readonly meta = inject(Meta);
    private readonly titles = inject(Title);
    private readonly previousTitle = this.titles.getTitle();
    /** `index.html` ships `robots: index,follow`; leaving the page puts that back. */
    private readonly baseRobots = this.meta.getTag("name='robots'")?.content ?? null;

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
        this.titles.setTitle(text === null ? this.previousTitle : `${text} — Mnema`);
    }

    ngOnDestroy(): void {
        // Removing the tag is not enough: Chrome keeps the last policy it applied. Put the site policy back first (the
        // `Referrer-Policy` of the server, docs/operations/browser-security-headers.md), then drop the tag.
        this.meta.updateTag({ name: 'referrer', content: SITE_REFERRER_POLICY });
        this.meta.removeTag("name='referrer'");
        this.restoreRobots();
        this.titles.setTitle(this.previousTitle);
    }

    private restoreRobots(): void {
        if (this.baseRobots === null) this.meta.removeTag("name='robots'");
        else this.meta.updateTag({ name: 'robots', content: this.baseRobots });
    }
}
