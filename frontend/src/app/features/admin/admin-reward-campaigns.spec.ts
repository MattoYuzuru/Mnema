import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';

import { AuthService } from '../../auth.service';
import { AdminSession } from './admin-api.service';
import { AdminRewardCampaignsComponent } from './admin-reward-campaigns.component';
import { campaign, issuance, ownerId } from './admin-test-data';
import { parseCampaign, parseCampaignPage, parseIssuancePage } from './admin.models';

const URL = '/api/admin/promo-campaigns';
const other = { ...campaign, campaignId: '40000000-0000-4000-8000-000000000002', offerType: 'TIER_DAYS', percent: null, plan: 'PLUS', days: 30, enabled: true, issued: 5, redeemed: 0, issuedLast24h: 0 };
const plain = (text: string | null): string => (text ?? '').replace(/\u00a0/gu, ' ').replace(/\s+/gu, ' ').trim();

describe('the console section «Награда за «Путь»» (OB-402)', () => {
    let fixture: ComponentFixture<AdminRewardCampaignsComponent>;
    let http: HttpTestingController;
    let root: HTMLElement;

    const settle = async (): Promise<void> => { await Promise.resolve(); await Promise.resolve(); await Promise.resolve(); fixture.detectChanges(); await fixture.whenStable(); fixture.detectChanges(); };
    const open = async (campaigns: readonly object[] = [campaign, other]): Promise<void> => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(),
            { provide: AuthService, useValue: { status: () => 'authenticated', user: () => ({ accountId: ownerId }) } }] });
        TestBed.inject(AdminSession).access.set({ owner: true, permissions: { events: true, moderation: true, promos: true, support: true } });
        http = TestBed.inject(HttpTestingController);
        fixture = TestBed.createComponent(AdminRewardCampaignsComponent);
        root = fixture.nativeElement;
        fixture.detectChanges();
        http.expectOne(URL).flush({ campaigns });
        await settle();
    };
    const button = (text: string, within: ParentNode = root): HTMLButtonElement => {
        const found = [...within.querySelectorAll<HTMLButtonElement>('button')].find(item => plain(item.textContent) === text);
        expect(found, text).toBeDefined();
        return found!;
    };
    const click = async (text: string, within: ParentNode = root): Promise<void> => { button(text, within).click(); fixture.detectChanges(); await settle(); };
    const fill = (name: string, value: string): void => {
        const field = root.querySelector<HTMLInputElement | HTMLSelectElement>(`[formControlName="${name}"]`)!;
        expect(field, name).not.toBeNull();
        field.value = value;
        field.dispatchEvent(new Event(field.tagName === 'SELECT' ? 'change' : 'input'));
        fixture.detectChanges();
    };
    const rows = (): HTMLElement[] => [...root.querySelectorAll<HTMLElement>('li.campaign')];
    afterEach(() => { http?.verify(); fixture?.destroy(); });

    describe('the campaigns', () => {
        it('lists each with its offer, whether it is enabled and how many codes were issued, redeemed and issued in 24 hours', async () => {
            await open();
            expect(rows()).toHaveLength(2);
            expect(plain(rows()[0].querySelector('.campaign-offer')!.textContent)).toBe('Скидка 10 % на любой тариф · Выключена');
            expect(plain(rows()[0].querySelector('.hint')!.textContent)).toContain('Выдано: 12 · Использовано: 3 · За 24 часа: 2');
            expect(plain(rows()[1].querySelector('.campaign-offer')!.textContent)).toBe('Plus на 30 дней · Включена');
            expect(button('Выключить', rows()[1])).toBeDefined();
            expect(button('Включить', rows()[0])).toBeDefined();
        });

        it('names the plan a discount applies to', async () => {
            await open([{ ...campaign, plan: 'PRO', percent: 15 }]);
            expect(plain(rows()[0].querySelector('.campaign-offer')!.textContent)).toContain('Скидка 15 % на Pro');
        });

        it('says there are none yet, and that a new one starts disabled', async () => {
            await open([]);
            expect(rows()).toHaveLength(0);
            expect(root.textContent).toContain('Кампаний пока нет. Создайте первую: она создаётся выключенной.');
        });

        it('shows a failed read with a retry that reads again, and keeps the form', async () => {
            TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(),
                { provide: AuthService, useValue: { status: () => 'authenticated', user: () => ({ accountId: ownerId }) } }] });
            http = TestBed.inject(HttpTestingController);
            fixture = TestBed.createComponent(AdminRewardCampaignsComponent);
            root = fixture.nativeElement;
            fixture.detectChanges();
            http.expectOne(URL).flush({}, { status: 503, statusText: 'Unavailable' });
            await settle();
            expect(root.querySelector('[role="alert"]')!.textContent).toContain('Список кампаний не загрузился');
            expect(root.querySelector('form')).not.toBeNull();
            await click('Повторить');
            http.expectOne(URL).flush({ campaigns: [campaign] });
            await settle();
            expect(rows()).toHaveLength(1);
        });

        it('drops an answer outside the contract as an error, not as a list', async () => {
            TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(),
                { provide: AuthService, useValue: { status: () => 'authenticated', user: () => ({ accountId: ownerId }) } }] });
            http = TestBed.inject(HttpTestingController);
            fixture = TestBed.createComponent(AdminRewardCampaignsComponent);
            root = fixture.nativeElement;
            fixture.detectChanges();
            http.expectOne(URL).flush({ campaigns: [{ ...campaign, offerType: 'GIFT' }] });
            await settle();
            expect(rows()).toHaveLength(0);
            expect(root.querySelector('[role="alert"]')).not.toBeNull();
        });
    });

    describe('creating a campaign', () => {
        it('sends a discount with an Idempotency-Key, says it was created disabled, and reads the list again', async () => {
            await open([]);
            fill('percent', '15');
            fill('plan', 'PRO');
            await click('Создать кампанию');
            const request = http.expectOne(request => request.method === 'POST' && request.url === URL);
            expect(request.request.body).toEqual({ type: 'PATH_REWARD', offerType: 'DISCOUNT_PERCENT', percent: 15, plan: 'PRO', days: null });
            expect(request.request.headers.get('Idempotency-Key')).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u);
            request.flush({ ...campaign, percent: 15, plan: 'PRO' });
            await settle();
            http.expectOne(URL).flush({ campaigns: [{ ...campaign, percent: 15, plan: 'PRO' }] });
            await settle();
            expect(root.textContent).toContain('Кампания создана выключенной');
            expect(rows()).toHaveLength(1);
        });

        it('sends a discount that applies to any plan with a null plan', async () => {
            await open([]);
            await click('Создать кампанию');
            const request = http.expectOne(request => request.method === 'POST');
            expect(request.request.body).toEqual({ type: 'PATH_REWARD', offerType: 'DISCOUNT_PERCENT', percent: 10, plan: null, days: null });
            request.flush(campaign);
            await settle();
            http.expectOne(URL).flush({ campaigns: [campaign] });
        });

        it('asks for a plan and days for a tier, and sends exactly those', async () => {
            await open([]);
            fill('offerType', 'TIER_DAYS');
            expect(root.querySelector('[formControlName="percent"]')).toBeNull();
            expect((root.querySelector('[formControlName="plan"]') as HTMLSelectElement).value).toBe('PLUS');
            fill('plan', 'PRO');
            fill('days', '14');
            await click('Создать кампанию');
            const request = http.expectOne(request => request.method === 'POST');
            expect(request.request.body).toEqual({ type: 'PATH_REWARD', offerType: 'TIER_DAYS', percent: null, plan: 'PRO', days: 14 });
            request.flush({ ...other, enabled: false, days: 14, plan: 'PRO' });
            await settle();
            http.expectOne(URL).flush({ campaigns: [] });
        });

        it.each([
            ['a discount of zero', { percent: '0' }, 'от 1 до 90'],
            ['a discount over ninety', { percent: '91' }, 'от 1 до 90'],
            ['a fractional discount', { percent: '10.5' }, 'от 1 до 90']
        ])('refuses %s before anything is sent', async (_name, values, message) => {
            await open([]);
            for (const [name, value] of Object.entries(values)) fill(name, value);
            await click('Создать кампанию');
            http.expectNone(request => request.method === 'POST');
            expect(root.querySelector('[role="alert"]')!.textContent).toContain(message);
        });

        it.each([['0'], ['367'], ['1.5']])('refuses a tier for %s days before anything is sent', async days => {
            await open([]);
            fill('offerType', 'TIER_DAYS');
            fill('days', days);
            await click('Создать кампанию');
            http.expectNone(request => request.method === 'POST');
            expect(root.querySelector('[role="alert"]')!.textContent).toContain('от 1 до 366');
        });

        it('freezes the command after an unknown outcome and retries exactly the same body and key', async () => {
            await open([]);
            fill('percent', '20');
            await click('Создать кампанию');
            const first = http.expectOne(request => request.method === 'POST');
            const key = first.request.headers.get('Idempotency-Key');
            first.error(new ProgressEvent('error'));
            await settle();
            expect(root.textContent).toContain('Результат не подтверждён');
            expect((root.querySelector('[formControlName="offerType"]') as HTMLSelectElement).disabled).toBe(true);
            await click('Повторить ту же команду');
            const second = http.expectOne(request => request.method === 'POST');
            expect(second.request.body).toEqual(first.request.body);
            expect(second.request.headers.get('Idempotency-Key')).toBe(key);
            second.flush({ ...campaign, percent: 20 });
            await settle();
            http.expectOne(URL).flush({ campaigns: [{ ...campaign, percent: 20 }] });
            await settle();
            expect(root.textContent).not.toContain('Повторить ту же команду');
        });

        it('says a refusal in words and lets the owner correct the fields', async () => {
            await open([]);
            await click('Создать кампанию');
            http.expectOne(request => request.method === 'POST').flush({ code: 'INVALID_REQUEST' }, { status: 400, statusText: 'Bad Request' });
            await settle();
            expect(root.querySelector('[role="alert"]')!.textContent).toContain('Кампания не создана');
            expect((root.querySelector('[formControlName="percent"]') as HTMLInputElement).disabled).toBe(false);
        });

        it('keeps the chosen plan of a tier after a refused create (disabling and enabling the form is not a change of the offer), and the retry carries it', async () => {
            await open([]);
            fill('offerType', 'TIER_DAYS');
            fill('plan', 'PRO');
            await click('Создать кампанию');
            http.expectOne(request => request.method === 'POST').flush({ code: 'INVALID_REQUEST' }, { status: 400, statusText: 'Bad Request' });
            await settle();
            expect(root.querySelector('[role="alert"]')!.textContent).toContain('Кампания не создана');
            expect((root.querySelector('[formControlName="plan"]') as HTMLSelectElement).value).toBe('PRO');
            await click('Создать кампанию');
            const retry = http.expectOne(request => request.method === 'POST');
            expect(retry.request.body).toEqual({ type: 'PATH_REWARD', offerType: 'TIER_DAYS', percent: null, plan: 'PRO', days: 30 });
            retry.flush({ ...other, enabled: false, plan: 'PRO' });
            await settle();
            http.expectOne(URL).flush({ campaigns: [] });
        });
    });

    describe('enabling and disabling', () => {
        it('asks first, in the page, and sends nothing until the owner answers; the question takes the focus', async () => {
            await open();
            await click('Включить', rows()[0]);
            const box = rows()[0].querySelector<HTMLElement>('[role="group"]')!;
            expect(box.textContent).toContain('Включить эту кампанию?');
            expect(plain(box.textContent)).toContain('Скидка 10 % на любой тариф');
            expect(document.activeElement).toBe(box);
            http.expectNone(request => request.method === 'PATCH');
        });

        it('cancels without a request and returns the focus to the button that asked', async () => {
            await open();
            await click('Включить', rows()[0]);
            await click('Отмена');
            expect(rows()[0].querySelector('[role="group"]')).toBeNull();
            http.expectNone(request => request.method === 'PATCH');
            await vi.waitFor(() => expect(document.activeElement).toBe(button('Включить', rows()[0])));
        });

        it('enables on the answer yes, with the enabled flag only, and shows the new state', async () => {
            await open();
            await click('Включить', rows()[0]);
            await click('Да, включить');
            const request = http.expectOne(request => request.method === 'PATCH');
            expect(request.request.url).toBe(`${URL}/${campaign.campaignId}`);
            expect(request.request.body).toEqual({ enabled: true });
            request.flush({ ...campaign, enabled: true });
            await settle();
            expect(plain(rows()[0].querySelector('.campaign-offer')!.textContent)).toContain('Включена');
            expect(root.textContent).toContain('Кампания включена');
            expect(rows()[0].querySelector('[role="group"]')).toBeNull();
        });

        it('disables with the words that codes already issued stay valid', async () => {
            await open();
            await click('Выключить', rows()[1]);
            expect(rows()[1].querySelector('[role="group"]')!.textContent).toContain('Уже выданные коды действуют до своего срока');
            await click('Да, выключить');
            const request = http.expectOne(request => request.method === 'PATCH');
            expect(request.request.body).toEqual({ enabled: false });
            request.flush({ ...other, enabled: false });
            await settle();
            expect(root.textContent).toContain('Выданные коды действуют до своего срока');
            expect(plain(rows()[1].querySelector('.campaign-offer')!.textContent)).toContain('Выключена');
        });

        it('explains the 409 PROMO_CAMPAIGN_ACTIVE: another campaign is enabled, which one, and what to do; the list is read again', async () => {
            await open();
            await click('Включить', rows()[0]);
            await click('Да, включить');
            http.expectOne(request => request.method === 'PATCH').flush({ code: 'PROMO_CAMPAIGN_ACTIVE' }, { status: 409, statusText: 'Conflict' });
            await settle();
            const message = plain(root.querySelector('[role="alert"]')!.textContent);
            expect(message).toContain('Уже включена другая кампания: Plus на 30 дней');
            expect(message).toContain('сначала выключите её');
            http.expectOne(URL).flush({ campaigns: [campaign, other] });
            await settle();
            expect(plain(rows()[0].querySelector('.campaign-offer')!.textContent)).toContain('Выключена');
        });

        it('says an ordinary refusal in words, and treats an unknown outcome as a command to repeat', async () => {
            await open();
            await click('Включить', rows()[0]);
            await click('Да, включить');
            http.expectOne(request => request.method === 'PATCH').flush({}, { status: 404, statusText: 'Not Found' });
            await settle();
            expect(root.querySelector('[role="alert"]')!.textContent).toContain('Команда отклонена');
            await click('Включить', rows()[0]);
            await click('Да, включить');
            http.expectOne(request => request.method === 'PATCH').error(new ProgressEvent('error'));
            await settle();
            expect(root.textContent).toContain('Результат не подтверждён');
            await click('Повторить ту же команду');
            http.expectOne(request => request.method === 'PATCH').flush({ ...campaign, enabled: true });
            await settle();
            expect(plain(rows()[0].querySelector('.campaign-offer')!.textContent)).toContain('Включена');
        });
    });

    describe('the issued codes of a campaign', () => {
        const issuancesUrl = (id: string): string => `${URL}/${id}/issuances`;

        it('opens on demand, shows the hint, the dates and whether it was redeemed, never a code, and closes again', async () => {
            await open();
            await click('Выдачи', rows()[0]);
            http.expectOne(request => request.url === issuancesUrl(campaign.campaignId)).flush({
                issuances: [issuance, { ...issuance, codeHint: 'Q2…ZM', issuedAt: '2026-10-21T10:00:00Z', expiresAt: '2026-10-28T10:00:00Z', redeemedAt: '2026-10-22T09:00:00Z' }], next: null
            });
            await settle();
            const table = rows()[0].querySelector('table')!;
            expect([...table.querySelectorAll('tbody tr')].map(row => plain(row.querySelector('th')!.textContent))).toEqual(['K7…RT', 'Q2…ZM']);
            expect([...table.querySelectorAll('tbody tr')].map(row => plain(row.querySelectorAll('td')[2].textContent))).toEqual(['Нет', '22 окт. 2026 г., 12:00']);
            expect(root.querySelector('button[aria-expanded="true"]')).not.toBeNull();
            await click('Скрыть выдачи');
            expect(rows()[0].querySelector('table')).toBeNull();
        });

        it('says when a campaign has issued nothing', async () => {
            await open();
            await click('Выдачи', rows()[0]);
            http.expectOne(request => request.url === issuancesUrl(campaign.campaignId)).flush({ issuances: [], next: null });
            await settle();
            expect(rows()[0].textContent).toContain('Эта кампания ещё ничего не выдала.');
        });

        it('continues on demand through the shared auto-load with the opaque cursor, never a «Показать ещё» button', async () => {
            await open();
            await click('Выдачи', rows()[0]);
            http.expectOne(request => request.url === issuancesUrl(campaign.campaignId)).flush({ issuances: [issuance], next: 'cursor-1' });
            await settle();
            expect(plain(root.textContent)).not.toMatch(/показать ещё|далее/iu);
            fixture.debugElement.query(By.css('li.campaign app-auto-load')).triggerEventHandler('loadNext');
            const next = http.expectOne(request => request.url === issuancesUrl(campaign.campaignId) && request.params.get('after') === 'cursor-1');
            next.flush({ issuances: [{ ...issuance, codeHint: 'Z9…AA', issuedAt: '2026-10-20T10:00:00Z', expiresAt: '2026-10-27T10:00:00Z' }], next: null });
            await settle();
            expect(rows()[0].querySelectorAll('tbody tr')).toHaveLength(2);
        });

        it('shows a failed page with an explicit retry and keeps the rows', async () => {
            await open();
            await click('Выдачи', rows()[0]);
            http.expectOne(request => request.url === issuancesUrl(campaign.campaignId)).flush({ issuances: [issuance], next: 'cursor-1' });
            await settle();
            fixture.debugElement.query(By.css('li.campaign app-auto-load')).triggerEventHandler('loadNext');
            http.expectOne(request => request.params.get('after') === 'cursor-1').flush({}, { status: 503, statusText: 'Unavailable' });
            await settle();
            expect(rows()[0].querySelectorAll('tbody tr')).toHaveLength(1);
            expect(rows()[0].querySelector('app-auto-load [role="alert"]')!.textContent).toContain('Следующие выдачи не загрузились');
        });

        it('shows a failed first page with a retry, and one list at a time', async () => {
            await open();
            await click('Выдачи', rows()[0]);
            http.expectOne(request => request.url === issuancesUrl(campaign.campaignId)).flush({}, { status: 503, statusText: 'Unavailable' });
            await settle();
            expect(rows()[0].querySelector('[role="alert"]')!.textContent).toContain('Выдачи не загрузились');
            await click('Выдачи', rows()[1]);
            http.expectOne(request => request.url === issuancesUrl(other.campaignId)).flush({ issuances: [issuance], next: null });
            await settle();
            expect(rows()[0].querySelector('table')).toBeNull();
            expect(rows()[1].querySelector('table')).not.toBeNull();
        });
    });

    it('forgets everything it holds when the rights end (403)', async () => {
        await open();
        await click('Включить', rows()[0]);
        await click('Да, включить');
        http.expectOne(request => request.method === 'PATCH').flush({}, { status: 403, statusText: 'Forbidden' });
        await settle();
        expect(rows()).toHaveLength(0);
        expect(root.querySelector('[role="alert"]')!.textContent).toContain('Доступ закрыт');
    });

    it('protects the page on close while a command runs or the form is dirty', async () => {
        await open([]);
        const clean = new Event('beforeunload', { cancelable: true });
        window.dispatchEvent(clean);
        expect(clean.defaultPrevented).toBe(false);
        fill('percent', '12');
        (root.querySelector('[formControlName="percent"]') as HTMLInputElement).dispatchEvent(new Event('input'));
        fixture.componentInstance['form'].markAsDirty();
        const dirty = new Event('beforeunload', { cancelable: true });
        window.dispatchEvent(dirty);
        expect(dirty.defaultPrevented).toBe(true);
    });
});

describe('the wire shapes of the reward campaigns (contracts/usage/promo.json)', () => {
    it('parses a campaign, a page of them and a page of issuances', () => {
        expect(parseCampaign(campaign)).toEqual(campaign);
        expect(parseCampaignPage({ campaigns: [campaign, other] }).campaigns).toHaveLength(2);
        expect(parseIssuancePage({ issuances: [issuance], next: 'abc' })).toEqual({ issuances: [issuance], next: 'abc' });
    });

    it.each([
        ['an offer type outside the contract', { offerType: 'GIFT' }],
        ['a type other than PATH_REWARD', { type: 'PROMO' }],
        ['a discount without a percent', { percent: null }],
        ['a discount over ninety', { percent: 91 }],
        ['a discount that carries days', { days: 7 }],
        ['a tier without a plan', { offerType: 'TIER_DAYS', percent: null, days: 30, plan: null }],
        ['a tier with a percent', { offerType: 'TIER_DAYS', percent: 10, days: 30, plan: 'PLUS' }],
        ['a tier of 367 days', { offerType: 'TIER_DAYS', percent: null, days: 367, plan: 'PLUS' }],
        ['more codes redeemed than issued', { issued: 1, redeemed: 2 }],
        ['a count as text', { issued: '12' }],
        ['a negative count', { issuedLast24h: -1 }],
        ['an id that is not a UUID', { campaignId: 'one' }]
    ])('refuses %s', (_name, patch) => {
        expect(() => parseCampaign({ ...campaign, ...patch })).toThrow();
    });

    it.each([
        ['an end before the beginning', { expiresAt: '2026-10-22T18:00:00Z' }],
        ['a redemption that is not a timestamp', { redeemedAt: 'yesterday' }],
        ['no hint', { codeHint: undefined }]
    ])('refuses an issuance with %s', (_name, patch) => {
        expect(() => parseIssuancePage({ issuances: [{ ...issuance, ...patch }], next: null })).toThrow();
    });

    it('refuses an empty cursor and a list that is not a list', () => {
        expect(() => parseIssuancePage({ issuances: [], next: '' })).toThrow();
        expect(() => parseCampaignPage({ campaigns: 'none' })).toThrow();
    });
});
