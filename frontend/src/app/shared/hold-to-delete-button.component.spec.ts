import { TestBed } from '@angular/core/testing';

import { HoldToDeleteButtonComponent } from './hold-to-delete-button.component';

describe('HoldToDeleteButtonComponent', () => {
    beforeEach(() => {
        vi.useFakeTimers();
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

    // That the button keeps its box when the countdown label appears is geometry jsdom cannot measure; the browser
    // harness owns it (scripts/browser-identity, scenario mechanics_hold_to_delete_geometry).
    it('shows the countdown label without a separate hint when armed', () => {
        const { fixture, button } = setup();
        button.click();
        fixture.detectChanges();
        expect(button.textContent).toContain('Удерживайте 3 с');
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

    describe('consequence', () => {
        function withConsequence(text: string) {
            const setupResult = setup();
            setupResult.fixture.componentRef.setInput('consequence', text);
            setupResult.fixture.detectChanges();
            return setupResult;
        }

        it('is the button\'s description at all times and visible only while the button is armed', () => {
            const { fixture, button } = withConsequence('Удалит 7 материалов и 15 упражнений. История занятий сохранится.');
            const note = fixture.nativeElement.querySelector('.consequence') as HTMLElement;
            expect(button.getAttribute('aria-describedby')).toBe(note.id);
            expect(note.hidden).toBe(true);
            button.click();
            fixture.detectChanges();
            expect(note.hidden).toBe(false);
            expect(note.textContent).toContain('15 упражнений');
            fixture.destroy();
        });

        it('adds nothing when no consequence is given', () => {
            const { fixture, button } = setup();
            expect(button.hasAttribute('aria-describedby')).toBe(false);
            expect(fixture.nativeElement.querySelector('.consequence')).toBeNull();
            fixture.destroy();
        });
    });

    describe('Escape', () => {
        it('disarms an armed button and stays there, so a surrounding selection is not cleared at the same time', () => {
            const { fixture, component, button } = setup();
            let reached = 0;
            fixture.nativeElement.addEventListener('keydown', () => reached++);
            button.click();
            expect(component.armed()).toBe(true);
            button.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
            expect(component.armed()).toBe(false);
            expect(reached).toBe(0);
            fixture.destroy();
        });

        it('bubbles to the surroundings when the button is not armed', () => {
            const { fixture, button } = setup();
            let reached = 0;
            fixture.nativeElement.addEventListener('keydown', () => reached++);
            const event = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
            button.dispatchEvent(event);
            expect(reached).toBe(1);
            expect(event.defaultPrevented).toBe(false);
            fixture.destroy();
        });
    });
});
