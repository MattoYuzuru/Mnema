import { ComponentFixture, TestBed } from '@angular/core/testing';

import { Mechanic } from '../../content/exercise/exercise-content.models';
import { MECHANIC_CATALOG } from '../../content/exercise/mechanic-catalog';
import { MechanicChoice, MechanicPickerComponent } from './mechanic-picker.component';

describe('MechanicPickerComponent', () => {
    let fixture: ComponentFixture<MechanicPickerComponent>;
    let chosen: MechanicChoice[];

    function create(selected: Mechanic | null = null, pending: Mechanic | null = null): void {
        fixture = TestBed.createComponent(MechanicPickerComponent);
        fixture.componentRef.setInput('entries', MECHANIC_CATALOG);
        fixture.componentRef.setInput('selected', selected);
        fixture.componentRef.setInput('pending', pending);
        chosen = [];
        fixture.componentInstance.chosen.subscribe(choice => chosen.push(choice));
        fixture.detectChanges();
    }
    const radio = (mechanic: Mechanic) => (fixture.nativeElement as HTMLElement)
        .querySelector<HTMLInputElement>(`input[value="${mechanic}"]`)!;

    it('renders a native radio group named by its numbered legend with one big labelled tile per catalog entry', () => {
        create();
        const root = fixture.nativeElement as HTMLElement;
        expect(root.querySelector('fieldset > legend')?.textContent).toBe('1. Тип упражнения');
        expect(root.querySelectorAll('label.tile input[type="radio"][name="mechanic"]').length).toBe(MECHANIC_CATALOG.length);
        expect(root.querySelector('label.tile')?.textContent).toContain(MECHANIC_CATALOG[0].description);
        expect([...root.querySelectorAll<HTMLInputElement>('input')].some(input => input.checked)).toBeFalse();
    });

    it('marks the selected tile and shows a pending one as chosen until the author decides', () => {
        create('CHOICE');
        expect(radio('CHOICE').checked).toBeTrue();
        fixture.componentRef.setInput('pending', 'MATCH'); fixture.detectChanges();
        expect(radio('MATCH').checked).toBeTrue();
        expect(radio('CHOICE').checked).toBeFalse();
        fixture.componentRef.setInput('pending', null); fixture.detectChanges();
        expect(radio('CHOICE').checked).toBeTrue();
        expect(radio('MATCH').checked).toBeFalse();
    });

    it('reports a pointer or Space activation as deliberate and an arrow key as not', () => {
        create();
        radio('CLOZE').click();
        expect(chosen).toEqual([{ mechanic: 'CLOZE', deliberate: true }]);
        radio('CHOICE').dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
        radio('CHOICE').click();
        expect(chosen.at(-1)).toEqual({ mechanic: 'CHOICE', deliberate: false });
        radio('MATCH').dispatchEvent(new KeyboardEvent('keydown', { key: ' ', bubbles: true }));
        radio('MATCH').click();
        expect(chosen.at(-1)).toEqual({ mechanic: 'MATCH', deliberate: true });
    });

    it('treats a second activation of the shown tile as a deliberate revisit but ignores it after an arrow key', () => {
        create('SELF_CHECK');
        radio('SELF_CHECK').click();
        expect(chosen).toEqual([{ mechanic: 'SELF_CHECK', deliberate: true }]);
        radio('SELF_CHECK').dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowUp', bubbles: true }));
        radio('SELF_CHECK').click();
        expect(chosen.length).toBe(1);
    });
});
