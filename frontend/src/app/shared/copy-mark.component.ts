import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import type { DeckFork } from '../features/own-decks/fork/fork.models';
import { COPY_TEXT } from './copy-text';
import { GlyphComponent } from './glyph.component';

/**
 * The mark of a deck that is a copy of somebody else's: the `copy-branch` line glyph, the word «Копия» and, only when there is
 * something the owner should know, one state in words after it: «Готовим колоду…» while the copy is being filled, «Не удалось
 * подготовить колоду» when it failed, «Источник больше недоступен» once the author closed the source (not after the owner detached
 * the copy). The glyph only repeats the word and is hidden from assistive technology. Used by «Мои колоды»; the hub says the same
 * things in a block of its own (`app-copy-status`, `app-copy-origin`).
 */
@Component({
    selector: 'app-copy-mark',
    imports: [GlyphComponent],
    template: `
      <app-glyph name="copy-branch" />
      <span class="label">{{ text.mark }}</span>
      @if (state(); as words) { <span class="state"><span class="sep" aria-hidden="true">·</span><span class="visually-hidden">: </span>{{ words }}</span> }
    `,
    styles: [`
      :host { display: inline-flex; flex-wrap: wrap; align-items: center; gap: .1rem .4rem; min-inline-size: 0; color: var(--mn-ink); font: 700 .75rem/1.4 var(--mn-font-mono); letter-spacing: .08em; text-transform: uppercase; }
      app-glyph { --mn-glyph-size: 1rem; }
      .label, .state { min-inline-size: 0; overflow-wrap: anywhere; }
      .state { display: inline-flex; gap: .4rem; font-weight: 600; }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class CopyMarkComponent {
    readonly fork = input.required<DeckFork>();

    protected readonly text = COPY_TEXT;
    protected readonly state = computed<string | null>(() => {
        const fork = this.fork();
        if (fork.failed) return COPY_TEXT.failed;
        if (fork.preparing) return COPY_TEXT.preparing;
        return !fork.sourceAvailable && !fork.detached ? COPY_TEXT.sourceClosedMark : null;
    });
}
