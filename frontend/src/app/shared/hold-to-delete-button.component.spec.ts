import { TestBed, fakeAsync, tick } from '@angular/core/testing';

import { HoldToDeleteButtonComponent } from './hold-to-delete-button.component';

describe('HoldToDeleteButtonComponent', () => {
    function setup() {
        TestBed.configureTestingModule({ imports: [HoldToDeleteButtonComponent] });
        const fixture = TestBed.createComponent(HoldToDeleteButtonComponent);
        fixture.componentRef.setInput('label', 'Удалить упражнение');
        fixture.detectChanges();
        return { fixture, component: fixture.componentInstance,
            button: fixture.nativeElement.querySelector('button') as HTMLButtonElement };
    }

    it('requires a separate activation and an uninterrupted three-second keyboard hold', fakeAsync(() => {
        const { fixture, component, button } = setup();
        let confirmations = 0;
        component.confirmed.subscribe(() => confirmations++);
        button.click();
        expect(component.armed()).toBeTrue();
        button.dispatchEvent(new KeyboardEvent('keydown', { key: ' ', bubbles: true, cancelable: true }));
        tick(2_975);
        expect(confirmations).toBe(0);
        tick(25);
        expect(confirmations).toBe(1);
        expect(component.armed()).toBeFalse();
        button.dispatchEvent(new KeyboardEvent('keyup', { key: ' ', bubbles: true, cancelable: true }));
        button.click();
        expect(component.armed()).toBeFalse();
        button.dispatchEvent(new KeyboardEvent('keydown', { key: ' ', bubbles: true, cancelable: true }));
        button.click();
        expect(component.armed()).toBeTrue();
        fixture.destroy();
    }));

    it('cancels an early pointer release and supports Escape', fakeAsync(() => {
        const { fixture, component, button } = setup();
        let confirmations = 0;
        component.confirmed.subscribe(() => confirmations++);
        button.click();
        button.dispatchEvent(new PointerEvent('pointerdown', { button: 0, clientX: 5, clientY: 5, bubbles: true }));
        tick(1_000);
        button.dispatchEvent(new PointerEvent('pointerup', { bubbles: true }));
        tick(3_000);
        expect(confirmations).toBe(0);
        expect(component.armed()).toBeTrue();
        button.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
        expect(component.armed()).toBeFalse();
        fixture.destroy();
    }));

    it('confirms after an uninterrupted pointer hold and can be armed again', fakeAsync(() => {
        const { fixture, component, button } = setup();
        let confirmations = 0;
        component.confirmed.subscribe(() => confirmations++);
        button.click();
        button.dispatchEvent(new PointerEvent('pointerdown', { button: 0, clientX: 8, clientY: 8, bubbles: true }));
        tick(3_000);
        expect(confirmations).toBe(1);
        expect(component.armed()).toBeFalse();
        button.dispatchEvent(new PointerEvent('pointerup', { bubbles: true }));
        button.click();
        expect(component.armed()).toBeFalse();
        button.dispatchEvent(new PointerEvent('pointerdown', { button: 0, bubbles: true }));
        button.click();
        expect(component.armed()).toBeTrue();
        fixture.destroy();
    }));
});
