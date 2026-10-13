import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { NavigationStart, Router, provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { CatalogTelemetry, EXPOSURE_FLUSH_MS } from './catalog-telemetry.service';
import { InterestStore } from './interest-store.service';
import { CardAttribution, RecommendationsApiService } from './recommendations-api.service';
const CONTEXT: CardAttribution = { code: 'Kq7xT3mNpR', surface: 'for-you', position: 1, rankVersion: 'recs-v1' };
describe('CatalogTelemetry privacy and navigation lifetime', () => {
    let interests: { account: ReturnType<typeof signal<string | null>>; events: ReturnType<typeof signal<boolean>>; ready: ReturnType<typeof signal<boolean>> };
    let api: { event: ReturnType<typeof vi.fn>; exposures: ReturnType<typeof vi.fn> };
    let telemetry: CatalogTelemetry;
    let navigation: Subject<NavigationStart>;
    beforeEach(() => {
        vi.useFakeTimers();
        interests = { account: signal<string | null>('a'), events: signal(true), ready: signal(true) };
        navigation = new Subject<NavigationStart>();
        api = { event: vi.fn().mockReturnValue(of(undefined)), exposures: vi.fn().mockReturnValue(of(undefined)) };
        TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: Router, useValue: { events: navigation } },
            { provide: InterestStore, useValue: interests }, { provide: RecommendationsApiService, useValue: api }] });
        telemetry = TestBed.inject(CatalogTelemetry); TestBed.tick();
    });
    afterEach(() => { vi.runOnlyPendingTimers(); vi.useRealTimers(); });
    it('writes once only on visibility/click and carries attribution into successful detail without duplicate canonical reads', () => {
        expect(api.event).not.toHaveBeenCalled(); expect(api.exposures).not.toHaveBeenCalled();
        telemetry.visible(CONTEXT); telemetry.visible(CONTEXT); expect(api.exposures).not.toHaveBeenCalled(); vi.advanceTimersByTime(EXPOSURE_FLUSH_MS); expect(api.exposures).toHaveBeenCalledTimes(1);
        telemetry.opened(CONTEXT, true); expect(api.event).toHaveBeenCalledWith('SEARCH_CLICK', CONTEXT);
        navigation.next(new NavigationStart(1, '/d/Kq7xT3mNpR'));
        telemetry.detail(CONTEXT.code); expect(api.event).toHaveBeenLastCalledWith('DETAIL_VIEW', CONTEXT);
        navigation.next(new NavigationStart(2, '/d/Kq7xT3mNpR/canonical-slug'));
        telemetry.detail(CONTEXT.code); expect(api.event).toHaveBeenCalledTimes(2);
        navigation.next(new NavigationStart(3, '/community'));
        telemetry.visible(CONTEXT); vi.advanceTimersByTime(EXPOSURE_FLUSH_MS); expect(api.exposures).toHaveBeenCalledTimes(2);
    });
    it('discards attribution on unrelated navigation and records direct detail with no position', () => {
        telemetry.opened(CONTEXT, false); navigation.next(new NavigationStart(1, '/profile'));
        telemetry.detail(CONTEXT.code);
        expect(api.event).toHaveBeenCalledWith('DETAIL_VIEW', { code: CONTEXT.code, surface: 'detail', position: null, rankVersion: 'catalog-v1' });
    });
    it('waits for flags, bounds dedupe/in-flight work and cancels on account change', () => {
        interests.ready.set(false); interests.events.set(false); TestBed.tick();
        telemetry.detail(CONTEXT.code); expect(api.event).not.toHaveBeenCalled();
        interests.ready.set(true); interests.events.set(true); TestBed.tick(); expect(api.event).toHaveBeenCalledTimes(1);
        const inflight = new Subject<void>(); api.exposures.mockReturnValue(inflight);
        for (let position = 1; position <= 60; position++) telemetry.visible({ ...CONTEXT, code: `Kq7xT3mN${'123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz'[Math.floor(position / 58)]}${'123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz'[position % 58]}`, position });
        vi.advanceTimersByTime(EXPOSURE_FLUSH_MS);
        expect(api.exposures.mock.calls.map(call => call[2].length)).toEqual([48, 12]);
        expect(inflight.observers).toHaveLength(2);
        interests.account.set('b'); TestBed.tick(); expect(inflight.observed).toBe(false);
        telemetry.detail(CONTEXT.code); expect(api.event).toHaveBeenLastCalledWith('DETAIL_VIEW', { code: CONTEXT.code, surface: 'detail', position: null, rankVersion: 'catalog-v1' });
    });
    it('flushes visible items before navigation, keeps surfaces separate and discards foreign-account buffers', () => {
        telemetry.visible(CONTEXT); telemetry.visible({ ...CONTEXT, surface: 'catalog', rankVersion: 'catalog-v1' });
        navigation.next(new NavigationStart(1, '/profile'));
        expect(api.exposures).toHaveBeenCalledTimes(2);
        expect(api.exposures.mock.calls[0]).toEqual(['for-you', 'recs-v1', [{ code: CONTEXT.code, position: 1 }]]);
        telemetry.visible(CONTEXT); interests.account.set('b'); TestBed.tick(); vi.advanceTimersByTime(EXPOSURE_FLUSH_MS);
        expect(api.exposures).toHaveBeenCalledTimes(2);
    });
    it('uses four exposure slots, retains overflow without sampling and resumes visibility after backpressure', () => {
        const inflight = new Subject<void>(); api.exposures.mockReturnValue(inflight);
        const alphabet = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz';
        for (let index = 0; index < 1192; index++) {
            const code = `Kq7xT3mN${alphabet[Math.floor(index / 58)]}${alphabet[index % 58]}`;
            telemetry.visible({ ...CONTEXT, code, position: index >= 1000 ? null : index + 1 });
        }
        expect(api.exposures).toHaveBeenCalledTimes(4); expect(inflight.observers).toHaveLength(4); expect(telemetry.accepting()).toBe(false);
        api.exposures.mockReturnValue(of(undefined)); inflight.complete();
        expect(telemetry.accepting()).toBe(true);
        expect(api.exposures.mock.calls.reduce((count, call) => count + call[2].length, 0)).toBe(1192);
        expect(api.exposures.mock.calls.every(call => call[2].length <= 48)).toBe(true);
        expect(api.exposures.mock.calls.flatMap(call => call[2]).filter(item => item.position === null)).toHaveLength(192);
    });
    it('records nothing for guests, disabled flags, malformed codes or illegal positions and never blocks on telemetry failure', () => {
        for (const position of [0, 1001, 1.5]) telemetry.visible({ ...CONTEXT, position });
        telemetry.visible({ ...CONTEXT, code: 'private-id' });
        expect(api.exposures).not.toHaveBeenCalled();
        interests.events.set(false); TestBed.tick(); telemetry.opened(CONTEXT, true); telemetry.detail(CONTEXT.code); expect(api.event).not.toHaveBeenCalled();
        interests.account.set(null); TestBed.tick(); telemetry.visible(CONTEXT); expect(api.exposures).not.toHaveBeenCalled();
        interests.account.set('a'); interests.events.set(true); TestBed.tick();
        api.event.mockReturnValue(throwError(() => new Error('offline')));
        expect(() => telemetry.opened(CONTEXT, true)).not.toThrow();
    });
});
