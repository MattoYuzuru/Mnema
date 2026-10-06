import { Router, provideRouter } from '@angular/router';
import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { NEVER, Subject, of, throwError } from 'rxjs';

import { spyObj, type SpyObj } from '../../../testing/mocks';
import { QuietZone } from '../../core/notifications/quiet-zone';
import { PromoApiService } from './promo-api.service';
import { POPUP_PREFERENCE_KEY, POPUP_SESSION_KEY, PromoPopupService } from './promo-popup.service';
import { PromoCampaign } from './promo.models';
import { AuthService, AuthStatus } from '../../auth.service';

const CAMPAIGN: PromoCampaign = { id: 'autumn', title: 'Осенняя скидка', body: 'Plus дешевле.', cta: 'Посмотреть тарифы', code: 'AUTUMN-26' };

describe('PromoPopupService', () => {
    let api: SpyObj<PromoApiService>;
    let service: PromoPopupService;
    const status = signal<AuthStatus>('authenticated');
    const user = signal<{ accountId: string } | null>({ accountId: 'first-account' });

    beforeEach(() => {
        sessionStorage.clear();
        localStorage.clear();
        status.set('authenticated');
        user.set({ accountId: 'first-account' });
        api = spyObj<PromoApiService>({ popup: vi.fn().mockName('popup'), popupEvent: vi.fn().mockName('popupEvent') });
        api.popup.mockReturnValue(of(CAMPAIGN));
        api.popupEvent.mockReturnValue(of(true));
        TestBed.configureTestingModule({ providers: [provideRouter([{ path: 'plans', children: [] }]), { provide: PromoApiService, useValue: api },
            { provide: AuthService, useValue: { status, user } }] });
        service = TestBed.inject(PromoPopupService);
    });
    afterEach(() => { sessionStorage.clear(); localStorage.clear(); });

    it('shows the campaign the server allows at a breakpoint and records that it was shown', async () => {
        await service.request();
        expect(service.campaign()).toEqual(CAMPAIGN);
        expect(api.popupEvent).toHaveBeenCalledWith('autumn', 'SHOWN');
        expect(sessionStorage.getItem(POPUP_SESSION_KEY)).not.toBeNull();
    });

    it('asks and shows at most once per browser session', async () => {
        await service.request();
        service.dismiss();
        await service.request();
        expect(api.popup).toHaveBeenCalledOnce();
        expect(service.campaign()).toBeNull();
        // a new page load of the same session reads the marker from sessionStorage
        const reloaded = TestBed.runInInjectionContext(() => new PromoPopupService());
        await reloaded.request();
        expect(api.popup).toHaveBeenCalledOnce();
        expect(reloaded.campaign()).toBeNull();
    });

    it('remembers a refusal of the server for the session so it is not asked again', async () => {
        api.popup.mockReturnValue(of(null));
        await service.request();
        await service.request();
        expect(api.popup).toHaveBeenCalledOnce();
        expect(service.campaign()).toBeNull();
        expect(api.popupEvent).not.toHaveBeenCalled();
    });

    it('does not interrupt a task: nothing is shown while a task is open, and the session keeps its chance', async () => {
        TestBed.inject(QuietZone).set(true);
        await service.request();
        expect(service.campaign()).toBeNull();
        expect(sessionStorage.getItem(POPUP_SESSION_KEY)).toBeNull();
        TestBed.inject(QuietZone).set(false);
        await service.request();
        expect(service.campaign()).toEqual(CAMPAIGN);
    });

    it('drops an answer that arrives after the learner left, without spending the session', async () => {
        const answer = new Subject<PromoCampaign | null>();
        api.popup.mockReturnValue(answer);
        const pending = service.request();
        service.close();
        answer.next(CAMPAIGN);
        answer.complete();
        await pending;
        expect(service.campaign()).toBeNull();
        expect(sessionStorage.getItem(POPUP_SESSION_KEY)).toBeNull();
        expect(api.popupEvent).not.toHaveBeenCalled();
    });

    it('drops an ineligible answer after navigation without consuming a later breakpoint', async () => {
        const answer = new Subject<PromoCampaign | null>();
        api.popup.mockReturnValueOnce(answer).mockReturnValueOnce(of(CAMPAIGN));
        const pending = service.request();
        service.close();
        answer.next(null);
        answer.complete();
        await pending;
        await service.request();
        expect(service.campaign()).toEqual(CAMPAIGN);
    });

    it('closes and forgets the popup after an account change, fencing the previous reply', async () => {
        const answer = new Subject<PromoCampaign | null>();
        api.popup.mockReturnValueOnce(answer).mockReturnValueOnce(of(CAMPAIGN));
        const pending = service.request();
        user.set({ accountId: 'second-account' });
        TestBed.tick();
        await service.request();
        expect(service.campaign()).toEqual(CAMPAIGN);
        answer.next(null);
        answer.complete();
        await pending;
        service.dismiss();
        await service.request();
        expect(api.popup).toHaveBeenCalledTimes(2);
    });

    it('does not ask or show a campaign while signed out', async () => {
        status.set('anonymous');
        user.set(null);
        TestBed.tick();
        await service.request();
        expect(api.popup).not.toHaveBeenCalled();
        expect(service.campaign()).toBeNull();
    });

    it('does nothing when asked again while asking or while shown, and treats a failure as no popup', async () => {
        api.popup.mockReturnValue(NEVER);
        void service.request();
        await service.request();
        expect(api.popup).toHaveBeenCalledOnce();

        TestBed.resetTestingModule();
        sessionStorage.clear();
        const failing = spyObj<PromoApiService>({ popup: vi.fn().mockReturnValue(throwError(() => new Error('down'))), popupEvent: vi.fn() });
        TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: PromoApiService, useValue: failing },
            { provide: AuthService, useValue: { status, user } }] });
        const other = TestBed.inject(PromoPopupService);
        await other.request();
        expect(other.campaign()).toBeNull();
        expect(failing.popupEvent).not.toHaveBeenCalled();
        expect(sessionStorage.getItem(POPUP_SESSION_KEY)).toBeNull();
    });

    it('records a dismissal and a decline and closes', async () => {
        await service.request();
        service.dismiss();
        expect(service.campaign()).toBeNull();
        expect(api.popupEvent).toHaveBeenLastCalledWith('autumn', 'DISMISSED');

        await Promise.resolve();
        sessionStorage.clear();
        service = TestBed.runInInjectionContext(() => new PromoPopupService());
        await service.request();
        service.decline();
        expect(service.campaign()).toBeNull();
        expect(api.popupEvent).toHaveBeenLastCalledWith('autumn', 'DECLINED');
        service.dismiss();
        service.decline();
        service.accept();
        expect(api.popupEvent).toHaveBeenCalledTimes(4);
    });

    it('takes the primary action to the plans with the campaign code to type, and counts it as a dismissal', async () => {
        const router = TestBed.inject(Router);
        const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
        await service.request();
        service.accept();
        expect(service.campaign()).toBeNull();
        expect(api.popupEvent).toHaveBeenLastCalledWith('autumn', 'DISMISSED');
        expect(navigate).toHaveBeenCalledWith(['/plans'], { queryParams: { promo: 'AUTUMN-26' } });

        await Promise.resolve();
        sessionStorage.clear();
        service = TestBed.runInInjectionContext(() => new PromoPopupService());
        api.popup.mockReturnValue(of({ ...CAMPAIGN, code: null }));
        await service.request();
        service.accept();
        expect(navigate).toHaveBeenLastCalledWith(['/plans'], { queryParams: {} });
    });

    it('survives blocked storage', async () => {
        const blocked = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked'); });
        const store = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked'); });
        try {
            await service.request();
            expect(service.campaign()).toEqual(CAMPAIGN);
            service.dismiss();
            await service.request();
            expect(api.popup).toHaveBeenCalledOnce();
            expect(service.campaign()).toBeNull();
        } finally { blocked.mockRestore(); store.mockRestore(); }
    });

    it('retains a failed decline across a fresh browser session and retries before eligibility', async () => {
        await service.request();
        api.popupEvent.mockReturnValueOnce(throwError(() => new Error('offline')));
        service.decline();
        await Promise.resolve();
        expect(localStorage.getItem(POPUP_PREFERENCE_KEY + 'first-account')).toContain('DECLINED');
        sessionStorage.clear();
        const reloaded = TestBed.runInInjectionContext(() => new PromoPopupService());
        await reloaded.request();
        expect(api.popup).toHaveBeenCalledOnce();
        expect(api.popupEvent).toHaveBeenLastCalledWith('autumn', 'DECLINED');
        expect(reloaded.campaign()).toBeNull();
        await reloaded.request();
        expect(api.popup).toHaveBeenCalledOnce();
        expect(api.popupEvent).toHaveBeenCalledTimes(3);
    });

    it('keeps a failed dismissal quiet until the cooldown event is confirmed', async () => {
        await service.request();
        api.popupEvent.mockReturnValueOnce(throwError(() => new Error('offline')));
        service.dismiss();
        await Promise.resolve();
        sessionStorage.clear();
        const reloaded = TestBed.runInInjectionContext(() => new PromoPopupService());
        await reloaded.request();
        expect(api.popup).toHaveBeenCalledOnce();
        expect(api.popupEvent).toHaveBeenLastCalledWith('autumn', 'DISMISSED');
        expect(localStorage.getItem(POPUP_PREFERENCE_KEY + 'first-account')).toBeNull();
        api.popup.mockReturnValue(of(null));
        await reloaded.request();
        expect(api.popup).toHaveBeenCalledTimes(2);
        expect(reloaded.campaign()).toBeNull();
    });

    it('does not replay a previous account preference as the new account', async () => {
        await service.request();
        api.popupEvent.mockReturnValueOnce(throwError(() => new Error('offline')));
        service.decline();
        await Promise.resolve();
        user.set({ accountId: 'second-account' });
        TestBed.tick();
        await service.request();
        expect(api.popup).toHaveBeenCalledTimes(2);
        expect(api.popupEvent).toHaveBeenLastCalledWith('autumn', 'SHOWN');
    });

    it('does not send the same pending preference again while its acknowledgement is in flight', async () => {
        await service.request();
        const acknowledgement = new Subject<boolean>();
        api.popupEvent.mockReturnValue(acknowledgement);
        service.dismiss();
        await service.request();
        await service.request();
        expect(api.popupEvent).toHaveBeenCalledTimes(2);
        acknowledgement.next(true);
        acknowledgement.complete();
        await Promise.resolve();
    });

    it('ignores malformed stored preference receipts', async () => {
        localStorage.setItem(POPUP_PREFERENCE_KEY + 'first-account', JSON.stringify({ campaignId: 'autumn', event: 'PURCHASE', confirmed: true }));
        await service.request();
        expect(service.campaign()).toEqual(CAMPAIGN);
    });

    it('keeps a disabled-campaign acknowledgement pending and retries until explicitly recorded', async () => {
        await service.request();
        api.popupEvent.mockReturnValueOnce(of(false));
        service.dismiss();
        await Promise.resolve();
        expect(JSON.parse(localStorage.getItem(POPUP_PREFERENCE_KEY + 'first-account')!)).toMatchObject({ confirmed: false, event: 'DISMISSED' });
        sessionStorage.clear();
        const reloaded = TestBed.runInInjectionContext(() => new PromoPopupService());
        api.popupEvent.mockReturnValueOnce(of(false)).mockReturnValueOnce(of(true));
        await reloaded.request();
        expect(api.popup).toHaveBeenCalledOnce();
        expect(reloaded.campaign()).toBeNull();
        expect(localStorage.getItem(POPUP_PREFERENCE_KEY + 'first-account')).not.toBeNull();
        await reloaded.request();
        expect(api.popup).toHaveBeenCalledOnce();
        expect(localStorage.getItem(POPUP_PREFERENCE_KEY + 'first-account')).toBeNull();
    });
});
