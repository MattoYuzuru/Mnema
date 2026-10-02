import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { HAS_LAYOUT_ENGINE } from '../../testing/layout';
import { DeckConstellationComponent } from './deck-constellation.component';

@Component({
    imports: [DeckConstellationComponent],
    template: `<main style="position:relative; width:1600px" [style.height.px]="height()">
        <app-deck-constellation seed="deck-a" />
        <div><section style="width:992px; padding:48px; box-sizing:border-box; margin:auto">Материал</section></div>
    </main>`
})
class PageFixture {
    readonly height = signal(1200);
}

describe('DeckConstellationComponent geometry', () => {
    const original = window.ResizeObserver;
    let resized: () => void;
    let disconnects: number;
    beforeEach(() => {
        disconnects = 0;
        window.ResizeObserver = class {
            constructor(callback: ResizeObserverCallback) { resized = () => callback([], {} as ResizeObserver); }
            observe(): void { }
            disconnect(): void { disconnects++; }
        } as unknown as typeof ResizeObserver;
    });
    afterEach(() => { window.ResizeObserver = original; });

    // Star placement is derived from measured page geometry, which jsdom cannot provide (see testing/layout.ts).
    it.skipIf(!HAS_LAYOUT_ENGINE)('scatters over the full page while preserving clearance and avoiding overlaps', async () => {
        const fixture = TestBed.createComponent(PageFixture);
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
        const component = fixture.debugElement.query(By.directive(DeckConstellationComponent)).componentInstance as DeckConstellationComponent;
        expect(component.rails()?.width).toBeGreaterThan(100);
        const main = fixture.nativeElement.querySelector('main').getBoundingClientRect() as DOMRect;
        const stars = [...fixture.nativeElement.querySelectorAll('.rail span')].map((e: Element) => e.getBoundingClientRect());
        expect(stars.length).toBeGreaterThanOrEqual(6);
        for (const [index, star] of stars.entries()) {
            expect(star.top).toBeGreaterThanOrEqual(main.top + main.height * .05);
            expect(star.bottom).toBeLessThanOrEqual(main.bottom - main.height * .05);
            expect(star.left).toBeGreaterThanOrEqual(main.left + main.width * .05);
            expect(star.right).toBeLessThanOrEqual(main.right - main.width * .05);
            for (const other of stars.slice(index + 1)) {
                expect(star.right <= other.left || other.right <= star.left || star.bottom <= other.top || other.bottom <= star.top).toBe(true);
            }
        }
        const positions = component.stars();
        fixture.componentInstance.height.set(2400);
        fixture.detectChanges();
        resized();
        fixture.detectChanges();
        expect(component.stars()).toBe(positions);
        const bottom = Math.max(...[...fixture.nativeElement.querySelectorAll('.rail span')].map((e: Element) => e.getBoundingClientRect().bottom));
        expect(bottom - main.top).toBeGreaterThan(2000);
        fixture.destroy();
        expect(disconnects).toBeGreaterThan(0);
    });

    it('omits decoration when the safe horizontal area cannot fit scattered stars', async () => {
        const fixture = TestBed.createComponent(PageFixture);
        fixture.detectChanges();
        await fixture.whenStable();
        (fixture.nativeElement.querySelector('main') as HTMLElement).style.width = '1200px';
        resized();
        fixture.detectChanges();
        expect(fixture.nativeElement.querySelector('.rail')).toBeNull();
        fixture.destroy();
    });
});
