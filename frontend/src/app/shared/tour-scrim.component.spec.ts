import { TestBed } from '@angular/core/testing';

import { TourScrimComponent } from './tour-scrim.component';

describe('TourScrimComponent', () => {
    function render(inputs: Record<string, unknown>) {
        TestBed.configureTestingModule({ imports: [TourScrimComponent] });
        const fixture = TestBed.createComponent(TourScrimComponent);
        for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
        fixture.detectChanges();
        return { fixture, host: fixture.nativeElement as HTMLElement };
    }

    it('is decoration: hidden from assistive technology and holding nothing focusable', () => {
        const { host } = render({ viewport: { width: 320, height: 200 } });
        expect(host.getAttribute('aria-hidden')).toBe('true');
        expect(host.querySelectorAll('button, a, input, [tabindex]')).toHaveLength(0);
    });

    it('paints the whole viewport when there is no cut-out', () => {
        const { host } = render({ viewport: { width: 320, height: 200 } });
        const svg = host.querySelector('svg')!;
        expect(svg.getAttribute('width')).toBe('320');
        expect(svg.getAttribute('height')).toBe('200');
        expect(svg.getAttribute('viewBox')).toBe('0 0 320 200');
        expect(host.querySelector('path')!.getAttribute('d')).toBe('M0 0H320V200H0Z');
        expect(host.querySelector('.frame')).toBeNull();
    });

    it('subtracts the cut-out by the even-odd rule, so the target is not covered and is reachable', () => {
        const { host } = render({ viewport: { width: 320, height: 200 }, hole: { x: 20, y: 30, width: 100, height: 40 } });
        const path = host.querySelector('path')!;
        expect(path.getAttribute('fill-rule')).toBe('evenodd');
        expect(path.getAttribute('d')).toContain('M22 30');
        expect(path.getAttribute('d')!.match(/Z/gu)).toHaveLength(2);
    });

    it('stands a frame off the cut-out and follows it', () => {
        const { fixture, host } = render({ viewport: { width: 320, height: 200 }, hole: { x: 20, y: 30, width: 100, height: 40 } });
        const frame = host.querySelector<HTMLElement>('.frame')!;
        expect([frame.style.left, frame.style.top, frame.style.width, frame.style.height]).toEqual(['20px', '30px', '100px', '40px']);
        fixture.componentRef.setInput('hole', { x: 50, y: 60, width: 10, height: 12 });
        fixture.detectChanges();
        expect([frame.style.left, frame.style.top, frame.style.width, frame.style.height]).toEqual(['50px', '60px', '10px', '12px']);
        fixture.componentRef.setInput('hole', null);
        fixture.detectChanges();
        expect(host.querySelector('.frame')).toBeNull();
    });

    it('rounds the cut-out with the radius it is given', () => {
        const { host } = render({ viewport: { width: 320, height: 200 }, hole: { x: 20, y: 30, width: 100, height: 40 }, radius: 6 });
        expect(host.querySelector('path')!.getAttribute('d')).toContain('A6 6 0 0 1');
    });

    it('covers its positioned parent instead of the window when contained', () => {
        const { host } = render({ viewport: { width: 320, height: 200 }, contained: true });
        expect(host.classList.contains('contained')).toBe(true);
    });
});
