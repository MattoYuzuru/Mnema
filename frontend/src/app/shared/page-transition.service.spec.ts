import { ApplicationRef } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';

import { PageTransition } from './page-transition.service';

describe('PageTransition', () => {
    let navigate: ReturnType<typeof vi.fn>;
    let service: PageTransition;
    const documentRef = document as unknown as { startViewTransition?: unknown };

    beforeEach(() => {
        navigate = vi.fn().mockResolvedValue(true);
        TestBed.configureTestingModule({ providers: [{ provide: Router, useValue: { navigate } }] });
        service = TestBed.inject(PageTransition);
    });
    afterEach(() => { delete documentRef.startViewTransition; });

    it('is a plain navigation where the browser has no View Transitions', async () => {
        expect(await service.navigate(['/decks', 'x'], { queryParams: { n: 1 } })).toBe(true);
        expect(navigate).toHaveBeenCalledWith(['/decks', 'x'], { queryParams: { n: 1 } });
    });

    it('is a plain navigation for reduced motion, even where the API exists', async () => {
        const start = vi.fn();
        documentRef.startViewTransition = start;
        vi.spyOn(window, 'matchMedia').mockReturnValue({ matches: true } as MediaQueryList);
        await service.navigate(['/decks']);
        expect(start).not.toHaveBeenCalled();
        expect(navigate).toHaveBeenCalledTimes(1);
    });

    it('navigates inside the transition and waits for the render before it lets the browser take the new snapshot', async () => {
        const order: string[] = [];
        const stable = vi.spyOn(TestBed.inject(ApplicationRef), 'whenStable').mockImplementation(async () => { order.push('stable'); });
        navigate.mockImplementation(async () => { order.push('navigated'); return true; });
        documentRef.startViewTransition = (update: () => Promise<void>) => ({ finished: update().then(() => { order.push('finished'); }) });
        expect(await service.navigate(['/decks', 'x'])).toBe(true);
        expect(order).toEqual(['navigated', 'stable', 'finished']);
        expect(stable).toHaveBeenCalledTimes(1);
    });

    it('does not report a skipped transition as a failed navigation, and a refused navigation as a success', async () => {
        documentRef.startViewTransition = (update: () => Promise<void>) => { void update(); return { finished: Promise.reject(new Error('skipped')) }; };
        expect(await service.navigate(['/a'])).toBe(true);
        navigate.mockResolvedValue(false);
        documentRef.startViewTransition = (update: () => Promise<void>) => ({ finished: update() });
        expect(await service.navigate(['/b'])).toBe(false);
    });
});
