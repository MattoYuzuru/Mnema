import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/**
 * The body of a list row whose material or exercise was taken down after a complaint (Share/15). The row keeps its place and number; this
 * says in words that it cannot be opened and why, and projects the actions that exist for the reader (the owner may replace or delete it
 * without reading it; a reader of a public deck has none). Text only: no colour, no icon carries the meaning alone.
 */
@Component({
    selector: 'app-unavailable-note',
    template: `
      <p class="title">{{ title() }}</p>
      <p class="hint">{{ explanation() }}</p>
      <div class="actions"><ng-content /></div>
    `,
    styles: [`
      :host { display: grid; gap: .25rem; min-inline-size: 0; }
      .title { margin: 0; color: var(--mn-muted); font-weight: 650; overflow-wrap: anywhere; }
      .hint { margin: 0; }
      .actions { display: flex; flex-wrap: wrap; align-items: center; gap: .5rem; }
      .actions:empty { display: none; }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class UnavailableNoteComponent {
    readonly subject = input<'material' | 'exercise'>('material');
    /** `owner` adds what the owner can do about it; `public` only says that it is gone. */
    readonly audience = input<'owner' | 'public'>('public');

    protected readonly title = computed(() => this.subject() === 'exercise' ? 'Упражнение недоступно' : 'Материал недоступен');
    protected readonly explanation = computed(() => {
        if (this.subject() === 'exercise') {
            return this.audience() === 'owner' ? 'Упражнение снято по жалобе. Создайте новое или удалите это.' : 'Упражнение снято по жалобе.';
        }
        return this.audience() === 'owner'
            ? 'Материал снят по жалобе, открыть его нельзя. Напишите новый на его месте или удалите этот.'
            : 'Материал снят по жалобе.';
    });
}
