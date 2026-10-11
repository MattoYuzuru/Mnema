import { Meta, Title } from '@angular/platform-browser';
import { TestBed } from '@angular/core/testing';

import { PublicPageMeta, SHELL_ROBOTS, SHELL_TITLE, SITE_REFERRER_POLICY } from './public-page-meta';

describe('PublicPageMeta', () => {
    const tag = (name: string) => document.head.querySelector<HTMLMetaElement>(`meta[name=${name}]`);

    beforeEach(() => {
        document.head.querySelectorAll('meta[name=robots], meta[name=referrer]').forEach(element => element.remove());
        TestBed.configureTestingModule({ providers: [PublicPageMeta] });
    });

    it('sends no referrer and no indexing while the page is open, and restores the head when it is left', () => {
        TestBed.inject(Meta).addTag({ name: 'robots', content: 'index,follow' });
        TestBed.inject(Title).setTitle('Mnema');
        const meta = TestBed.inject(PublicPageMeta);
        expect(tag('referrer')?.content).toBe('no-referrer');
        expect(tag('robots')?.content).toBe('noindex');
        meta.title('Испанские глаголы');
        expect(document.title).toBe('Испанские глаголы — Mnema');
        meta.indexable(true);
        expect(tag('robots')?.content).toBe('index,follow');
        meta.indexable(false);
        expect(tag('robots')?.content).toBe('noindex');
        meta.title(null);
        expect(document.title).toBe('Mnema');

        const update = vi.spyOn(TestBed.inject(Meta), 'updateTag');
        const remove = vi.spyOn(TestBed.inject(Meta), 'removeTag');
        TestBed.resetTestingModule();
        // The site policy is applied first (Chrome keeps the last policy it applied), then the tag is removed.
        expect(update).toHaveBeenCalledWith({ name: 'referrer', content: SITE_REFERRER_POLICY });
        expect(SITE_REFERRER_POLICY).toBe('strict-origin-when-cross-origin');
        expect(update.mock.invocationCallOrder[0]).toBeLessThan(remove.mock.invocationCallOrder[0]);
        expect(tag('referrer')).toBeNull();
        expect(tag('robots')?.content).toBe('index,follow');
        expect(document.title).toBe('Mnema');
    });

    it('restores the shell defaults, not the card the web server inserted for this URL', () => {
        // /d/{code} of a link-only deck: the server put the generic noindex card and a deck-specific title into the document before the page opened
        TestBed.inject(Meta).addTag({ name: 'robots', content: 'noindex' });
        TestBed.inject(Title).setTitle('Колода по ссылке — Mnema');
        const meta = TestBed.inject(PublicPageMeta);
        meta.indexable(true);
        expect(tag('robots')?.content).toBe(SHELL_ROBOTS);
        meta.title(null);
        expect(document.title).toBe(SHELL_TITLE);
        TestBed.resetTestingModule();
        expect(tag('robots')?.content).toBe('index,follow');
        expect(document.title).toBe('Mnema');
        expect(tag('referrer')).toBeNull();
    });

    it('adds the robots tag back when the document had none', () => {
        const meta = TestBed.inject(PublicPageMeta);
        expect(tag('robots')?.content).toBe('noindex');
        meta.indexable(true);
        expect(tag('robots')?.content).toBe(SHELL_ROBOTS);
        TestBed.resetTestingModule();
        expect(tag('referrer')).toBeNull();
        expect(tag('robots')?.content).toBe(SHELL_ROBOTS);
    });
});
