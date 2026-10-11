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
        const emitted = vi.spyOn(fixture.componentInstance.valueChange, 'emit').mockReturnValue(undefined);
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
        expect(emitted).toHaveBeenCalledTimes(1);
        expect(emitted).toHaveBeenCalledWith('two');
        expect(trigger.getAttribute('aria-expanded')).toBe('false');
        fixture.destroy();
    });

    it('skips disabled options, supports Home/End and cancels with Escape', () => {
        const { fixture, trigger } = setup();
        const emitted = vi.spyOn(fixture.componentInstance.valueChange, 'emit').mockReturnValue(undefined);
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
        const emitted = vi.spyOn(fixture.componentInstance.valueChange, 'emit').mockReturnValue(undefined);
        trigger.dispatchEvent(new KeyboardEvent('keydown', { key: 'д', bubbles: true }));
        fixture.detectChanges();
        expect(trigger.getAttribute('aria-activedescendant')).toBe('sample-choice-option-2');
        (fixture.nativeElement.querySelector('#sample-choice-option-2') as HTMLElement).click();
        expect(emitted).toHaveBeenCalledTimes(1);
        expect(emitted).toHaveBeenCalledWith('two');

        trigger.click();
        document.body.dispatchEvent(new Event('pointerdown', { bubbles: true }));
        fixture.detectChanges();
        expect(trigger.getAttribute('aria-expanded')).toBe('false');

        fixture.componentRef.setInput('disabled', true);
        fixture.detectChanges();
        expect(trigger.disabled).toBe(true);
        trigger.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
        fixture.detectChanges();
        expect(trigger.getAttribute('aria-expanded')).toBe('false');
        fixture.destroy();
    });

    describe('option groups', () => {
        function grouped() {
            const fixture = TestBed.createComponent(MnemaSelectComponent);
            fixture.componentRef.setInput('controlId', 'topic');
            fixture.componentRef.setInput('label', 'Тема');
            fixture.componentRef.setInput('value', '');
            fixture.componentRef.setInput('describedBy', 'topic-state');
            fixture.componentRef.setInput('options', [
                { value: 'en', label: 'Английский', group: 'Языки' },
                { value: 'ja', label: 'Японский', group: 'Языки' },
                { value: 'bio', label: 'Биология', group: 'Наука' },
                { value: 'other', label: 'Другое' }
            ]);
            document.body.appendChild(fixture.nativeElement);
            fixture.detectChanges();
            const trigger = fixture.nativeElement.querySelector('[role="combobox"]') as HTMLButtonElement;
            trigger.click();
            fixture.detectChanges();
            return { fixture, trigger };
        }

        it('draws each run of a group in a named role=group whose heading is text, not an option', () => {
            const { fixture } = grouped();
            const root = fixture.nativeElement as HTMLElement;
            const groups = [...root.querySelectorAll<HTMLElement>('[role=group]')];
            expect(groups).toHaveLength(2);
            for (const group of groups) {
                const heading = root.querySelector<HTMLElement>(`#${group.getAttribute('aria-labelledby')}`)!;
                expect(heading.getAttribute('role')).toBeNull();
                expect(group.contains(heading)).toBe(true);
            }
            expect(groups.map(group => group.querySelector('.group-label')!.textContent)).toEqual(['Языки', 'Наука']);
            expect([...groups[0].querySelectorAll('[role=option]')].map(option => option.textContent)).toEqual(['Английский', 'Японский']);
            const options = [...root.querySelectorAll('[role=listbox] [role=option]')];
            expect(options.map(option => option.textContent)).toEqual(['Английский', 'Японский', 'Биология', 'Другое']);
            expect(options.every(option => option.getAttribute('aria-disabled') === null)).toBe(true);
            expect(root.querySelector('[role=listbox] > [role=option]')!.textContent).toBe('Другое');
            fixture.destroy();
        });

        it('keeps flat option ids and keyboard order across the headings', () => {
            const { fixture, trigger } = grouped();
            expect(trigger.getAttribute('aria-activedescendant')).toBe('topic-option-0');
            for (const id of ['topic-option-1', 'topic-option-2', 'topic-option-3', 'topic-option-0']) {
                trigger.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
                fixture.detectChanges();
                expect(trigger.getAttribute('aria-activedescendant')).toBe(id);
                expect(fixture.nativeElement.querySelector(`#${id}`)!.getAttribute('role')).toBe('option');
            }
            fixture.destroy();
        });

        it('chooses with the pointer and names its description on the trigger', () => {
            const { fixture, trigger } = grouped();
            const emitted = vi.spyOn(fixture.componentInstance.valueChange, 'emit').mockReturnValue(undefined);
            expect(trigger.getAttribute('aria-describedby')).toBe('topic-state');
            (fixture.nativeElement.querySelector('#topic-option-2') as HTMLElement).click();
            expect(emitted).toHaveBeenCalledWith('bio');
            fixture.destroy();
        });
    });
});
