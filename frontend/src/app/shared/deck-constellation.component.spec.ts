import { Component, signal } from '@angular/core';
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

@Component({
    imports: [DeckConstellationComponent],
    template: `<main><app-deck-constellation seed="deck-a" /><div></div>
      @if (page(); as id) { <div [attr.data-page]="id"><section>Материал</section></div> }
    </main>`
})
class LazyPageFixture { readonly page = signal<string | null>(null); }

describe('DeckConstellationComponent', () => {
    const original = window.ResizeObserver;
    let disconnects: number;
    let observed: Element[];
    beforeEach(() => {
        disconnects = 0;
        observed = [];
        window.ResizeObserver = class {
            constructor(_callback: ResizeObserverCallback) { }
            observe(element: Element): void { observed.push(element); }
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

    it('observes a lazy page inserted after the initial render and reconnects when it is replaced', async () => {
        const fixture = TestBed.createComponent(LazyPageFixture);
        const root = fixture.nativeElement as HTMLElement;
        fixture.detectChanges();
        await fixture.whenStable();
        expect(observed).toEqual([]);

        fixture.componentInstance.page.set('first');
        fixture.detectChanges();
        await fixture.whenStable();
        const first = root.querySelector('section')!;
        expect(observed).toContain(first);

        fixture.componentInstance.page.set(null);
        fixture.detectChanges();
        await fixture.whenStable();
        const disconnected = disconnects;
        fixture.componentInstance.page.set('second');
        fixture.detectChanges();
        await fixture.whenStable();
        const second = root.querySelector('section')!;
        expect(second).not.toBe(first);
        expect(observed).toContain(second);
        expect(disconnects).toBeGreaterThan(disconnected);
        fixture.destroy();
    });
});
