import { DOCUMENT, Injectable, inject } from '@angular/core';
import { Meta, Title } from '@angular/platform-browser';
import { RouterStateSnapshot, TitleStrategy } from '@angular/router';
import publicPages from './public-pages.json';

const ORIGIN = 'https://mnema.app';
const IMAGE = `${ORIGIN}/assets/og-image.png`;

/** The same route inventory feeds prerender and the sitemap build check. No private URL enters the canonical. */
@Injectable()
export class SeoTitleStrategy extends TitleStrategy {
    private readonly document = inject(DOCUMENT);
    private readonly meta = inject(Meta);
    private readonly title = inject(Title);

    override updateTitle(snapshot: RouterStateSnapshot): void {
        const path = snapshot.url.split(/[?#]/u)[0].replace(/\/+$/u, '') || '/';
        const page = publicPages.find(candidate => candidate.path === path);
        const title = page?.title ?? this.buildTitle(snapshot) ?? 'Мнема | Mnema';
        const description = page?.description ?? 'Мнема: личные учебные материалы и упражнения.';
        this.title.setTitle(title);
        this.meta.updateTag({ name: 'description', content: description });
        this.meta.updateTag({ name: 'robots', content: page ? 'index,follow' : 'noindex,follow' });
        this.meta.updateTag({ property: 'og:title', content: title });
        this.meta.updateTag({ property: 'og:description', content: description });
        this.meta.updateTag({ name: 'twitter:title', content: title });
        this.meta.updateTag({ name: 'twitter:description', content: description });
        this.meta.updateTag({ property: 'og:image', content: IMAGE });
        this.meta.updateTag({ name: 'twitter:image', content: IMAGE });

        let canonical = this.document.head.querySelector<HTMLLinkElement>('link[rel="canonical"]');
        if (page) {
            canonical ??= this.document.createElement('link');
            canonical.rel = 'canonical';
            canonical.href = ORIGIN + page.path;
            if (!canonical.parentNode) this.document.head.appendChild(canonical);
            this.meta.updateTag({ property: 'og:url', content: canonical.href });
        } else {
            if (canonical?.parentNode) canonical.parentNode.removeChild(canonical);
            this.meta.removeTag('property="og:url"');
        }

        let structured = this.document.getElementById('mnema-structured-data');
        if (!page) {
            if (structured?.parentNode) structured.parentNode.removeChild(structured);
            return;
        }
        structured ??= this.document.createElement('script');
        structured.id = 'mnema-structured-data';
        structured.setAttribute('type', 'application/ld+json');
        const website = { '@type': 'WebSite', '@id': `${ORIGIN}/#website`, name: 'Мнема', alternateName: 'Mnema', url: `${ORIGIN}/` };
        structured.textContent = JSON.stringify({
            '@context': 'https://schema.org',
            ...(page.path === '/' ? website : {
                '@type': 'WebPage', url: ORIGIN + page.path, name: title,
                description, inLanguage: 'ru-RU', isPartOf: { '@id': website['@id'] }
            })
        }).replace(/</gu, '\\u003c');
        if (!structured.parentNode) this.document.head.appendChild(structured);
    }
}
