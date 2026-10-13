import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';
import contract from '../../../../../contracts/community/recommendations.json';
import { AuthService, AuthStatus } from '../../auth.service';
import { COMMUNITY_LAUNCH } from '../../shared/community-launch';
import { InterestStore } from './interest-store.service';
import { RecommendationsApiService } from './recommendations-api.service';
import { InterestProfile, parseInterestProfile } from './recommendations.models';

const PROFILE = parseInterestProfile(contract.profile);
describe('InterestStore account and command boundary', () => {
    let status: ReturnType<typeof signal<AuthStatus>>;
    let user: ReturnType<typeof signal<{ accountId: string } | null>>;
    let api: { profile: ReturnType<typeof vi.fn>; change: ReturnType<typeof vi.fn> };
    let store: InterestStore;
    beforeEach(() => {
        status = signal<AuthStatus>('authenticated'); user = signal({ accountId: 'account-a' });
        api = { profile: vi.fn().mockReturnValue(of(PROFILE)), change: vi.fn().mockReturnValue(of(parseInterestProfile(contract.select.response))) };
        TestBed.configureTestingModule({ providers: [{ provide: AuthService, useValue: { status, user } }, { provide: RecommendationsApiService, useValue: api }, { provide: COMMUNITY_LAUNCH, useValue: true }] });
        store = TestBed.inject(InterestStore); store.ensure(); TestBed.tick();
    });
    it('loads once for the restored account and disables every personal surface for guests', () => {
        expect(api.profile).toHaveBeenCalledTimes(1); expect(store.onboarding()).toBe(true);
        store.ensure(); expect(api.profile).toHaveBeenCalledTimes(1);
        status.set('anonymous'); TestBed.tick();
        expect(store.profile()).toBeNull(); expect(store.forYou()).toBe(false); expect(store.events()).toBe(false);
        store.save(['spanish']); expect(api.change).not.toHaveBeenCalled();
    });
    it('keeps the exact selection command and version after an unknown network outcome', () => {
        api.change.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 0 })));
        store.save(['spanish']); const attempt = api.change.mock.calls[0];
        expect(store.error()).toContain('Повторите тот же запрос');
        store.save(['english']); expect(api.change.mock.calls[1]).toEqual(attempt);
        expect(store.notice()).toBe('Интересы сохранены.'); expect(store.profile()?.rowVersion).toBe('1');
    });
    it('skip completes onboarding with an empty array, reset retries the same body and keeps no old signals', () => {
        store.save([]); expect(api.change.mock.calls[0][0].topicIds).toEqual([]);
        api.change.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 0 })));
        store.save([], true); const attempt = api.change.mock.calls[1];
        api.change.mockReturnValue(of(parseInterestProfile(contract.reset.response)));
        store.save(['english']); expect(api.change.mock.calls[2]).toEqual(attempt);
        expect(Object.keys(attempt[0])).toEqual(['commandId']);
        expect(store.profile()?.selectedTopics).toEqual([]); expect(store.notice()).toContain('Новые действия');
    });
    it('reloads on 412 and stops showing the settings when the server is disabled', () => {
        api.change.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 412 })));
        store.save(['spanish']); expect(api.profile).toHaveBeenCalledTimes(2); expect(store.notice()).toContain('другом окне');
        api.profile.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 404 })));
        store.reload(); expect(store.profile()).toBeNull(); expect(store.error()).toBe(''); expect(store.forYou()).toBe(false);
    });
    it('cancels old reads/commands and ignores old-account results', () => {
        const oldRead = new Subject<InterestProfile>(); api.profile.mockReturnValue(oldRead); store.reload();
        expect(oldRead.observed).toBe(true);
        user.set({ accountId: 'account-b' }); api.profile.mockReturnValue(of({ ...PROFILE, forYouEnabled: false })); TestBed.tick();
        expect(oldRead.observed).toBe(false); oldRead.next(PROFILE); expect(store.forYou()).toBe(false);
        const pending = new Subject<InterestProfile>(); api.change.mockReturnValue(pending); store.save(['spanish']);
        status.set('anonymous'); TestBed.tick(); expect(pending.observed).toBe(false);
        pending.next(parseInterestProfile(contract.select.response)); expect(store.profile()).toBeNull();
    });
    it('honors a recording kill switch response immediately without disabling recommendations', () => {
        store.disableEvents(); expect(store.events()).toBe(false); expect(store.forYou()).toBe(true);
        store.disableForYou(); expect(store.forYou()).toBe(false); expect(store.profile()).not.toBeNull();
    });
    it('does not make a request while launch is closed', () => {
        TestBed.resetTestingModule();
        TestBed.configureTestingModule({ providers: [{ provide: AuthService, useValue: { status, user } }, { provide: RecommendationsApiService, useValue: api }, { provide: COMMUNITY_LAUNCH, useValue: false }] });
        api.profile.mockClear(); store = TestBed.inject(InterestStore); store.ensure(); TestBed.tick(); expect(api.profile).not.toHaveBeenCalled();
    });
});
