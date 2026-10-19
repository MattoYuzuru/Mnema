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
        // 1.8: onboarding, «Путь» and the weekly rhythm (OB-002); 1.7: admitted AI flows; 1.6: interests and recommendations; 1.5: catalogue and author aggregates; 1.4: shared and public decks, complaints; 1.3: plain-language edit; 1.2: the public profile became a separate consent (section 15); 1.1 made avatars and material media active
        expect(text(root.querySelector('.last-updated'))).toBe('Действует с 19 октября 2026 · версия 1.8');
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

    it('discloses admitted AI flows and separate voice consent without adding tracking cookies', () => {
        const body = text(render(PrivacyPageComponent));
        expect(body).toContain('Распознавание голоса запускается только после отдельного согласия');
        expect(body).toContain('DeepSeek');
        expect(body).toContain('OpenRouter');
        expect(body).toContain('Google (Gemini API');
        expect(body).toContain('Рекламных и отслеживающих cookie нет');
        expect(body).toContain('Действия в Сообществе учитываются на сервере');
        expect(body).toContain('Самостоятельное удаление аккаунта в интерфейсе пока не включено');
        expect(body).toContain('10 рабочих дней');
        expect(body).not.toContain('GigaChat');
        expect(body).not.toContain('данные внешним ИИ-сервисам не передаются');
    });

    it('names Yandex Cloud and the server provider as current Russian processors, and states the hashing truthfully', () => {
        const root = render(PrivacyPageComponent);
        const recipients = text(root.querySelector('#recipients')!.closest('section'));
        expect(recipients).toContain('АО «Селектел»');
        expect(recipients).toContain('ООО «Яндекс.Облако»');
        expect(recipients).toContain('Postbox');
        expect(recipients).toContain('Object Storage');
        expect(recipients).toContain('аватары профиля');
        expect(recipients).toContain('в течение 7 дней');
        // material media is live: stored in Yandex Object Storage, processed on Mnema's own server, removed by the 7-day grace
        expect(recipients).toContain('файлы (изображения, аудио, видео), которые вы прикрепляете к материалам');
        expect(recipients).toContain('обрабатываются на сервере Mnema, внешним сервисам обработки файлы не передаются');
        expect(recipients).toContain('удаляется из хранилища автоматически примерно через 7 дней');
        expect(recipients).toContain('При удалении аккаунта аватар и загруженные файлы удаляются вместе с ним');
        expect(recipients).not.toContain('сейчас не предоставляется');
        expect(recipients).toContain('Telegram FZ-LLC');
        expect(recipients).not.toContain('Telegram хранит');
        const body = text(root);
        expect(body).toContain('хэши SHA-256');
        expect(body).toContain('до 35 минут');
        expect(body).toContain('ключевые хэши (HMAC)');
        expect(body).not.toContain('необратим');
        expect(body).not.toContain('отдельном экране');
        expect(body).toContain('Сейчас мы запрашиваем согласие на распознавание голоса и на публичный профиль');
        expect(body).not.toContain('Сейчас ни для одной цели мы не запрашиваем согласие');
    });

    it('gives the public profile its own section with the approved consent wording and links it from section 2', () => {
        const root = render(PrivacyPageComponent);
        const heading = root.querySelector('h2#public-profile')!;
        expect(text(heading)).toBe('15. Публичный профиль и колоды сообщества');
        const section = heading.closest('section')!;
        expect(section.getAttribute('aria-labelledby')).toBe('public-profile');
        // Public profile, shared decks, recommendations and complaints.
        expect(section.querySelectorAll('p')).toHaveLength(4);
        const paragraph = text(section);
        for (const phrase of ['Профиль виден другим, только если вы включили его в настройках.', 'Тогда всем показывается ваш логин и то, что вы отметили: имя, фото, «О себе».',
            'ст. 10.1 152-ФЗ', 'Оно действует до отзыва', 'данные показываются только на mnema.app', 'другие цели и передача третьим лицам запрещены',
            'Отозвать согласие можно переключателем в профиле или письмом оператору: показ прекращается сразу.']) expect(paragraph).toContain(phrase);
        for (const phrase of ['Колоды, которыми вы делитесь.', 'только вы, все, у кого есть ссылка, приглашённые люди или все пользователи',
            'Ни ваши ответы, ни прогресс, ни имена учеников автору не показываются.', 'Жалобы.', 'мы храним вашу жалобу, аккаунт и решение по ней до 3 лет']) expect(paragraph).toContain(phrase);
        // The approved catalogue and recommendations wording ships locally with epic 4.
        expect(paragraph).toContain('общие цифры');
        expect(paragraph).toContain('Сообщество и рекомендации');
        expect(paragraph).toContain('до 13 месяцев');
        expect(paragraph).toContain('Интересы можно посмотреть и сбросить в профиле');
        expect(paragraph).toContain('Содержимое ваших личных колод для рекомендаций не используется');
        expect(text(root)).toContain('Действия в Сообществе');
        expect(text(root)).toContain('Учебная цель: подсказки, рекомендация тарифа и подбор колод');
        const purposes = text(root.querySelector('#purposes')!.closest('section'));
        expect(purposes).toContain('Рассмотрение жалоб на колоды и профили');
        expect(purposes).toContain('До 3 лет с подачи жалобы');
        const data = text(root.querySelector('#data')!.closest('section'));
        expect(data).toContain('другим не показываются, пока вы не включили публичный профиль (см. раздел 15 «Публичный профиль и колоды сообщества»)');
        expect(data).not.toContain('доступен без входа');
        const links = [...root.querySelectorAll('a[href$="#public-profile"]')].filter(link => !link.closest('nav'));
        expect(links).toHaveLength(2);
        links.forEach(link => expect(text(link)).toBe('Публичный профиль и колоды сообщества'));
        expect(text(root.querySelector('#sources'))).toBe('17. Нормативная база');
        expect(text(root.querySelector('#onboarding'))).toBe('16. Знакомство с сервисом, «Путь» и ритм');
        expect(root.querySelector('nav.toc a[href$="#public-profile"]')).not.toBeNull();
    });

    it('keeps every data category, legal basis, retention period and the Russian-storage rule', () => {
        const root = render(PrivacyPageComponent);
        const tables = [...root.querySelectorAll('table')];
        const dataTable = tables.find(table => text(table.querySelector('caption')).startsWith('Категории'))!;
        expect([...dataTable.querySelectorAll('thead th')].map(header => text(header))).toEqual(['Категория', 'Состав', 'Источник']);
        expect([...dataTable.querySelectorAll('tbody th')].map(header => text(header))).toEqual(['Данные аккаунта', 'Вход через стороннего провайдера', 'Профиль', 'Учебные данные',
            'Тариф и использование', 'Учебная цель', 'Состояние промо-предложения', 'Технические данные', 'Действия в Сообществе', 'Интересы', 'Знакомство с сервисом и «Путь»', 'Жалобы', 'Обращения в поддержку']);
        const purposes = tables.find(table => text(table.querySelector('caption')).startsWith('Цели'))!;
        expect(purposes.querySelectorAll('tbody tr')).toHaveLength(14);
        for (const row of purposes.querySelectorAll('tbody tr')) expect(row.querySelectorAll('td'), text(row)).toHaveLength(3);
        const purposeText = text(purposes);
        for (const purpose of ['Регистрация, вход, ведение аккаунта и профиля', 'Работа сервиса', 'Создание и правка материалов и упражнений, проверка ответов, озвучивание по вашему запросу', 'Распознавание записи голоса',
            'Тариф, лимиты использования, промокоды и скидки', 'Учебная цель: подсказки, рекомендация тарифа и подбор колод', 'Знакомство с сервисом, «Путь» и недельный ритм', 'Подбор подходящих колод и проверка рекомендаций',
            'Безопасность', 'Служебные письма', 'Поддержка и обратная связь', 'Рассмотрение жалоб на колоды и профили', 'Резервное копирование и восстановление', 'Выполнение требований закона']) expect(purposeText).toContain(purpose);
        // legal bases per purpose, and the periods of the table
        for (const basis of ['п. 5 ч. 1 ст. 6', 'п. 7 ч. 1 ст. 6', 'п. 2 ч. 1 ст. 6']) expect(purposeText).toContain(basis);
        for (const period of ['до 30 дней', '2 часа', 'до 35 минут', '3 суток', '10 минут', 'до 12 месяцев', 'не более двух последних копий', 'Срок, установленный законом']) expect(purposeText).toContain(period);
        const aiRow = [...purposes.querySelectorAll('tbody tr')].find(row => text(row.querySelector('th')).startsWith('Создание и правка'))!;
        for (const disclosure of ['Провайдер получает только данные своего действия', 'п. 5 ч. 1 ст. 6', '30 дней после последней активности', '90 дней', '180 дней после последнего использования']) expect(text(aiRow)).toContain(disclosure);
        const voiceRow = [...purposes.querySelectorAll('tbody tr')].find(row => text(row.querySelector('th')) === 'Распознавание записи голоса')!;
        for (const disclosure of ['Отдельное согласие на регион обработки', 'п. 1 ч. 1 ст. 6', 'до согласия запись провайдеру не передаётся', 'Запись удаляется после результата или завершения неуспешной обработки', 'до 15 минут']) expect(text(voiceRow)).toContain(disclosure);
        const bases = text(root.querySelector('#bases')!.closest('section'));
        for (const phrase of ['согласие на обработку не нужно', 'ч. 1 ст. 9 152-ФЗ', 'от 24.06.2025 № 156-ФЗ', 'отдельным документом']) expect(bases).toContain(phrase);
        const retention = text(root.querySelector('#retention')!.closest('section'));
        for (const phrase of ['до 30 дней', 'ч. 4–5 ст. 21 152-ФЗ', 'ч. 6 ст. 21', 'шести месяцев', 'не более двух последних копий базы данных', 'до трёх файлов по 10 МБ']) expect(retention).toContain(phrase);
        expect(text(root.querySelector('#localization')!.closest('section'))).toContain('ч. 5 ст. 18 152-ФЗ');
        expect(text(root.querySelector('#general')!.closest('section'))).toContain('ч. 2 ст. 18.1');
    });

    it('keeps every recipient, the full cross-border disclosure and the security measures', () => {
        const root = render(PrivacyPageComponent);
        const recipients = text(root.querySelector('#recipients')!.closest('section'));
        for (const recipient of ['самому оператору', 'АО «Селектел»', 'ООО «Яндекс.Облако»', 'Cloudflare, Inc. (США)', 'Google LLC', 'ООО «Яндекс»', 'GitHub, Inc.', 'Telegram', 'государственным органам',
            'Мы не продаём персональные данные и не передаём их для рекламы']) expect(recipients).toContain(recipient);
        const crossBorder = root.querySelector('#cross-border')!.closest('section')!;
        expect([...crossBorder.querySelectorAll('dl.facts dt')].map(term => text(term))).toEqual(['Получатель', 'Цель', 'Какие данные', 'Когда', 'Роль Cloudflare', 'Уведомление']);
        const facts = text(crossBorder);
        for (const fact of ['101 Townsend St., San Francisco, CA 94107, США', 'dpo@cloudflare.com', 'отпечаток TLS-соединения', 'одноразовый токен проверки', 'секретный ключ сайта',
            'обработчиком по поручению оператора', 'самостоятельным лицом', 'уведомление о намерении осуществлять трансграничную передачу (ч. 3 ст. 12 152-ФЗ)',
            'остаются закрытыми до новой успешной проверки']) expect(facts).toContain(fact);
        const security = text(root.querySelector('#security')!.closest('section'));
        for (const measure of ['HTTPS, HSTS', 'bcrypt', 'Cloudflare Turnstile', 'недоступна из интернета', 'SHA-256', 'HMAC', 'ч. 3.1 ст. 21 152-ФЗ']) expect(security).toContain(measure);
    });

    it('keeps every right, how to use it, each response deadline, the minors rule and the legal references', () => {
        const root = render(PrivacyPageComponent);
        const rights = root.querySelector('#rights')!.closest('section')!;
        expect(text(rights)).toContain('ст. 14, 20, 21 152-ФЗ');
        expect(rights.querySelectorAll('ul > li')).toHaveLength(4);
        const rightsText = text(rights);
        for (const right of ['получить подтверждение', 'уточнить, заблокировать или удалить данные', 'отозвать согласие', 'потребовать прекратить обработку', 'возмещения убытков и компенсации морального вреда (ст. 17)']) {
            expect(rightsText).toContain(right);
        }
        expect(rights.querySelector('a[href="https://rkn.gov.ru/"]')).not.toBeNull();
        for (const phrase of ['адрес оператора из раздела 1', 'подтвердить личность', 'Telegram-бот поддержки']) expect(rightsText).toContain(phrase);
        const deadlines = text([...rights.querySelectorAll('p')].find(paragraph => text(paragraph).startsWith('Сроки ответа')));
        for (const deadline of ['на запрос о ваших данных', 'до 10 рабочих дней', 'не более чем на 5 рабочих дней', 'мотивированно уведомим', 'до 7 рабочих дней', 'до 30 дней']) {
            expect(deadlines.toLowerCase(), deadline).toContain(deadline.toLowerCase());
        }
        const minors = text(root.querySelector('#minors')!.closest('section'));
        for (const phrase of ['младше 14 лет', 'от 14 до 18 лет', 'родителей или законных представителей', 'мы удалим данные']) expect(minors).toContain(phrase);
        const sources = root.querySelector('#sources')!.closest('section')!;
        expect(sources.querySelector('a[href="https://www.consultant.ru/document/cons_doc_LAW_61801/"]')).not.toBeNull();
        expect(text(sources)).toContain('от 24.06.2025 № 156-ФЗ');
        expect(text(root.querySelector('#changes')!.closest('section'))).toContain('Предыдущие редакции можно запросить у оператора');
    });

    it('lists the browser storage keys and the server-side promo state', () => {
        const body = text(render(PrivacyPageComponent));
        for (const key of ['mnema.identity.access', 'mnema.identity.pkce', 'mnema.promo-popup.session', 'mnema:exercise-review:', 'SameSite=Lax', 'localStorage', 'youtube-nocookie.com']) expect(body).toContain(key);
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

    it('keeps the contract basis, the account duties, the prohibited uses and the dispute rules', () => {
        const root = render(TermsPageComponent);
        const section = (id: string): string => text(root.querySelector(`#${id}`)!.closest('section'));
        for (const phrase of ['ст. 428 ГК РФ', 'ст. 438 ГК РФ', 'вы не даёте согласия на обработку данных']) expect(section('parties')).toContain(phrase);
        expect(section('account')).toContain('не менее 14 лет');
        expect(section('account')).toContain('Один аккаунт предназначен для одного человека');
        expect(root.querySelectorAll('#rules ~ ul > li')).toHaveLength(5);
        expect(section('rules')).toContain('Cloudflare Turnstile');
        expect(section('content')).toContain('остаются вашими');
        expect(section('content')).toContain('срок их хранения, указанный в политике');
        expect(section('content')).toContain('кроме колод, которые вы сами открыли другим');
        for (const phrase of ['По умолчанию колода видна только вам.', 'бесплатно разрешаете им смотреть её в Mnema и добавлять себе копию',
            'Вне Mnema ваши материалы этим разрешением не распространяются.', 'лицензией, запрещающей коммерческое использование (NC)',
            'Мы рассматриваем жалобу в течение 30 дней', 'он скрывается и в копиях, где не был изменён']) expect(section('sharing')).toContain(phrase);
        // The catalogue enabling text is prepared with the recommendations release.
        expect(section('sharing')).toContain('Сообщество» и в вашем профиле');
        expect(section('liability')).toContain('ст. 400 ГК РФ');
        expect(section('liability')).toContain('Наша ответственность ограничена реальным ущербом');
        expect(section('law')).toContain('в течение 30 дней');
        expect(section('law')).toContain('по месту жительства потребителя');
        expect(section('termination')).toContain('потребовать удалить аккаунт');
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

describe('Legal pages wording', () => {
    for (const [name, page] of [['policy', PrivacyPageComponent], ['agreement', TermsPageComponent]] as const) {
        it(`uses no long dash in the ${name}`, () => {
            const root = render(page);
            expect(root.textContent).not.toContain('\u2014');
            expect(root.innerHTML).not.toContain('\u2014');
        });
    }
});
