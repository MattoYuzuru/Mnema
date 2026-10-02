import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { DeckConstellationComponent } from './deck-constellation.component';

@Component({
    imports: [DeckConstellationComponent],
    template: `<main style="position:relative; width:1600px; height:1200px">
        <app-deck-constellation seed="deck-a" />
        <div><section style="width:992px; padding:48px; box-sizing:border-box; margin:auto">Материал</section></div>
    </main>`
})
class PageFixture {}

describe('DeckConstellationComponent', () => {
    const original = window.ResizeObserver;
    let disconnects: number;
    beforeEach(() => {
        disconnects = 0;
        window.ResizeObserver = class {
            constructor(_callback: ResizeObserverCallback) { }
            observe(): void { }
            disconnect(): void { disconnects++; }
        } as unknown as typeof ResizeObserver;
    });
    afterEach(() => { window.ResizeObserver = original; });

    // Star placement, clearance and omission are derived from measured page geometry, which jsdom cannot provide; the
    // browser harness owns them (scripts/browser-identity, scenario mechanics_constellation_geometry).
    it('observes the page layout and stops observing when destroyed', async () => {
        const fixture = TestBed.createComponent(PageFixture);
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.destroy();
        expect(disconnects).toBeGreaterThan(0);
    });
});
