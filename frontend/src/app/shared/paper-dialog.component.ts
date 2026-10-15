import { DOCUMENT } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, inject, input, output, viewChild } from '@angular/core';

let nextDialog = 0;

/** Нативное модальное окно: содержимое остаётся смонтированным после закрытия, сохраняя незавершённые команды и ввод. */
@Component({
    selector: 'app-paper-dialog',
    template: `
      <dialog #surface class="paper-dialog" [attr.aria-labelledby]="titleId" (cancel)="cancel($event)" (close)="closed()">
        <div class="paper-dialog-body">
          <header class="paper-dialog-heading">
            <div>
              <h2 #heading [id]="titleId" tabindex="-1">{{ title() }}</h2>
              @if (subtitle()) { <p class="paper-dialog-subtitle">{{ subtitle() }}</p> }
            </div>
            <button type="button" class="button quiet paper-dialog-close" [attr.aria-label]="'Закрыть: ' + title()" (click)="close()"><span aria-hidden="true">×</span></button>
          </header>
          <ng-content />
          <footer class="paper-dialog-footer"><button type="button" class="button primary" (click)="close()">Готово</button></footer>
        </div>
      </dialog>
    `,
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class PaperDialogComponent {
    readonly title = input.required<string>();
    readonly subtitle = input<string | null>(null);
    readonly dismissed = output<void>();
    private readonly document = inject(DOCUMENT);
    private readonly surface = viewChild.required<ElementRef<HTMLDialogElement>>('surface');
    private readonly heading = viewChild.required<ElementRef<HTMLElement>>('heading');
    protected readonly titleId = `mn-paper-dialog-${nextDialog++}-title`;
    private opener: HTMLElement | null = null;

    constructor() {
        inject(DestroyRef).onDestroy(() => this.close());
    }

    open(returnFocus?: HTMLElement): void {
        const dialog = this.surface().nativeElement;
        if (dialog.open) return;
        const active = returnFocus ?? this.document.activeElement;
        this.opener = active instanceof HTMLElement && active !== this.document.body ? active : null;
        dialog.showModal();
        this.heading().nativeElement.focus();
    }

    close(): void {
        const dialog = this.surface().nativeElement;
        if (dialog.open) { dialog.close(); this.restoreFocus(); }
    }

    protected cancel(event: Event): void {
        event.preventDefault();
        this.close();
    }

    protected closed(): void {
        this.restoreFocus();
        this.dismissed.emit();
    }

    private restoreFocus(): void {
        const opener = this.opener;
        this.opener = null;
        if (opener?.isConnected) opener.focus();
    }
}
