import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { TourAnchorDirective } from './tour-anchor.directive';
import { TourAnchors } from './tour-anchors';

@Component({
    imports: [TourAnchorDirective],
    template: `@if (shown()) { <button [mnTourAnchor]="key()">Найти</button> }`
})
class HostComponent {
    readonly shown = signal(true);
    readonly key = signal('community.search');
}

describe('TourAnchors', () => {
    it('registers an element under a key and replaces an earlier one with the later one', () => {
        const anchors = TestBed.inject(TourAnchors);
        const first = document.createElement('div');
        const second = document.createElement('div');
        const dropFirst = anchors.register('hub.start', first);
        anchors.register('hub.start', second);
        expect(anchors.lookup('hub.start')).toBe(second);
        // The older registration does not remove its successor.
        dropFirst();
        expect(anchors.lookup('hub.start')).toBe(second);
        expect(anchors.lookup('missing')).toBeNull();
    });

    it('is reactive: a computed lookup follows registration and removal', () => {
        const anchors = TestBed.inject(TourAnchors);
        const element = document.createElement('div');
        const lookups: (HTMLElement | null)[] = [];
        const seen = signal(0);
        const read = (): HTMLElement | null => { seen(); return anchors.lookup('nav.new'); };
        lookups.push(read());
        const drop = anchors.register('nav.new', element);
        lookups.push(read());
        drop();
        lookups.push(read());
        expect(lookups).toEqual([null, element, null]);
    });
});

describe('mnTourAnchor', () => {
    async function mount() {
        TestBed.configureTestingModule({ imports: [HostComponent] });
        const fixture = TestBed.createComponent(HostComponent);
        fixture.detectChanges();
        await fixture.whenStable();
        return { fixture, anchors: TestBed.inject(TourAnchors), host: fixture.componentInstance };
    }

    it('registers its element under the stable key and says so in the DOM', async () => {
        const { fixture, anchors } = await mount();
        const button = fixture.nativeElement.querySelector('button') as HTMLButtonElement;
        expect(anchors.lookup('community.search')).toBe(button);
        expect(button.getAttribute('data-tour-anchor')).toBe('community.search');
    });

    it('unregisters when the element is destroyed', async () => {
        const { fixture, anchors, host } = await mount();
        host.shown.set(false);
        fixture.detectChanges();
        await fixture.whenStable();
        expect(anchors.lookup('community.search')).toBeNull();
    });

    it('moves the registration when the key changes', async () => {
        const { fixture, anchors, host } = await mount();
        const button = fixture.nativeElement.querySelector('button') as HTMLButtonElement;
        host.key.set('community.add');
        fixture.detectChanges();
        await fixture.whenStable();
        expect(anchors.lookup('community.search')).toBeNull();
        expect(anchors.lookup('community.add')).toBe(button);
    });
});
