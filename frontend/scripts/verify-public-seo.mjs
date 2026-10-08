/** Build gate: source route inventory, sitemap and crawler-visible HTML must agree. */
import { readFile, stat } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { JSDOM } from 'jsdom';

const source = fileURLToPath(new URL('../src/app/core/seo/public-pages.json', import.meta.url));
const check = (condition, message) => { if (!condition) throw new Error(message); };
const one = (document, selector) => {
    const matches = document.querySelectorAll(selector);
    check(matches.length === 1, `Expected exactly one ${selector}`);
    return matches[0];
};

export async function verifyPublicSeo(dist) {
    const pages = JSON.parse(await readFile(source, 'utf8'));
    const nginx = await readFile(fileURLToPath(new URL('../nginx.conf', import.meta.url)), 'utf8');
    const publicMapping = /location ~ \^\/\(([^)]+)\)\/\?\$ \{/u.exec(nginx)?.[1].split('|').map(path => '/' + path).sort();
    check(JSON.stringify(publicMapping) === JSON.stringify(pages.filter(page => page.path !== '/').map(page => page.path).sort()),
        'Nginx public route mapping must match the public inventory');
    const sitemap = new JSDOM(await readFile(join(dist, 'sitemap.xml'), 'utf8'), { contentType: 'application/xml' });
    try {
        const urls = [...sitemap.window.document.querySelectorAll('url > loc')].map(element => element.textContent);
        const expected = pages.map(page => 'https://mnema.app' + page.path);
        check(urls.length === new Set(urls).size, 'Duplicate sitemap URL');
        check(JSON.stringify([...urls].sort()) === JSON.stringify([...expected].sort()), 'Sitemap must contain every public page and no private page');
        check(!sitemap.window.document.querySelector('priority, changefreq, lastmod'), 'Do not invent sitemap priority, frequency or modification dates');
    } finally { sitemap.window.close(); }

    for (const page of pages) {
        const html = await readFile(join(dist, page.path.slice(1), 'index.html'), 'utf8');
        const dom = new JSDOM(html);
        try {
            const document = dom.window.document;
            check(document.documentElement.lang === 'ru', `${page.path}: missing Russian language`);
            check(one(document, 'title').textContent === page.title, `${page.path}: wrong title`);
            check(one(document, 'meta[name="description"]').content === page.description, `${page.path}: wrong description`);
            check(one(document, 'meta[name="robots"]').content === 'index,follow', `${page.path}: public page is not indexable`);
            check(one(document, 'link[rel="canonical"]').getAttribute('href') === 'https://mnema.app' + page.path, `${page.path}: wrong canonical`);
            check(one(document, 'meta[property="og:url"]').content === 'https://mnema.app' + page.path, `${page.path}: wrong sharing URL`);
            check(one(document, 'meta[property="og:title"]').content === page.title, `${page.path}: wrong sharing title`);
            check(one(document, 'meta[name="twitter:description"]').content === page.description, `${page.path}: wrong sharing description`);
            check(one(document, 'main h1').textContent.trim().length > 0, `${page.path}: no prerendered heading`);
            check(one(document, 'main').textContent.trim().length > 100, `${page.path}: empty application shell`);
            check(document.querySelector('app-root[ngh]'), `${page.path}: missing hydration state`);
            for (const path of pages.filter(page => page.path !== '/').map(page => page.path)) {
                check(document.querySelector(`footer a[href="${path}"]`), `${page.path}: missing crawlable footer link to ${path}`);
            }
            const structured = JSON.parse(one(document, '#mnema-structured-data').textContent);
            check(structured['@type'] === (page.path === '/' ? 'WebSite' : 'WebPage'), `${page.path}: wrong structured-data type`);
            check(structured.url === 'https://mnema.app' + page.path, `${page.path}: wrong structured-data URL`);
            const icon = one(document, 'link[rel="icon"]').getAttribute('href');
            check(icon === '/favicon.ico' && (await stat(join(dist, icon.slice(1)))).isFile(), `${page.path}: current ICO is unavailable`);
        } finally { dom.window.close(); }
    }
    const csr = new JSDOM(await readFile(join(dist, 'index.csr.html'), 'utf8'));
    try {
        const document = csr.window.document;
        check(one(document, 'meta[name="robots"]').content === 'noindex,follow', 'Private CSR shell must start with noindex');
        check(!document.querySelector('link[rel="canonical"], #mnema-structured-data, app-root[ngh], main'), 'Private shell must not reuse a public prerendered page');
    } finally { csr.window.close(); }
    const robots = await readFile(join(dist, 'robots.txt'), 'utf8');
    check(robots.includes('Sitemap: https://mnema.app/sitemap.xml'), 'Missing sitemap discovery in robots.txt');
    check(!/Disallow:\s*\//u.test(robots), 'Do not block crawling needed to read noindex or public page assets');
    console.log(`OK: ${pages.length} public documents, self canonicals, sitemap, current favicon and private CSR shell.`);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    try { await verifyPublicSeo(resolve(process.argv[2] ?? 'dist/mnema-frontend')); }
    catch (error) { console.error(`Public SEO verification failed: ${error.message}`); process.exitCode = 1; }
}
