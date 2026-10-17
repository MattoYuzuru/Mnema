import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { PaperDialogComponent } from './paper-dialog.component';

@Component({
    imports: [PaperDialogComponent],
    template: `<button type="button" id="opener" (click)="dialog.open()">Поделиться</button>
      <app-paper-dialog #dialog title="Поделиться" subtitle="История искусств" (dismissed)="dismissals = dismissals + 1">
        <label>Что нового <input id="draft" /></label>
      </app-paper-dialog>`
})
class HostComponent { dismissals = 0; }

describe('PaperDialogComponent', () => {
    let fixture: ComponentFixture<HostComponent>;
    let root: HTMLElement;
    let dialog: HTMLDialogElement;
    let opener: HTMLButtonElement;

    beforeEach(async () => {
        fixture = TestBed.createComponent(HostComponent);
        root = fixture.nativeElement as HTMLElement;
        document.body.append(root);
        fixture.detectChanges();
        await fixture.whenStable();
        dialog = root.querySelector('dialog')!;
        opener = root.querySelector('#opener')!;
    });
    afterEach(() => root.remove());
    const open = () => { opener.focus(); opener.click(); fixture.detectChanges(); };

    it('opens modally with an accessible title, subtitle and focus on the heading', () => {
        const nativeOpen = vi.spyOn(dialog, 'showModal');
        expect(dialog.open).toBe(false);
        open();
        expect(nativeOpen).toHaveBeenCalledOnce();
        expect(dialog.open).toBe(true);
        expect(document.getElementById(dialog.getAttribute('aria-labelledby')!)?.textContent).toBe('Поделиться');
        expect(root.querySelector('.paper-dialog-subtitle')?.textContent).toBe('История искусств');
        expect(document.activeElement).toBe(dialog.querySelector('h2'));
        expect(dialog.hasAttribute('tabindex')).toBe(false);
    });

    it('closes by Escape and restores the opener while keeping the draft mounted', () => {
        open();
        const field = root.querySelector<HTMLInputElement>('#draft')!;
        field.value = 'Мой черновик';
        const cancel = new Event('cancel', { cancelable: true });
        dialog.dispatchEvent(cancel);
        expect(cancel.defaultPrevented).toBe(true);
        expect(dialog.open).toBe(false);
        expect(document.activeElement).toBe(opener);
        expect(fixture.componentInstance.dismissals).toBe(1);
        open();
        expect(root.querySelector('#draft')).toBe(field);
        expect(field.value).toBe('Мой черновик');
    });

    it('provides a named close button and handles a native close', () => {
        open();
        const close = root.querySelector<HTMLButtonElement>('.paper-dialog-close')!;
        expect(close.getAttribute('aria-label')).toBe('Закрыть: Поделиться');
        close.click();
        expect(dialog.open).toBe(false);
        expect(document.activeElement).toBe(opener);
        open();
        dialog.close();
        expect(document.activeElement).toBe(opener);
        expect(fixture.componentInstance.dismissals).toBe(2);
    });

    it('does not open a second modal or replace the original focus target when already open', () => {
        const nativeOpen = vi.spyOn(dialog, 'showModal');
        open();
        opener.click();
        expect(nativeOpen).toHaveBeenCalledOnce();
        dialog.close();
        expect(document.activeElement).toBe(opener);
    });

    it('releases the connected native dialog on host destruction', () => {
        open();
        fixture.destroy();
        expect(dialog.open).toBe(false);
        expect(document.activeElement).toBe(opener);
    });
});
