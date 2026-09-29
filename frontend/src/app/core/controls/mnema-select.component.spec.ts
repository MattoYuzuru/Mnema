import { TestBed } from '@angular/core/testing';

import { MnemaSelectComponent } from './mnema-select.component';

describe('MnemaSelectComponent', () => {
    function setup() {
        const fixture = TestBed.createComponent(MnemaSelectComponent);
        fixture.componentRef.setInput('controlId', 'sample-choice');
        fixture.componentRef.setInput('label', 'Вариант');
        fixture.componentRef.setInput('value', 'one');
        fixture.componentRef.setInput('options', [
            { value: '', label: 'Выберите вариант' },
            { value: 'one', label: 'Один' },
            { value: 'two', label: 'Два' },
            { value: 'three', label: 'Три', disabled: true }
        ]);
        document.body.appendChild(fixture.nativeElement);
        fixture.detectChanges();
        const trigger = fixture.nativeElement.querySelector('[role="combobox"]') as HTMLButtonElement;
        return { fixture, trigger };
    }

    it('exposes selected value and navigates without committing until Enter', () => {
        const { fixture, trigger } = setup();
        const emitted = spyOn(fixture.componentInstance.valueChange, 'emit');
        expect(trigger.textContent).toContain('Один');
        expect(trigger.getAttribute('aria-expanded')).toBe('false');

        trigger.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
        fixture.detectChanges();
        expect(trigger.getAttribute('aria-expanded')).toBe('true');
        expect(trigger.getAttribute('aria-activedescendant')).toBe('sample-choice-option-1');
        trigger.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
        fixture.detectChanges();
        expect(trigger.getAttribute('aria-activedescendant')).toBe('sample-choice-option-2');
        expect(emitted).not.toHaveBeenCalled();

        trigger.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
        fixture.detectChanges();
        expect(emitted).toHaveBeenCalledOnceWith('two');
        expect(trigger.getAttribute('aria-expanded')).toBe('false');
        fixture.destroy();
    });

    it('skips disabled options, supports Home/End and cancels with Escape', () => {
        const { fixture, trigger } = setup();
        const emitted = spyOn(fixture.componentInstance.valueChange, 'emit');
        trigger.click();
        trigger.dispatchEvent(new KeyboardEvent('keydown', { key: 'End', bubbles: true }));
        fixture.detectChanges();
        expect(trigger.getAttribute('aria-activedescendant')).toBe('sample-choice-option-2');
        trigger.dispatchEvent(new KeyboardEvent('keydown', { key: 'Home', bubbles: true }));
        fixture.detectChanges();
        expect(trigger.getAttribute('aria-activedescendant')).toBe('sample-choice-option-0');
        trigger.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
        fixture.detectChanges();
        expect(trigger.getAttribute('aria-expanded')).toBe('false');
        expect(emitted).not.toHaveBeenCalled();
        fixture.destroy();
    });

    it('supports typeahead, pointer selection, outside dismissal, and disabled state', () => {
        const { fixture, trigger } = setup();
        const emitted = spyOn(fixture.componentInstance.valueChange, 'emit');
        trigger.dispatchEvent(new KeyboardEvent('keydown', { key: 'д', bubbles: true }));
        fixture.detectChanges();
        expect(trigger.getAttribute('aria-activedescendant')).toBe('sample-choice-option-2');
        (fixture.nativeElement.querySelector('#sample-choice-option-2') as HTMLElement).click();
        expect(emitted).toHaveBeenCalledOnceWith('two');

        trigger.click();
        document.body.dispatchEvent(new Event('pointerdown', { bubbles: true }));
        fixture.detectChanges();
        expect(trigger.getAttribute('aria-expanded')).toBe('false');

        fixture.componentRef.setInput('disabled', true);
        fixture.detectChanges();
        expect(trigger.disabled).toBeTrue();
        trigger.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
        fixture.detectChanges();
        expect(trigger.getAttribute('aria-expanded')).toBe('false');
        fixture.destroy();
    });
});
