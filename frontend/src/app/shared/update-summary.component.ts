import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

import { plural } from '../core/notifications/notification-presenter';
import { UPDATE_TEXT } from './copy-text';

/** How many units of each kind the author's update holds for this reader. */
export interface UpdateCounts {
    /** New materials. */
    readonly materials: number;
    /** New exercises. */
    readonly exercises: number;
    /** Corrections of what the reader did not change. */
    readonly fixes: number;
    readonly conflicts: number;
    /** Units the author deleted. */
    readonly deleted: number;
}

/** The longest «Что нового» the author can write (docs/product/community-decks.md, «Публикация»). */
export const MAX_UPDATE_NOTE = 500;

/**
 * «+12 материалов, +30 упражнений, 5 исправлений, 2 конфликта, 3 удалено автором»: plain words with Russian plural forms, a part with
 * nothing in it left out. No long dash, no «и»: the parts are separated by commas.
 */
export function summaryLine(counts: UpdateCounts): string {
    const parts: string[] = [];
    if (counts.materials > 0) parts.push(`+${counts.materials} ${plural(counts.materials, ['материал', 'материала', 'материалов'])}`);
    if (counts.exercises > 0) parts.push(`+${counts.exercises} ${plural(counts.exercises, ['упражнение', 'упражнения', 'упражнений'])}`);
    if (counts.fixes > 0) parts.push(`${counts.fixes} ${plural(counts.fixes, ['исправление', 'исправления', 'исправлений'])}`);
    if (counts.conflicts > 0) parts.push(`${counts.conflicts} ${plural(counts.conflicts, ['конфликт', 'конфликта', 'конфликтов'])}`);
    if (counts.deleted > 0) parts.push(`${counts.deleted} ${UPDATE_TEXT.classes.deleted.toLowerCase()}`);
    return parts.join(', ');
}

let nextSummary = 0;

/**
 * The top of the update review screen: what the author changed in numbers, the author's «Что нового» when there is one, and the one
 * primary action «Принять всё безопасное» with a line that says what it touches («Берём новое и исправления того, что вы не меняли»)
 * and what it does not («Ваши правки и удаления не трогаем»). With `pendingNote` a second line says that the reader's picks on conflicts and
 * deletions are applied by «Применить выбранное», not by this button. The line describes the button (`aria-describedby`). While the request
 * runs the button is `aria-busy` and `aria-disabled` but keeps the focus; with nothing safe to accept it is `aria-disabled` and the
 * line says so. The block is a plain section with a heading (`headingLevel`, 2 by default), not a live region: the page announces
 * the result of the action once in its own `role="status"`.
 */
@Component({
    selector: 'app-update-summary',
    template: `
      <section class="summary paper-surface" [attr.aria-labelledby]="headingId">
        @switch (headingLevel()) {
          @case (3) { <h3 class="section-heading" [id]="headingId">{{ text.summaryTitle }}</h3> }
          @default { <h2 class="section-heading" [id]="headingId">{{ text.summaryTitle }}</h2> }
        }
        <p class="line">{{ line() || text.nothingChanged }}</p>
        @if (note(); as news) {
          <div class="news">
            <p class="eyebrow">{{ text.whatsNew }}</p>
            <p class="news-text">{{ news }}</p>
          </div>
        }
        <div class="action">
          <button type="button" class="button primary" [attr.aria-describedby]="pendingNote() ? noteId + ' ' + pendingId : noteId" [attr.aria-busy]="busy() ? 'true' : null"
            [attr.aria-disabled]="busy() || !hasSafe() ? 'true' : null" (click)="accept()">{{ busy() ? text.accepting : text.acceptSafe }}</button>
          <p class="hint" [id]="noteId">{{ hasSafe() ? text.acceptSafeNote : text.nothingSafe }}</p>
          @if (pendingNote()) { <p class="hint" [id]="pendingId">{{ text.picksWaiting }}</p> }
        </div>
      </section>
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; }
      .summary { display: grid; gap: .9rem; }
      .line { margin: 0; color: var(--mn-ink); font: 500 clamp(1.15rem, 3vw, 1.4rem)/1.35 var(--mn-font-display, Georgia, serif); overflow-wrap: anywhere; }
      .news { border-inline-start: 3px solid var(--mn-ink); padding-inline-start: .85rem; }
      .news .eyebrow { margin-block-end: .25rem; }
      .news-text { max-inline-size: 62ch; margin: 0; white-space: pre-line; overflow-wrap: anywhere; line-height: 1.55; }
      .action { display: grid; justify-items: start; gap: .5rem; }
      .action .hint { max-inline-size: 62ch; }
      @media (max-width: 30rem) { .action .button { inline-size: 100%; } }
      @media (forced-colors: active) { .news { border-inline-start-color: CanvasText; } }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class UpdateSummaryComponent {
    readonly counts = input.required<UpdateCounts>();
    /** The author's «Что нового», up to {@link MAX_UPDATE_NOTE} characters; `null` or empty when there is none. */
    readonly note = input<string | null>(null);
    /** «Принять всё безопасное» was pressed and the request is on its way. */
    readonly busy = input(false);
    readonly headingLevel = input<2 | 3>(2);
    /**
     * The reader has picked something on a conflict, a unit changed again or a deletion. «Принять всё безопасное» does not send those
     * picks; a line next to it says which button does.
     */
    readonly pendingNote = input(false);
    /** The reader pressed «Принять всё безопасное». */
    readonly acceptSafe = output<void>();

    protected readonly text = UPDATE_TEXT;
    protected readonly headingId = `mn-update-summary-${nextSummary}`;
    protected readonly noteId = `mn-update-summary-${nextSummary}-note`;
    protected readonly pendingId = `mn-update-summary-${nextSummary++}-pending`;
    protected readonly line = computed(() => summaryLine(this.counts()));
    /** Safe is what is new and what corrects a unit the reader did not change. */
    protected readonly hasSafe = computed(() => {
        const { materials, exercises, fixes } = this.counts();
        return materials + exercises + fixes > 0;
    });

    protected accept(): void { if (!this.busy() && this.hasSafe()) this.acceptSafe.emit(); }
}
