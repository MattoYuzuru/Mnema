import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, TitleStrategy } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { SeoTitleStrategy } from './seo-title.strategy';
import publicPages from './public-pages.json';

@Component({ template: '' })
class PageFixture {}

describe('public SEO across router navigation', () => {
    let originalHead: string;
    beforeEach(() => {
        originalHead = document.head.innerHTML;
        document.head.querySelectorAll('link[rel="canonical"], #mnema-structured-data').forEach(element => element.remove());
        TestBed.configureTestingModule({ providers: [
            provideRouter([
                ...publicPages.map(page => ({ path: page.path.slice(1), component: PageFixture })),
                { path: 'login', component: PageFixture, title: 'Вход в Мнему | Mnema' },
                { path: 'decks/:id', component: PageFixture },
                { path: '**', component: PageFixture, title: 'Страница не найдена | Мнема' }
            ]),
            { provide: TitleStrategy, useClass: SeoTitleStrategy }
        ] });
    });
    afterEach(() => { document.head.innerHTML = originalHead; });
    const meta = (name: string) => document.head.querySelector(`meta[${name}]`)?.getAttribute('content');

    it('gives every public page its own metadata and a single self canonical, including SPA transitions', async () => {
        const harness = await RouterTestingHarness.create();
        for (const page of publicPages) {
            await harness.navigateByUrl(page.path);
            expect(document.title).toBe(page.title);
            expect(meta('name="description"')).toBe(page.description);
            expect(meta('name="robots"')).toBe('index,follow');
            expect(document.head.querySelectorAll('link[rel="canonical"]')).toHaveLength(1);
            expect(document.head.querySelector('link[rel="canonical"]')?.getAttribute('href')).toBe('https://mnema.app' + page.path);
            expect(meta('property="og:url"')).toBe('https://mnema.app' + page.path);
            expect(meta('property="og:title"')).toBe(page.title);
            expect(meta('name="twitter:description"')).toBe(page.description);
            expect(document.head.querySelectorAll('#mnema-structured-data')).toHaveLength(1);
        }
    });

    it('drops tracking parameters and fragments from canonical and shared URLs', async () => {
        const harness = await RouterTestingHarness.create();
        await harness.navigateByUrl('/ai?campaign=private-value#sources');
        expect(meta('property="og:url"')).toBe('https://mnema.app/ai');
        expect(document.head.querySelector('link[rel="canonical"]')?.getAttribute('href')).toBe('https://mnema.app/ai');
        expect(document.head.innerHTML).not.toContain('private-value');
    });

    it('removes public structured data and canonical from private and unknown routes', async () => {
        const harness = await RouterTestingHarness.create();
        for (const path of ['/login', '/decks/private-id', '/not-a-page']) {
            await harness.navigateByUrl('/ai');
            await harness.navigateByUrl(path);
            expect(meta('name="robots"')).toBe('noindex,follow');
            expect(document.head.querySelector('link[rel="canonical"]')).toBeNull();
            expect(document.head.querySelector('#mnema-structured-data')).toBeNull();
            expect(meta('property="og:url"')).toBeUndefined();
            expect(document.title).not.toContain('Как Мнема');
        }
        await harness.navigateByUrl('/login');
        expect(document.title).toBe('Вход в Мнему | Mnema');
        await harness.navigateByUrl('/');
        expect(meta('name="robots"')).toBe('index,follow');
    });

    it('identifies the real bilingual site name on the homepage without fabricated ratings or search actions', async () => {
        const harness = await RouterTestingHarness.create('/');
        const structured = () => JSON.parse(document.getElementById('mnema-structured-data')!.textContent!);
        expect(structured()).toMatchObject({ '@type': 'WebSite', name: 'Мнема', alternateName: 'Mnema', url: 'https://mnema.app/' });
        expect(structured()).not.toHaveProperty('potentialAction');
        expect(structured()).not.toHaveProperty('aggregateRating');
        await harness.navigateByUrl('/events');
        expect(structured()).toMatchObject({ '@type': 'WebPage', url: 'https://mnema.app/events', inLanguage: 'ru-RU' });
    });
});
