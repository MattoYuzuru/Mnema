import { Router, provideRouter } from '@angular/router';
import { TestBed } from '@angular/core/testing';
import { RouterTestingHarness } from '@angular/router/testing';
import { Component } from '@angular/core';
import { of } from 'rxjs';

import { spyObj, type SpyObj } from '../../../testing/mocks';
import { PromoApiService } from './promo-api.service';
import { PromoPopupHostComponent } from './promo-popup-host.component';
import { PromoPopupService } from './promo-popup.service';

@Component({ template: '<app-promo-popup-host />', imports: [PromoPopupHostComponent] })
class HostPageComponent { }

@Component({ template: '' })
class BlankComponent { }

describe('PromoPopupHostComponent', () => {
    let api: SpyObj<PromoApiService>;

    beforeEach(() => {
        sessionStorage.clear();
        api = spyObj<PromoApiService>({ popup: vi.fn(), popupEvent: vi.fn() });
        api.popup.mockReturnValue(of({ id: 'autumn', title: 'Осенняя скидка', body: 'Plus дешевле.', cta: 'Посмотреть тарифы', code: null }));
        api.popupEvent.mockReturnValue(of(undefined));
        TestBed.configureTestingModule({
            providers: [provideRouter([{ path: '', component: HostPageComponent }, { path: 'plans', component: BlankComponent }]),
                { provide: PromoApiService, useValue: api }]
        });
    });
    afterEach(() => sessionStorage.clear());

    it('renders nothing until a breakpoint asks, then the dialog, and routes the primary action to the plans', async () => {
        const harness = await RouterTestingHarness.create('/');
        const root = harness.routeNativeElement as HTMLElement;
        const service = TestBed.inject(PromoPopupService);
        expect(root.querySelector('dialog')).toBeNull();

        await service.request();
        harness.detectChanges();
        expect(root.querySelector('dialog')?.textContent).toContain('Осенняя скидка');

        root.querySelector<HTMLButtonElement>('button.primary')!.click();
        await harness.fixture.whenStable();
        expect(TestBed.inject(Router).url).toBe('/plans');
        expect(service.campaign()).toBeNull();
        expect(api.popupEvent).toHaveBeenLastCalledWith('autumn', 'DISMISSED');
    });

    it('wires «Не сейчас» and «Больше не показывать» to the service', async () => {
        const harness = await RouterTestingHarness.create('/');
        const root = harness.routeNativeElement as HTMLElement;
        const service = TestBed.inject(PromoPopupService);
        await service.request();
        harness.detectChanges();
        root.querySelectorAll<HTMLButtonElement>('button')[2].click();
        expect(api.popupEvent).toHaveBeenLastCalledWith('autumn', 'DISMISSED');

        sessionStorage.clear();
        await service.request();
        harness.detectChanges();
        root.querySelectorAll<HTMLButtonElement>('button')[3].click();
        expect(api.popupEvent).toHaveBeenLastCalledWith('autumn', 'DECLINED');
    });

    it('hands focus to the page heading when the popup closes with nothing focused', async () => {
        const harness = await RouterTestingHarness.create('/');
        const main = document.createElement('main');
        main.id = 'main-content';
        main.innerHTML = '<h1 tabindex="-1">Мои колоды</h1>';
        document.body.append(main);
        try {
            const service = TestBed.inject(PromoPopupService);
            await service.request();
            harness.detectChanges();
            (document.activeElement as HTMLElement | null)?.blur();
            service.dismiss();
            harness.detectChanges();
            await harness.fixture.whenStable();
            await Promise.resolve();
            expect(document.activeElement).toBe(main.querySelector('h1'));
        } finally { main.remove(); }
    });

    it('closes without an answer when the learner navigates away', async () => {
        const harness = await RouterTestingHarness.create('/');
        const service = TestBed.inject(PromoPopupService);
        await service.request();
        harness.detectChanges();
        await TestBed.inject(Router).navigateByUrl('/plans');
        expect(service.campaign()).toBeNull();
        expect(api.popupEvent).toHaveBeenCalledTimes(1);
        expect(api.popupEvent).toHaveBeenCalledWith('autumn', 'SHOWN');
    });
});
