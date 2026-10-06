import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import { spyObj, type SpyObj } from '../../../testing/mocks';
import { PromoApiService } from '../promo/promo-api.service';
import { PlansApiService, parsePlans } from './plans-api.service';
import { ProfilePlanComponent } from './profile-plan.component';
import { plansBody } from './plans-test-data';

describe('ProfilePlanComponent', () => {
    let api: SpyObj<PlansApiService>;
    let promo: SpyObj<PromoApiService>;

    beforeEach(() => {
        api = spyObj<PlansApiService>({ load: vi.fn().mockName('PlansApiService.load') });
        promo = spyObj<PromoApiService>({ redeem: vi.fn().mockName('PromoApiService.redeem') });
        TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: PlansApiService, useValue: api }, { provide: PromoApiService, useValue: promo }] });
    });

    async function render(): Promise<HTMLElement> {
        const fixture = TestBed.createComponent(ProfilePlanComponent);
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
        return fixture.nativeElement as HTMLElement;
    }

    it('shows the plan, the way to change it and two disabled placeholders', async () => {
        api.load.mockReturnValue(of(parsePlans(plansBody())));
        const root = await render();
        expect(root.querySelector('.plan-line')?.textContent?.trim()).toBe('Тариф Free');
        expect(root.querySelector('a.button')?.getAttribute('href')).toBe('/plans');
        const toggle = root.querySelector<HTMLInputElement>('input[role=switch]')!;
        expect(toggle.disabled).toBe(true);
        expect(toggle.checked).toBe(false);
        expect(root.querySelector('.settings-row.is-switch')?.textContent).toContain('Автопродление');
        expect(root.querySelector('.settings-row:not(.is-switch) span')?.textContent).toBe('Способ оплаты');
        expect(root.querySelector<HTMLButtonElement>('.settings-row button')!.disabled).toBe(true);
        const later = root.querySelector('#profile-plan-later')!;
        expect(later.textContent).toBe('Появится вместе с оплатой.');
        expect(toggle.getAttribute('aria-describedby')).toBe('profile-plan-later');
    });

    it('names the end of a paid period but not the end of the configured month', async () => {
        api.load.mockReturnValue(of(parsePlans(plansBody({ current: 'PRO', source: 'BILLING' }))));
        expect((await render()).querySelector('.plan-line')?.textContent).toContain('действует до 1 ноября');
    });

    it('fails soft with a retry', async () => {
        api.load.mockReturnValueOnce(throwError(() => new Error('down')));
        const fixture = TestBed.createComponent(ProfilePlanComponent);
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
        const root = fixture.nativeElement as HTMLElement;
        expect(root.querySelector('[role=status]')?.textContent).toContain('Не удалось узнать тариф');
        api.load.mockReturnValue(of(parsePlans(plansBody())));
        root.querySelector<HTMLButtonElement>('button')!.click();
        await fixture.whenStable();
        fixture.detectChanges();
        expect(root.querySelector('.plan-line')?.textContent?.trim()).toBe('Тариф Free');
    });

    it('names a promo tier without renewal and the pending discount', async () => {
        api.load.mockReturnValue(of(parsePlans(plansBody({ current: 'PLUS', source: 'PROMO',
            pendingDiscount: { percent: 15, plan: null, validUntil: '2026-10-31T20:59:59Z' } }))));
        const root = await render();
        expect(root.querySelector('.plan-line')?.textContent?.trim().replace(/\s+/gu, ' ')).toBe('Тариф Plus, действует до 1 ноября, без автопродления');
        expect(root.querySelector('.notice')?.textContent).toBe('Скидка 15\u00a0% применится к оплате до 31 октября.');
    });

    it('has the promo field and reads the plan again after a redemption without losing the success message', async () => {
        api.load.mockReturnValue(of(parsePlans(plansBody())));
        const fixture = TestBed.createComponent(ProfilePlanComponent);
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
        const root = fixture.nativeElement as HTMLElement;
        expect(root.querySelector('.promo label')?.textContent).toBe('Промокод');
        promo.redeem.mockReturnValue(of({ type: 'TIER_DAYS', plan: 'PLUS', validUntil: '2026-10-20T09:00:00Z', percent: null,
            message: 'Plus до 20 октября, без автопродления.' }));
        api.load.mockReturnValue(of(parsePlans(plansBody({ current: 'PLUS', source: 'PROMO' }))));
        const input = root.querySelector<HTMLInputElement>('.promo input')!;
        input.value = 'PLUS15';
        input.dispatchEvent(new Event('input'));
        root.querySelector<HTMLFormElement>('.promo form')!.dispatchEvent(new Event('submit'));
        await fixture.whenStable();
        fixture.detectChanges();
        expect(root.querySelector('.plan-line')?.textContent).toContain('Тариф Plus');
        expect(root.querySelector('.promo .notice.success')?.textContent).toContain('Plus до 20 октября');
    });

    it('keeps the plan on screen when the quiet re-read fails', async () => {
        api.load.mockReturnValue(of(parsePlans(plansBody())));
        const fixture = TestBed.createComponent(ProfilePlanComponent);
        fixture.detectChanges();
        await fixture.whenStable();
        await fixture.componentInstance.load(true);
        api.load.mockReturnValue(throwError(() => new Error('down')));
        await fixture.componentInstance.load(true);
        fixture.detectChanges();
        expect((fixture.nativeElement as HTMLElement).querySelector('.plan-line')?.textContent?.trim()).toBe('Тариф Free');
    });
});
