import { Router, provideRouter } from '@angular/router';
import { TestBed } from '@angular/core/testing';
import { NEVER, Subject, of, throwError } from 'rxjs';

import { spyObj, type SpyObj } from '../../../testing/mocks';
import { QuietZone } from '../../core/notifications/quiet-zone';
import { PromoApiService } from './promo-api.service';
import { POPUP_SESSION_KEY, PromoPopupService } from './promo-popup.service';
import { PromoCampaign } from './promo.models';

const CAMPAIGN: PromoCampaign = { id: 'autumn', title: 'Осенняя скидка', body: 'Plus дешевле.', cta: 'Посмотреть тарифы', code: 'AUTUMN-26' };

describe('PromoPopupService', () => {
    let api: SpyObj<PromoApiService>;
    let service: PromoPopupService;

    beforeEach(() => {
        sessionStorage.clear();
        api = spyObj<PromoApiService>({ popup: vi.fn().mockName('popup'), popupEvent: vi.fn().mockName('popupEvent') });
        api.popup.mockReturnValue(of(CAMPAIGN));
        api.popupEvent.mockReturnValue(of(undefined));
        TestBed.configureTestingModule({ providers: [provideRouter([{ path: 'plans', children: [] }]), { provide: PromoApiService, useValue: api }] });
        service = TestBed.inject(PromoPopupService);
    });
    afterEach(() => sessionStorage.clear());

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

    it('does nothing when asked again while asking or while shown, and treats a failure as no popup', async () => {
        api.popup.mockReturnValue(NEVER);
        void service.request();
        await service.request();
        expect(api.popup).toHaveBeenCalledOnce();

        TestBed.resetTestingModule();
        sessionStorage.clear();
        const failing = spyObj<PromoApiService>({ popup: vi.fn().mockReturnValue(throwError(() => new Error('down'))), popupEvent: vi.fn() });
        TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: PromoApiService, useValue: failing }] });
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

        sessionStorage.clear();
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

        sessionStorage.clear();
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
        } finally { blocked.mockRestore(); store.mockRestore(); }
    });
});
