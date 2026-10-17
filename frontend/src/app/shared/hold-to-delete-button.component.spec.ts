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

    it('can stay in the focus order while disabled (aria-disabled, no disabled attribute) and still refuse every action', async () => {
        const { fixture, component, button } = setup();
        fixture.componentRef.setInput('disabled', true);
        fixture.componentRef.setInput('focusable', true);
        fixture.detectChanges();
        let confirmations = 0;
        component.confirmed.subscribe(() => confirmations++);
        expect(button.disabled).toBe(false);
        expect(button.getAttribute('aria-disabled')).toBe('true');
        button.focus();
        expect(document.activeElement).toBe(button);
        button.click();
        button.dispatchEvent(new KeyboardEvent('keydown', { key: ' ', bubbles: true, cancelable: true }));
        await vi.advanceTimersByTimeAsync(3500);
        expect(component.armed()).toBe(false);
        expect(confirmations).toBe(0);
        fixture.componentRef.setInput('disabled', false);
        fixture.detectChanges();
        expect(button.hasAttribute('aria-disabled')).toBe(false);
    });

    it('drops out of the focus order with the plain disabled attribute unless it was asked to stay', () => {
        const { fixture, button } = setup();
        fixture.componentRef.setInput('disabled', true);
        fixture.detectChanges();
        expect(button.disabled).toBe(true);
        expect(button.hasAttribute('aria-disabled')).toBe(false);
    });

    describe('deletion defaults', () => {
        it('keeps the three-second hold with its countdown label and the danger tone', async () => {
            const { fixture, component, button } = setup();
            let confirmations = 0;
            component.confirmed.subscribe(() => confirmations++);
            button.click();
            fixture.detectChanges();
            expect(button.classList.contains('tone-ink')).toBe(false);
            expect(button.classList.contains('has-glyph')).toBe(false);
            expect(button.hasAttribute('aria-label')).toBe(false);
            expect(button.querySelector('app-glyph')).toBeNull();
            button.dispatchEvent(new PointerEvent('pointerdown', { button: 0, clientX: 4, clientY: 4, bubbles: true }));
            await vi.advanceTimersByTimeAsync(1000);
            fixture.detectChanges();
            expect(component.remaining()).toBe(2);
            expect(button.querySelector('.hold-label')?.textContent).toBe('Удерживайте 2 с');
            await vi.advanceTimersByTimeAsync(1975);
            expect(confirmations).toBe(0);
            await vi.advanceTimersByTimeAsync(25);
            expect(confirmations).toBe(1);
        });

        it('never uses the stepped fill, whatever the preview switch says', () => {
            const { fixture, component, button } = setup();
            fixture.componentRef.setInput('steppedFill', true);
            fixture.detectChanges();
            expect(component.stepped()).toBe(false);
            expect(button.classList.contains('is-stepped')).toBe(false);
        });
    });

    describe('the early finish of the tour (two seconds, a glyph instead of the label)', () => {
        const FINISH = 'Завершить знакомство';
        const ARMED_NAME = 'Удерживайте 2\u00a0секунды, чтобы завершить знакомство';

        function setupFinish(inputs: Record<string, unknown> = {}) {
            TestBed.configureTestingModule({ imports: [HoldToDeleteButtonComponent] });
            const fixture = TestBed.createComponent(HoldToDeleteButtonComponent);
            const values: Record<string, unknown> = { label: FINISH, holdMs: 2000, armedGlyph: 'finger-hold', armedName: ARMED_NAME, tone: 'ink', ...inputs };
            for (const [name, value] of Object.entries(values)) fixture.componentRef.setInput(name, value);
            fixture.detectChanges();
            const component = fixture.componentInstance;
            let confirmations = 0;
            component.confirmed.subscribe(() => confirmations++);
            return { fixture, component, confirmations: () => confirmations,
                button: fixture.nativeElement.querySelector('button') as HTMLButtonElement };
        }
        const press = (button: HTMLButtonElement, clientX = 6, clientY = 6): void => {
            button.dispatchEvent(new PointerEvent('pointerdown', { button: 0, clientX, clientY, bubbles: true }));
        };

        it('shows the label first; the first press only arms and shows a glyph with no text and the instruction as its name', () => {
            const { fixture, component, button } = setupFinish();
            expect(button.textContent).toContain(FINISH);
            expect(button.hasAttribute('aria-label')).toBe(false);
            expect(button.querySelector('app-glyph')).toBeNull();
            button.click();
            fixture.detectChanges();
            expect(component.armed()).toBe(true);
            expect(button.getAttribute('aria-pressed')).toBe('true');
            expect(button.getAttribute('aria-label')).toBe(ARMED_NAME);
            expect(button.querySelector('app-glyph')?.getAttribute('data-glyph')).toBe('finger-hold');
            // The glyph replaces the label: the only text left is the invisible width reserve.
            expect(button.querySelector('.hold-label')).toBeNull();
            const visible = [...button.children].filter(child => !child.hasAttribute('aria-hidden') || child.tagName === 'APP-GLYPH');
            expect(visible.map(child => child.tagName)).toEqual(['APP-GLYPH']);
            expect(button.textContent).not.toContain('Удерживайте');
            expect(button.classList.contains('tone-ink')).toBe(true);
            expect(button.classList.contains('has-glyph')).toBe(true);
        });

        it('finishes after an uninterrupted two-second pointer hold and not a moment earlier', async () => {
            const { fixture, component, button, confirmations } = setupFinish();
            button.click();
            press(button);
            await vi.advanceTimersByTimeAsync(1975);
            expect(confirmations()).toBe(0);
            await vi.advanceTimersByTimeAsync(25);
            expect(confirmations()).toBe(1);
            expect(component.armed()).toBe(false);
            fixture.detectChanges();
            expect(button.textContent).toContain(FINISH);
            expect(button.hasAttribute('aria-label')).toBe(false);
        });

        it('does nothing when the button is released early, and stays armed', async () => {
            const { fixture, component, button, confirmations } = setupFinish();
            button.click();
            press(button);
            await vi.advanceTimersByTimeAsync(900);
            button.dispatchEvent(new PointerEvent('pointerup', { bubbles: true }));
            await vi.advanceTimersByTimeAsync(3000);
            expect(confirmations()).toBe(0);
            expect(component.armed()).toBe(true);
            expect(component.holding()).toBe(false);
            expect(component.elapsed()).toBe(0);
            fixture.detectChanges();
        });

        it('does not start a hold from a press on a button that is not armed', async () => {
            const { button, confirmations, component } = setupFinish();
            press(button);
            await vi.advanceTimersByTimeAsync(3000);
            expect(confirmations()).toBe(0);
            expect(component.holding()).toBe(false);
        });

        it('disarms on Escape, without letting Escape reach the surroundings', () => {
            const { fixture, component, button } = setupFinish();
            let reached = 0;
            fixture.nativeElement.addEventListener('keydown', () => reached++);
            button.click();
            button.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
            expect(component.armed()).toBe(false);
            expect(reached).toBe(0);
        });

        it('disarms when the focus leaves the button and on a press outside', () => {
            const { fixture, component, button } = setupFinish();
            button.click();
            button.dispatchEvent(new FocusEvent('blur'));
            expect(component.armed()).toBe(false);
            button.click();
            expect(component.armed()).toBe(true);
            document.body.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }));
            expect(component.armed()).toBe(false);
            fixture.detectChanges();
        });

        it.each(['Enter', ' '])('finishes after a two-second hold of the %j key, as the deletion does with its three', async key => {
            const { component, button, confirmations } = setupFinish();
            button.click();
            button.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
            await vi.advanceTimersByTimeAsync(1975);
            expect(confirmations()).toBe(0);
            await vi.advanceTimersByTimeAsync(25);
            expect(confirmations()).toBe(1);
            expect(component.armed()).toBe(false);
            button.dispatchEvent(new KeyboardEvent('keyup', { key, bubbles: true, cancelable: true }));
        });

        it('stops a keyboard hold when the key is let go', async () => {
            const { button, confirmations, component } = setupFinish();
            button.click();
            button.dispatchEvent(new KeyboardEvent('keydown', { key: ' ', bubbles: true, cancelable: true }));
            await vi.advanceTimersByTimeAsync(1000);
            button.dispatchEvent(new KeyboardEvent('keyup', { key: ' ', bubbles: true, cancelable: true }));
            await vi.advanceTimersByTimeAsync(3000);
            expect(confirmations()).toBe(0);
            expect(component.armed()).toBe(true);
        });

        it('moves the fill in four discrete steps when asked for the calm fill, and in a wave otherwise', async () => {
            const calm = setupFinish({ steppedFill: true });
            expect(calm.button.classList.contains('is-stepped')).toBe(true);
            calm.button.click();
            press(calm.button);
            const steps: number[] = [];
            for (let tick = 0; tick < 8; tick++) {
                await vi.advanceTimersByTimeAsync(240);
                calm.fixture.detectChanges();
                steps.push(calm.component.step());
                const share = Number.parseFloat(calm.button.style.getPropertyValue('--hold-step'));
                expect([0, 25, 50, 75, 100]).toContain(share);
            }
            // Four steps, and the fill never takes a value in between (the last one is reached only as the hold completes).
            expect(new Set(steps)).toEqual(new Set([0, 1, 2, 3]));
        });

        it('fills in a wave, not in steps, unless the calm fill is asked for', () => {
            const { button, component } = setupFinish();
            expect(component.stepped()).toBe(false);
            expect(button.classList.contains('is-stepped')).toBe(false);
        });

        it('takes the calm fill from the system setting when the browser asks for reduced motion', () => {
            const motion = { matches: true, addEventListener: vi.fn(), removeEventListener: vi.fn() };
            vi.stubGlobal('matchMedia', (query: string) => ({ ...motion, media: query }));
            const { button, component } = setupFinish();
            expect(component.stepped()).toBe(true);
            expect(button.classList.contains('is-stepped')).toBe(true);
        });

        it('leaves the deletion with its countdown and its hidden wave under reduced motion: no glyph, no stepped fill', () => {
            vi.stubGlobal('matchMedia', (query: string) => ({ matches: true, media: query, addEventListener: vi.fn(), removeEventListener: vi.fn() }));
            const deletion = setup();
            expect(deletion.component.stepped()).toBe(false);
        });

        it('follows a change of the system setting and lets go of the listener on destroy', () => {
            let listener: ((event: MediaQueryListEvent) => void) | null = null;
            const remove = vi.fn();
            vi.stubGlobal('matchMedia', () => ({ matches: false, addEventListener: (_: string, fn: typeof listener) => { listener = fn; }, removeEventListener: remove }));
            const { fixture, component } = setupFinish();
            expect(component.stepped()).toBe(false);
            listener!({ matches: true } as MediaQueryListEvent);
            expect(component.stepped()).toBe(true);
            fixture.destroy();
            expect(remove).toHaveBeenCalledWith('change', listener);
        });

        it('keeps the three-second countdown reserve for a glyph-less button with another duration', () => {
            const { fixture, button } = setupFinish({ armedGlyph: null, holdMs: 2000 });
            button.click();
            fixture.detectChanges();
            expect(button.textContent).toContain('Удерживайте 2 с');
        });
    });
});
