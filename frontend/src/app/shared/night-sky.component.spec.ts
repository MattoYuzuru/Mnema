import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { NightSkyComponent } from './night-sky.component';

@Component({
    imports: [NightSkyComponent],
    template: `<app-night-sky [seed]="seed()" [stars]="stars()" [layout]="layout()"><h1 id="welcome-title" tabindex="-1">Начало пути</h1><button type="button">Познакомиться</button></app-night-sky>`
})
class HostComponent {
    readonly seed = signal('welcome');
    readonly stars = signal(true);
    readonly layout = signal<'screen' | 'panel'>('screen');
}

describe('NightSkyComponent', () => {
    function render() {
        TestBed.configureTestingModule({ imports: [HostComponent] });
        const fixture = TestBed.createComponent(HostComponent);
        fixture.detectChanges();
        const sky = fixture.nativeElement.querySelector('app-night-sky') as HTMLElement;
        return { fixture, sky, host: fixture.componentInstance };
    }

    it('draws the stars as decoration: the sky is hidden from assistive technology and holds no control', () => {
        const { sky } = render();
        const stars = sky.querySelector('.sky')!;
        expect(stars.getAttribute('aria-hidden')).toBe('true');
        expect(stars.querySelectorAll('.star').length).toBeGreaterThan(10);
        expect(stars.querySelectorAll('button, a, [tabindex]')).toHaveLength(0);
    });

    it('leaves the projected content to assistive technology', () => {
        const { sky } = render();
        const content = sky.querySelector('.content')!;
        expect(content.closest('[aria-hidden="true"]')).toBeNull();
        expect(sky.getAttribute('aria-hidden')).toBeNull();
        expect(content.querySelector('h1')?.textContent).toBe('Начало пути');
        expect(content.querySelector('button')?.textContent).toBe('Познакомиться');
    });

    it('has the lavender and paper stars of the constellation motif, some twinkling', () => {
        const { sky } = render();
        expect(sky.querySelectorAll('.star.lavender').length).toBeGreaterThan(0);
        expect(sky.querySelectorAll('.star.paper').length).toBeGreaterThan(0);
        expect(sky.querySelectorAll('.star.twinkle').length).toBeGreaterThan(0);
        expect([...sky.querySelectorAll('.star:not(.dot)')].every(star => star.textContent === '✧')).toBe(true);
    });

    it('draws no stars for the survey and the branch choice, with the same ground and content', () => {
        const { fixture, sky, host } = render();
        host.stars.set(false);
        fixture.detectChanges();
        expect(sky.querySelector('.sky')).toBeNull();
        expect(sky.querySelector('.content h1')).not.toBeNull();
    });

    it('draws the same sky for the same seed and another for another seed', () => {
        const { fixture, sky, host } = render();
        const positions = (): string[] => [...sky.querySelectorAll<HTMLElement>('.star')].map(star => `${star.style.left},${star.style.top}`);
        const first = positions();
        host.seed.set('congratulations');
        fixture.detectChanges();
        expect(positions()).not.toEqual(first);
        host.seed.set('welcome');
        fixture.detectChanges();
        expect(positions()).toEqual(first);
    });

    it('covers the window as a screen and fills its parent as a panel', () => {
        const { fixture, sky, host } = render();
        expect(sky.classList.contains('screen')).toBe(true);
        host.layout.set('panel');
        fixture.detectChanges();
        expect(sky.classList.contains('screen')).toBe(false);
    });
});
