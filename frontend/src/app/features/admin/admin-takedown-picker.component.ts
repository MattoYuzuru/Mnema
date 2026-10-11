import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, input, output, signal } from '@angular/core';
import { map, tap } from 'rxjs';
import { AutoLoadComponent } from '../../shared/auto-load.component';
import { SegmentedChoiceComponent, SegmentedOption } from '../../shared/segmented-choice.component';
import { MECHANIC_LABELS, exercisesText, materialsText } from '../own-decks/hub/deck-hub.text';
import { AdminApiService } from './admin-api.service';
import { TakedownUnit, UnitKind, UnitRow } from './admin-complaints.models';
import { CursorList } from './cursor-list';

/** A decision names at most this many units (the API refuses more). */
export const MAX_TAKEDOWN_UNITS = 1000;

/**
 * The unit picker of the legal takedown: the materials and the exercises of the reported deck's PUBLISHED revision (what readers get), in
 * manifest order, each with a checkbox. A unit that is already taken down is marked in words and cannot be chosen again. The chosen revision
 * is the one the list shows, so the decision names exactly what the operator saw. Both lists continue by themselves (no page buttons)
 * and the choice survives switching between them. Native checkboxes in `.check-row` labels: Space toggles, Tab walks the list.
 */
@Component({
    selector: 'app-admin-takedown-picker',
    imports: [AutoLoadComponent, SegmentedChoiceComponent],
    template: `
  <section class="picker" aria-labelledby="picker-title">
    <h3 id="picker-title">Что снять</h3>
    <p class="hint">
      Отметьте материалы и упражнения опубликованной версии, где есть спорное содержимое. Снимаются именно эти версии: они перестанут открываться
      в колоде и в копиях, где их не меняли. Версия, которую владелец копии изменил у себя, остаётся.
    </p>
    <app-segmented-choice legend="Что отмечать" [options]="kindOptions()" [value]="kind()" (valueChange)="setKind($event)" />
    @for (list of lists; track list.kind) {
      <div [hidden]="kind() !== list.kind">
        @if (list.cursor.loading()) {<p role="status">Загружаем {{ list.kind === 'ITEM' ? 'материалы' : 'упражнения' }}…</p>}
        @if (list.cursor.error()) {
          <div class="notice error" role="alert">
            <p>{{ list.cursor.error() }}</p><button class="button" type="button" (click)="list.cursor.reload()">Повторить</button>
          </div>
        }
        <ul #rows class="picker-list" role="list" [attr.aria-label]="list.kind === 'ITEM' ? 'Материалы колоды' : 'Упражнения колоды'">
          @for (unit of list.cursor.items(); track unit.key) {
            <li>
              <label class="check-row">
                <input type="checkbox" [checked]="isChosen(unit)" [disabled]="unit.takenDown" (change)="toggle(unit)" />
                <span>
                  <strong>{{ unit.ordinal + 1 }}.</strong> {{ label(unit) }}
                  @if (unit.takenDown) { <span class="stamp">Уже снято</span> }
                </span>
              </label>
            </li>
          }
        </ul>
        @if (!list.cursor.loading() && !list.cursor.error() && list.cursor.items().length === 0) {
          <p class="empty-state">{{ list.kind === 'ITEM' ? 'В опубликованной версии нет материалов.' : 'В опубликованной версии нет упражнений.' }}</p>
        }
        <app-auto-load [content]="rows" [context]="list.cursor.context()" [continuation]="list.cursor.next()" [enabled]="kind() === list.kind"
          [loading]="list.cursor.loadingMore()" [error]="list.cursor.moreError()" loadingText="Загружаем следующие…" (loadNext)="list.cursor.more()" />
      </div>
    }
    <p class="hint summary" role="status">{{ summary() }}</p>
    @if (limitReached()) { <p class="field-error" role="alert">За одно решение можно снять не больше {{ max }}.</p> }
  </section>`,
    styles: [`
      :host { display: block; min-inline-size: 0; }
      .picker-list { display: grid; margin: var(--mn-space-3, .75rem) 0; padding: 0; list-style: none; border-block-start: 1px solid var(--mn-rule); }
      .picker-list li { border-block-end: 1px solid var(--mn-rule); }
      .check-row span { overflow-wrap: anywhere; }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class AdminTakedownPickerComponent implements OnInit {
    readonly complaintId = input.required<string>();
    /** The units the operator has chosen, with the revision each list showed. */
    readonly unitsChange = output<readonly TakedownUnit[]>();

    private readonly api = inject(AdminApiService);
    private readonly destroy = inject(DestroyRef);
    protected readonly max = MAX_TAKEDOWN_UNITS;
    protected readonly kind = signal<UnitKind>('ITEM');
    private readonly totals = signal<Readonly<Record<UnitKind, number | null>>>({ ITEM: null, EXERCISE: null });
    private readonly chosen = signal<ReadonlyMap<string, TakedownUnit>>(new Map());
    protected readonly limitReached = signal(false);
    protected readonly lists: readonly { readonly kind: UnitKind; readonly cursor: CursorList<UnitRow> }[] = [
        { kind: 'ITEM', cursor: this.list('ITEM') }, { kind: 'EXERCISE', cursor: this.list('EXERCISE') }
    ];

    protected readonly kindOptions = computed<readonly SegmentedOption<UnitKind>[]>(() => [
        { value: 'ITEM', label: `Материалы${this.totalSuffix('ITEM')}`, hint: 'Показаны материалы колоды.' },
        { value: 'EXERCISE', label: `Упражнения${this.totalSuffix('EXERCISE')}`, hint: 'Показаны упражнения колоды.' }
    ]);
    protected readonly summary = computed(() => {
        const units = [...this.chosen().values()];
        const items = units.filter(unit => unit.kind === 'ITEM').length;
        const exercises = units.length - items;
        const parts = [...(items > 0 ? [materialsText(items)] : []), ...(exercises > 0 ? [exercisesText(exercises)] : [])];
        return units.length === 0 ? 'Пока ничего не отмечено.' : `Отмечено: ${parts.join(' и ')}.`;
    });

    ngOnInit(): void {
        for (const list of this.lists) void list.cursor.reload();
    }

    protected setKind(value: UnitKind | null): void { if (value !== null) this.kind.set(value); }

    protected isChosen(unit: UnitRow): boolean { return this.chosen().has(this.keyOf(unit)); }

    protected toggle(unit: UnitRow): void {
        const next = new Map(this.chosen());
        const key = this.keyOf(unit);
        if (next.has(key)) next.delete(key);
        else if (next.size >= MAX_TAKEDOWN_UNITS) { this.limitReached.set(true); this.chosen.set(new Map(next)); return; }
        else next.set(key, { kind: unit.kind, key: unit.key, revisionId: unit.revisionId });
        this.limitReached.set(false);
        this.chosen.set(next);
        this.unitsChange.emit([...next.values()]);
    }

    protected label(unit: UnitRow): string {
        if (unit.kind === 'ITEM') return unit.label.trim() === '' ? 'Материал без названия' : unit.label;
        const mechanic = (MECHANIC_LABELS as Readonly<Record<string, string | undefined>>)[unit.type ?? ''] ?? 'Упражнение';
        return unit.label.trim() === '' ? mechanic : `${mechanic}: ${unit.label}`;
    }

    private totalSuffix(kind: UnitKind): string {
        const total = this.totals()[kind];
        return total === null ? '' : ` · ${total}`;
    }

    private keyOf(unit: Pick<UnitRow, 'kind' | 'key'>): string { return `${unit.kind}:${unit.key}`; }

    private list(kind: UnitKind): CursorList<UnitRow> {
        return new CursorList<UnitRow>({
            fetch: cursor => this.api.complaintUnits(this.complaintId(), kind, cursor).pipe(
                tap(page => this.totals.update(totals => ({ ...totals, [kind]: page.total }))),
                map(page => ({ items: page.units, next: page.next }))),
            key: unit => unit.key,
            destroy: this.destroy,
            firstError: kind === 'ITEM' ? 'Не удалось загрузить материалы колоды.' : 'Не удалось загрузить упражнения колоды.',
            moreError: 'Следующие записи не загрузились.'
        });
    }
}
