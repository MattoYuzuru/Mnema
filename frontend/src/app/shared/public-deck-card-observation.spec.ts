import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { COMMUNITY_CARDS } from '../styleguide/sg-community-demo';
import { PublicDeckCardComponent } from './public-deck-card.component';
import { ShareLinkService } from './share-link.service';
describe('PublicDeckCard visibility boundary', () => {
    afterEach(() => vi.unstubAllGlobals());
    it('observes only when enabled, emits once after real intersection and disconnects on replacement/destruction', async () => {
        const observers: { callback: IntersectionObserverCallback; disconnect: ReturnType<typeof vi.fn>; observe: ReturnType<typeof vi.fn> }[] = [];
        vi.stubGlobal('IntersectionObserver', class {
            disconnect = vi.fn(); observe = vi.fn();
            constructor(callback: IntersectionObserverCallback) { observers.push({ callback, disconnect: this.disconnect, observe: this.observe }); }
        });
        TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: ShareLinkService, useValue: {} }] });
        const fixture = TestBed.createComponent(PublicDeckCardComponent); fixture.componentRef.setInput('deck', COMMUNITY_CARDS[0]); fixture.detectChanges(); await fixture.whenStable();
        expect(observers).toHaveLength(0);
        const visible = vi.fn(); fixture.componentInstance.visible.subscribe(visible);
        fixture.componentRef.setInput('observe', true); fixture.detectChanges(); await fixture.whenStable();
        expect(observers).toHaveLength(1); expect(observers[0].observe).toHaveBeenCalledWith(fixture.nativeElement);
        const emit = (index: number, on: boolean, ratio: number) => observers[index].callback([{ isIntersecting: on, intersectionRatio: ratio } as IntersectionObserverEntry], {} as IntersectionObserver);
        emit(0, false, 0); emit(0, true, 0.3); expect(visible).not.toHaveBeenCalled();
        emit(0, true, 0.5); emit(0, true, 1); expect(visible).toHaveBeenCalledTimes(1); expect(observers[0].disconnect).toHaveBeenCalled();
        fixture.componentRef.setInput('deck', COMMUNITY_CARDS[1]); fixture.detectChanges(); await fixture.whenStable(); emit(0, true, 1); expect(visible).toHaveBeenCalledTimes(1);
        fixture.componentRef.setInput('observe', false); fixture.detectChanges(); await fixture.whenStable(); emit(1, true, 1); expect(visible).toHaveBeenCalledTimes(1);
        fixture.destroy(); expect(observers[1].disconnect).toHaveBeenCalled();
    });
});
