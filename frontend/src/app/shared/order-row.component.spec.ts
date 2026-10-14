import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { OrderRowComponent } from './order-row.component';

@Component({
    imports: [OrderRowComponent],
    template: `<app-order-row [position]="position()" [total]="total()" label="Столицы мира" [error]="error()" [busy]="busy()"
      (moveUp)="log.push('up')" (moveDown)="log.push('down')" (remove)="log.push('remove')"><span>Столицы мира</span></app-order-row>`
})
class Host {
    readonly position = signal(2);
    readonly total = signal(3);
    readonly error = signal<string | null>(null);
    readonly busy = signal(false);
    readonly log: string[] = [];
}

describe('OrderRowComponent', () => {
    let fixture: ComponentFixture<Host>;
    let root: HTMLElement;
    const button = (name: string): HTMLButtonElement => root.querySelector<HTMLButtonElement>(`[data-action="${name}"]`)!;

    beforeEach(() => {
        fixture = TestBed.createComponent(Host);
        root = fixture.nativeElement as HTMLElement;
        fixture.detectChanges();
    });

    it('shows the position, the projected body and three named buttons that start with their visible word', () => {
        expect(root.querySelector('.order-position')!.textContent).toBe('2');
        expect(root.querySelector('.order-position')!.getAttribute('aria-hidden')).toBe('true');
        expect(root.querySelector('.order-body')!.textContent).toContain('Столицы мира');
        for (const [name, word] of [['up', 'Выше'], ['down', 'Ниже'], ['remove', 'Убрать']] as const) {
            expect(button(name).textContent!.trim()).toBe(word);
            expect(button(name).getAttribute('aria-label')).toBe(`${word}: Столицы мира`);
            expect(button(name).type).toBe('button');
        }
    });

    it('emits what was pressed', () => {
        button('up').click(); button('down').click(); button('remove').click();
        expect(fixture.componentInstance.log).toEqual(['up', 'down', 'remove']);
    });

    it('keeps the end buttons focusable and inert: aria-disabled, never disabled, and no output', () => {
        fixture.componentInstance.position.set(1);
        fixture.detectChanges();
        expect(button('up').getAttribute('aria-disabled')).toBe('true');
        expect(button('up').disabled).toBe(false);
        button('up').click();
        fixture.componentInstance.position.set(3);
        fixture.detectChanges();
        expect(button('down').getAttribute('aria-disabled')).toBe('true');
        button('down').click();
        expect(button('up').getAttribute('aria-disabled')).toBeNull();
        expect(fixture.componentInstance.log).toEqual([]);
    });

    it('makes every button inert while busy', () => {
        fixture.componentInstance.busy.set(true);
        fixture.detectChanges();
        for (const name of ['up', 'down', 'remove']) {
            expect(button(name).getAttribute('aria-disabled')).toBe('true');
            button(name).click();
        }
        expect(fixture.componentInstance.log).toEqual([]);
    });

    it('describes the buttons by the row error', () => {
        expect(root.querySelector('.field-error')).toBeNull();
        expect(button('remove').getAttribute('aria-describedby')).toBeNull();
        fixture.componentInstance.error.set('Колоду нельзя поставить на полку.');
        fixture.detectChanges();
        const error = root.querySelector('.field-error')!;
        expect(error.textContent).toBe('Колоду нельзя поставить на полку.');
        for (const name of ['up', 'down', 'remove']) expect(button(name).getAttribute('aria-describedby')).toBe(error.id);
    });
});
