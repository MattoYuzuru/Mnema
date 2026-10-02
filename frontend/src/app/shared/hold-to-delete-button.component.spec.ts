import { TestBed } from '@angular/core/testing';

import { HoldToDeleteButtonComponent } from './hold-to-delete-button.component';

describe('HoldToDeleteButtonComponent', () => {
    beforeEach(() => {
        vi.useFakeTimers({ advanceTimeDelta: 1, shouldAdvanceTime: true });
    });
    afterEach(() => {
        vi.useRealTimers();
    });
    function setup() {
        TestBed.configureTestingModule({ imports: [HoldToDeleteButtonComponent] });
        const fixture = TestBed.createComponent(HoldToDeleteButtonComponent);
        fixture.componentRef.setInput('label', 'Удалить упражнение');
        fixture.detectChanges();
        return { fixture, component: fixture.componentInstance,
            button: fixture.nativeElement.querySelector('button') as HTMLButtonElement };
    }

    it('requires a separate activation and an uninterrupted three-second keyboard hold', async () => {
        const { fixture, component, button } = setup();
        let confirmations = 0;
        component.confirmed.subscribe(() => confirmations++);
        button.click();
        expect(component.armed()).toBe(true);
        button.dispatchEvent(new KeyboardEvent('keydown', { key: ' ', bubbles: true, cancelable: true }));
        await vi.advanceTimersByTimeAsync(2975);
        expect(confirmations).toBe(0);
        await vi.advanceTimersByTimeAsync(25);
        expect(confirmations).toBe(1);
        expect(component.armed()).toBe(false);
        button.dispatchEvent(new KeyboardEvent('keyup', { key: ' ', bubbles: true, cancelable: true }));
        button.click();
        expect(component.armed()).toBe(false);
        button.dispatchEvent(new KeyboardEvent('keydown', { key: ' ', bubbles: true, cancelable: true }));
        button.click();
        expect(component.armed()).toBe(true);
        fixture.destroy();
    });

    it('disarms when pressing a non-focusable surface outside the control', () => {
        const { fixture, component, button } = setup();
        button.click();
        expect(component.armed()).toBe(true);
        document.body.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }));
        expect(component.armed()).toBe(false);
        fixture.destroy();
    });

    it('keeps its geometry unchanged when the longer countdown label appears', () => {
        const { fixture, button } = setup();
        const before = button.getBoundingClientRect().toJSON();
        button.click();
        fixture.detectChanges();
        expect(button.textContent).toContain('Удерживайте 3 с');
        expect(button.getBoundingClientRect().toJSON()).toEqual(before);
        expect(fixture.nativeElement.querySelector('.hold-hint')).toBeNull();
        fixture.destroy();
    });

    it('cancels an early pointer release and supports Escape', async () => {
        const { fixture, component, button } = setup();
        let confirmations = 0;
        component.confirmed.subscribe(() => confirmations++);
        button.click();
        button.dispatchEvent(new PointerEvent('pointerdown', { button: 0, clientX: 5, clientY: 5, bubbles: true }));
        await vi.advanceTimersByTimeAsync(1000);
        button.dispatchEvent(new PointerEvent('pointerup', { bubbles: true }));
        await vi.advanceTimersByTimeAsync(3000);
        expect(confirmations).toBe(0);
        expect(component.armed()).toBe(true);
        button.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
        expect(component.armed()).toBe(false);
        fixture.destroy();
    });

    it('confirms after an uninterrupted pointer hold and can be armed again', async () => {
        const { fixture, component, button } = setup();
        let confirmations = 0;
        component.confirmed.subscribe(() => confirmations++);
        button.click();
        button.dispatchEvent(new PointerEvent('pointerdown', { button: 0, clientX: 8, clientY: 8, bubbles: true }));
        await vi.advanceTimersByTimeAsync(3000);
        expect(confirmations).toBe(1);
        expect(component.armed()).toBe(false);
        button.dispatchEvent(new PointerEvent('pointerup', { bubbles: true }));
        button.click();
        expect(component.armed()).toBe(false);
        button.dispatchEvent(new PointerEvent('pointerdown', { button: 0, bubbles: true }));
        button.click();
        expect(component.armed()).toBe(true);
        fixture.destroy();
    });
});
