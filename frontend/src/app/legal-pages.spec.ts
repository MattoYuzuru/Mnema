import { Type } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { PRIVACY_SECTIONS, PrivacyPageComponent } from './privacy-page.component';
import { TERMS_SECTIONS, TermsPageComponent } from './terms-page.component';
import { LEGAL_EFFECTIVE_DATE, LEGAL_OPERATOR } from './shared/legal-operator';
import { SUPPORT_CONTACT, telegramContact } from './shared/support-contact';

function render(page: Type<unknown>): HTMLElement {
    TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: SUPPORT_CONTACT, useValue: telegramContact('MnemaSupportBot') }] });
    const fixture = TestBed.createComponent(page);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
}

function text(element: Element | null | undefined): string {
    return (element?.textContent ?? '').replace(/\s+/g, ' ').trim();
}

describe('Personal data policy page', () => {
    it('is titled as the policy, dated, and shows the operator block from the single requisites file', () => {
        const root = render(PrivacyPageComponent);
        expect(text(root.querySelector('h1'))).toBe('Политика обработки персональных данных');
        expect(text(root.querySelector('.last-updated'))).toContain(LEGAL_EFFECTIVE_DATE);
        expect(root.querySelector('.legal-page')?.getAttribute('lang')).toBe('ru');
        const block = text(root.querySelector('app-legal-operator-block'));
        for (const value of [LEGAL_OPERATOR.name, LEGAL_OPERATOR.inn, LEGAL_OPERATOR.ogrnip, LEGAL_OPERATOR.email]) expect(block).toContain(value);
        expect(root.querySelector('app-legal-operator-block a[href^="mailto:"]')?.getAttribute('href')).toBe('mailto:matvei.riabushkin@yandex.ru');
        expect(root.querySelector('app-legal-operator-block a[href^="mailto:"]')?.textContent).toBe('matvei.riabushkin@yandex.ru');
    });

    it('has a table of contents whose every entry resolves to a heading, in document order', () => {
        const root = render(PrivacyPageComponent);
        const headings = [...root.querySelectorAll('section > h2')];
        expect(headings.map(heading => heading.id)).toEqual(PRIVACY_SECTIONS.map(section => section.id));
        expect(headings.map(heading => text(heading))).toEqual(PRIVACY_SECTIONS.map(section => section.title));
        expect(root.querySelectorAll('nav.toc li')).toHaveLength(PRIVACY_SECTIONS.length);
        for (const section of root.querySelectorAll('section')) expect(section.getAttribute('aria-labelledby')).toBe(section.querySelector('h2')?.id);
    });

    it('lists purposes with data, legal basis and retention, and discloses the Cloudflare cross-border transfer', () => {
        const root = render(PrivacyPageComponent);
        const tables = [...root.querySelectorAll('table')];
        const purposeTable = tables.find(table => text(table.querySelector('caption')).startsWith('Цели'))!;
        expect([...purposeTable.querySelectorAll('thead th')].map(header => text(header))).toEqual(['Цель', 'Данные', 'Основание', 'Срок']);
        expect(purposeTable.querySelectorAll('tbody tr').length).toBeGreaterThanOrEqual(6);
        const body = text(root);
        expect(body).toContain('п. 5 ч. 1 ст. 6');
        expect(body).toContain('Cloudflare, Inc.');
        expect(body).toContain('США');
        expect(body).toContain('не более двух последних копий');
        expect(body).toContain('до 30 дней');
        expect(root.querySelector('a[href="https://www.cloudflare.com/turnstile-privacy-policy/"]')).not.toBeNull();
        for (const wrapper of root.querySelectorAll('.table-scroll')) {
            expect(wrapper.getAttribute('tabindex')).toBe('0');
            expect(wrapper.getAttribute('role')).toBe('region');
            expect(wrapper.getAttribute('aria-label')).toBeTruthy();
        }
    });

    it('states truthfully that AI functions are not provided, that consent is not a precondition and that no analytics runs', () => {
        const body = text(render(PrivacyPageComponent));
        expect(body).toContain('не предоставляются');
        expect(body).toContain('не применяет аналитику');
        expect(body).toContain('Самостоятельное удаление аккаунта в интерфейсе пока не включено');
        expect(body).toContain('10 рабочих дней');
        expect(body).not.toMatch(/DeepSeek|OpenRouter|Gemini|GigaChat/);
    });

    it('names Yandex Cloud and the server provider as current Russian processors, and states the hashing truthfully', () => {
        const root = render(PrivacyPageComponent);
        const recipients = text(root.querySelector('#recipients')!.closest('section'));
        expect(recipients).toContain('АО «Селектел»');
        expect(recipients).toContain('ООО «Яндекс.Облако»');
        expect(recipients).toContain('Postbox');
        expect(recipients).toContain('Object Storage');
        expect(recipients).toContain('Telegram FZ-LLC');
        expect(recipients).not.toContain('Telegram хранит');
        const body = text(root);
        expect(body).toContain('хэши SHA-256');
        expect(body).toContain('до 35 минут');
        expect(body).toContain('ключевые хэши (HMAC)');
        expect(body).not.toContain('необратим');
        expect(body).not.toContain('отдельном экране');
        expect(body).toContain('Сейчас ни для одной цели мы не запрашиваем согласие');
    });

    it('lists the browser storage keys and the server-side promo state', () => {
        const body = text(render(PrivacyPageComponent));
        for (const key of ['mnema.identity.access', 'mnema.promo-popup.session', 'mnema:exercise-review:']) expect(body).toContain(key);
        expect(body).toContain('Состояние промо-предложения');
        expect(body).toContain('получаем, но не сохраняем');
    });

    it('opens external references in a new tab with an announced destination', () => {
        const root = render(PrivacyPageComponent);
        const external = [...root.querySelectorAll('a[href^="https://"]')].filter(link => !link.closest('app-support-contact'));
        expect(external.length).toBeGreaterThan(0);
        for (const link of external) {
            expect(link.getAttribute('rel')).toBe('noopener noreferrer');
            expect(text(link)).toContain('откроется в новой вкладке');
        }
    });
});

describe('Terms of service page', () => {
    it('is titled as the agreement, shows the operator block and links the policy', () => {
        const root = render(TermsPageComponent);
        expect(text(root.querySelector('h1'))).toBe('Пользовательское соглашение');
        expect(text(root.querySelector('.last-updated'))).toContain(LEGAL_EFFECTIVE_DATE);
        const block = text(root.querySelector('app-legal-operator-block'));
        for (const value of [LEGAL_OPERATOR.name, LEGAL_OPERATOR.inn, LEGAL_OPERATOR.ogrnip, LEGAL_OPERATOR.email]) expect(block).toContain(value);
        expect(root.querySelector('a[href="/privacy"]')).not.toBeNull();
        expect(root.querySelector('a[href="mailto:matvei.riabushkin@yandex.ru"]')?.textContent).toBe('matvei.riabushkin@yandex.ru');
    });

    it('has a table of contents that resolves to headings and covers the required agreement topics', () => {
        const root = render(TermsPageComponent);
        const headings = [...root.querySelectorAll('section > h2')];
        expect(headings.map(heading => heading.id)).toEqual(TERMS_SECTIONS.map(section => section.id));
        expect(headings.map(heading => text(heading))).toEqual(TERMS_SECTIONS.map(section => section.title));
        expect(root.querySelectorAll('nav.toc li')).toHaveLength(TERMS_SECTIONS.length);
        const titles = headings.map(heading => text(heading));
        for (const topic of ['Предмет', 'Аккаунт', 'Тарифы', 'Ваш контент', 'Запрещённое использование', 'искусственного интеллекта', 'Ответственность', 'Применимое право']) {
            expect(titles.some(title => title.includes(topic)), topic).toBe(true);
        }
    });

    it('keeps payments closed, the AI caveat and the consumer-law proviso explicit', () => {
        const body = text(render(TermsPageComponent));
        expect(body).toContain('тарифе Free');
        expect(body).toContain('пока не открыты');
        expect(body).toContain('могут быть неточными');
        expect(body).toContain('Закона РФ «О защите прав потребителей»');
        expect(body).toContain('законодательством Российской Федерации');
    });
});
