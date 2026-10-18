import { DOCUMENT } from '@angular/common';
import { ChangeDetectionStrategy, Component, ElementRef, afterRenderEffect, computed, effect, inject, untracked, viewChild } from '@angular/core';

import { onboardingText } from '../../core/onboarding/onboarding-texts';
import { TourEngine } from '../../core/onboarding/tour-engine.service';
import { QuestChooserComponent } from '../../shared/quest-chooser.component';
import { QuestLauncher } from './quest-launcher.service';

/**
 * Draws the chooser of a side quest in a native modal `<dialog>` (the page behind is inert, Tab stays inside, Escape asks for «Не сейчас»).
 * The heading takes the focus when it opens; when it closes without a quest starting, the focus goes back to what opened it (the chevron).
 * The state is `QuestLauncher`'s.
 */
@Component({
    selector: 'app-quest-host',
    imports: [QuestChooserComponent],
    // The chooser is the quest's own surface, not the tour's: no `data-tour-surface`.
    template: `
      @if (launcher.request(); as request) {
        <dialog #surface class="quest-dialog" [attr.aria-label]="title()" (cancel)="onCancel($event)">
          <app-quest-chooser [title]="title()" [phase]="launcher.phase()" [decks]="launcher.decks()" [selected]="launcher.selected()" [notice]="launcher.notice()"
            (chosen)="launcher.choose($event)" (started)="launcher.start()" (cancelled)="launcher.close()" (acted)="launcher.act($event)" (retried)="launcher.retry()" />
        </dialog>
      }
    `,
    styles: [`
      :host { display: contents; }
      .quest-dialog { box-sizing: border-box; inline-size: min(28rem, calc(100vw - 2 * var(--mn-page-gutter))); max-block-size: calc(100dvh - 2rem); overflow: auto; margin: auto;
        border: 1px solid var(--mn-rule); border-block-start: 3px solid var(--mn-ink); padding: 1.1rem 1.2rem 1rem; color: var(--mn-body); background: var(--mn-sheet); }
      .quest-dialog::backdrop { background: var(--mn-scrim); }
      @media (forced-colors: active) { .quest-dialog { border: 2px solid CanvasText; } }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class QuestHostComponent {
    protected readonly launcher = inject(QuestLauncher);
    private readonly engine = inject(TourEngine);
    private readonly document = inject(DOCUMENT);
    private readonly surface = viewChild<ElementRef<HTMLDialogElement>>('surface');
    private readonly chooser = viewChild(QuestChooserComponent);
    protected readonly title = computed(() => {
        const milestone = this.launcher.milestone();
        return milestone === null ? '' : onboardingText(`m.${milestone}`);
    });
    private opener: HTMLElement | null = null;

    constructor() {
        // The dialog opens modal when the chooser appears and closes with it; the opener is where the focus goes back to.
        afterRenderEffect(() => {
            const dialog = this.surface()?.nativeElement;
            if (dialog === undefined || dialog.open) return;
            untracked(() => {
                const active = this.document.activeElement;
                this.opener = active instanceof HTMLElement && active !== this.document.body ? active : null;
                dialog.showModal();
            });
        });
        // A new phase puts the focus on the heading again: a screen reader reads the new state under the same name.
        afterRenderEffect(() => {
            this.launcher.phase();
            this.chooser()?.focusHeading();
        });
        let was = false;
        effect(() => {
            const open = this.launcher.request() !== null;
            untracked(() => {
                if (was && !open) {
                    // No quest started: the focus goes back to the chevron. A quest that started has its own card taking the focus.
                    if (!this.engine.active() && this.opener !== null && this.opener.isConnected) this.opener.focus();
                    this.opener = null;
                }
                was = open;
            });
        });
    }

    protected onCancel(event: Event): void {
        // The browser would close the dialog by itself; the launcher decides.
        event.preventDefault();
        this.launcher.close();
    }
}
