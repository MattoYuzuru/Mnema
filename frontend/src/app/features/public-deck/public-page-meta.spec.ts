import { Meta, Title } from '@angular/platform-browser';
import { TestBed } from '@angular/core/testing';

import { PublicPageMeta, SITE_REFERRER_POLICY } from './public-page-meta';

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

    it('leaves no robots tag behind when the document had none', () => {
        const meta = TestBed.inject(PublicPageMeta);
        expect(tag('robots')?.content).toBe('noindex');
        meta.indexable(true);
        expect(tag('robots')).toBeNull();
        TestBed.resetTestingModule();
        expect(tag('referrer')).toBeNull();
    });
});
