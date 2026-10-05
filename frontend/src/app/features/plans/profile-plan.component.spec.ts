import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import { spyObj, type SpyObj } from '../../../testing/mocks';
import { PlansApiService, parsePlans } from './plans-api.service';
import { ProfilePlanComponent } from './profile-plan.component';
import { plansBody } from './plans-test-data';

describe('ProfilePlanComponent', () => {
    let api: SpyObj<PlansApiService>;

    beforeEach(() => {
        api = spyObj<PlansApiService>({ load: vi.fn().mockName('PlansApiService.load') });
        TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: PlansApiService, useValue: api }] });
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
        expect(root.querySelector('.switch-row')?.textContent).toContain('Автопродление');
        expect(root.querySelector('.method-label')?.textContent).toBe('Способ оплаты');
        expect(root.querySelector<HTMLButtonElement>('.method-row button')!.disabled).toBe(true);
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
});
