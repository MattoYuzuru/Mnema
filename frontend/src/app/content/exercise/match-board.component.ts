import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Injector, afterNextRender, computed, effect, inject, input, output, signal } from '@angular/core';

import { LearnerBlock, LearnerMatchItem } from './exercise-content.models';
import { LearnerBlocksComponent } from './learner-blocks.component';

export interface MatchPair { readonly leftId: string; readonly rightId: string; }

type Side = 'left' | 'right';

interface ItemView {
    readonly item: LearnerMatchItem;
    readonly index: number;
    readonly name: string;
    readonly pairNumber: number | null;
}

/** Text or image description when the item has one, otherwise a generated neutral name. */
export function itemName(blocks: readonly LearnerBlock[], side: Side, index: number): string {
    const text = blocks.find(block => block.kind === 'TEXT');
    if (text?.kind === 'TEXT') return text.text;
    const image = blocks.find(block => block.kind === 'IMAGE');
    if (image?.kind === 'IMAGE') return image.alt;
    const media = blocks.find(block => block.kind === 'AUDIO' || block.kind === 'VIDEO');
    return `${media?.kind === 'VIDEO' ? 'Видео' : 'Аудио'}, ${side === 'left' ? 'слева' : 'справа'} ${index + 1}`;
}

/**
 * Two columns of items, paired by selecting one on the left and then one on the right. Selection
 * buttons and players are separate siblings: pressing play, pause or seek never selects or pairs anything.
 * The parent owns the confirmed pairs and the server pair check; this component only reports intent.
 */
@Component({
    selector: 'app-match-board',
    imports: [LearnerBlocksComponent],
    template: `
      <div class="match-board" role="group" aria-label="Подбор пар" [attr.aria-busy]="busy()">
        @for (column of columns(); track column.side) {
          <section class="match-column" [attr.aria-label]="column.side === 'left' ? 'Слева' : 'Справа'">
            <h3 class="column-label">{{ column.side === 'left' ? 'Слева' : 'Справа' }}</h3>
            <ul class="match-list">
              @for (view of column.items; track view.item.itemId) {
                <li class="match-item" [class.is-selected]="column.side === 'left' && selectedLeft() === view.item.itemId"
                    [class.is-matched]="view.pairNumber !== null" [class.is-wrong]="isWrong(column.side, view.item.itemId)">
                  <div class="match-content">
                    <app-learner-blocks [blocks]="view.item.blocks"
                      [nameSuffix]="', ' + (column.side === 'left' ? 'слева' : 'справа') + ' ' + (view.index + 1)" />
                  </div>
                  <button type="button" class="match-select"
                    [attr.data-answer-control]="column.side === 'left' && view.index === 0 ? '' : null"
                    [attr.data-side]="column.side"
                    [attr.aria-pressed]="column.side === 'left' ? selectedLeft() === view.item.itemId : null"
                    [disabled]="view.pairNumber !== null"
                    [attr.aria-label]="buttonName(view)"
                    (click)="choose(column.side, view.item.itemId)">
                    {{ view.pairNumber !== null ? 'Пара ' + view.pairNumber + ' найдена' : column.side === 'left' && selectedLeft() === view.item.itemId ? 'Выбрано' : 'Выбрать' }}
                  </button>
                </li>
              }
            </ul>
          </section>
        }
      </div>
      <p class="match-status" role="status" aria-live="polite">{{ status() }}</p>
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; container-type: inline-size; }
      .match-board { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: clamp(.75rem, 3vw, 1.5rem); }
      .match-column { min-inline-size: 0; }
      .column-label { margin: 0 0 .5rem; color: var(--mn-muted); font: 600 .85rem/1.4 var(--mn-font-mono, ui-monospace, monospace); letter-spacing: .06em; text-transform: uppercase; }
      .match-list { display: grid; gap: .6rem; margin: 0; padding: 0; list-style: none; }
      .match-item { display: grid; gap: .6rem; min-inline-size: 0; border: 1px solid var(--mn-field-border, var(--mn-rule)); padding: .7rem; background: var(--mn-sheet); }
      .match-item.is-selected { border-color: var(--mn-ink); box-shadow: inset 0 0 0 2px var(--mn-ink); }
      .match-item.is-matched { opacity: .72; border-style: dashed; }
      .match-item.is-wrong { border-color: var(--mn-danger); }
      .match-content { min-inline-size: 0; overflow-wrap: anywhere; }
      .match-select { min-block-size: var(--mn-touch-min, 2.75rem); border: 1px solid var(--mn-ink); border-radius: var(--mn-radius, 2px); padding: .5rem .8rem; color: var(--mn-ink); background: transparent; font: 650 .95rem/1.2 var(--mn-font-body, system-ui, sans-serif); cursor: pointer; }
      .match-select[aria-pressed='true'] { color: var(--mn-on-ink); background: var(--mn-ink); }
      .match-select:disabled { cursor: default; }
      .match-select:focus-visible { outline: 3px solid var(--mn-focus, var(--mn-ink)); outline-offset: 3px; }
      .match-status { margin: .75rem 0 0; color: var(--mn-muted); font-size: .9rem; }
      @container (max-width: 34rem) { .match-board { grid-template-columns: minmax(0, 1fr); } }
      @media (forced-colors: active) { .match-item.is-selected { outline: 2px solid Highlight; } .match-item.is-matched { opacity: 1; } }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class MatchBoardComponent {
    readonly left = input.required<readonly LearnerMatchItem[]>();
    readonly right = input.required<readonly LearnerMatchItem[]>();
    /** Confirmed pairs, left id to right id. */
    readonly matches = input<Readonly<Record<string, string>>>({});
    readonly wrongPair = input<MatchPair | null>(null);
    readonly busy = input(false);
    readonly pairSelected = output<MatchPair>();
    readonly selectedLeft = signal<string | null>(null);
    private readonly hint = signal<string | null>(null);

    readonly columns = computed(() => {
        const numbers = new Map(Object.keys(this.matches()).map((leftId, index) => [leftId, index + 1] as const));
        const byRight = new Map(Object.entries(this.matches()).map(([leftId, rightId]) => [rightId, numbers.get(leftId)!] as const));
        const view = (items: readonly LearnerMatchItem[], side: Side): readonly ItemView[] => items.map((item, index) => ({
            item, index, name: itemName(item.blocks, side, index),
            pairNumber: (side === 'left' ? numbers.get(item.itemId) : byRight.get(item.itemId)) ?? null
        }));
        return [{ side: 'left' as const, items: view(this.left(), 'left') },
            { side: 'right' as const, items: view(this.right(), 'right') }];
    });
    readonly status = computed(() => {
        if (this.busy()) return 'Проверяем пару…';
        if (this.wrongPair() !== null && this.selectedLeft() === this.wrongPair()!.leftId) return 'Эта пара не подходит. Выберите другой вариант справа или другой элемент слева.';
        if (this.hint() !== null) return this.hint()!;
        if (this.selectedLeft() !== null) return 'Элемент слева выбран. Теперь выберите пару справа.';
        return Object.keys(this.matches()).length > 0 ? 'Пара найдена. Выберите следующий элемент слева.'
            : 'Выберите элемент слева, затем подходящий элемент справа.';
    });

    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
    private readonly injector = inject(Injector);
    private readonly destroyRef = inject(DestroyRef);

    constructor() {
        effect(() => {
            const selected = this.selectedLeft();
            if (selected !== null && this.matches()[selected] !== undefined) {
                this.selectedLeft.set(null);
                afterNextRender(() => {
                    if (!this.destroyRef.destroyed) this.host.nativeElement
                        .querySelector<HTMLButtonElement>('button[data-side="left"]:not(:disabled)')?.focus();
                }, { injector: this.injector });
            }
        });
    }

    choose(side: Side, itemId: string): void {
        if (this.busy()) return;
        this.hint.set(null);
        if (side === 'left') {
            this.selectedLeft.set(this.selectedLeft() === itemId ? null : itemId);
            return;
        }
        const leftId = this.selectedLeft();
        if (leftId === null) { this.hint.set('Сначала выберите элемент слева.'); return; }
        if (Object.values(this.matches()).includes(itemId)) return;
        this.pairSelected.emit({ leftId, rightId: itemId });
    }

    isWrong(side: Side, itemId: string): boolean {
        const wrong = this.wrongPair();
        return wrong !== null && (side === 'left' ? wrong.leftId === itemId : wrong.rightId === itemId);
    }

    buttonName(view: ItemView): string {
        return view.pairNumber !== null ? `${view.name}: пара ${view.pairNumber} найдена` : `Выбрать: ${view.name}`;
    }
}
