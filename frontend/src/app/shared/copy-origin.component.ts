import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';

import type { DeckFork } from '../features/own-decks/fork/fork.models';
import { COPY_TEXT } from './copy-text';
import { GlyphComponent } from './glyph.component';

/**
 * Where a copy came from, under the title of the hub: «Копия колоды «Название»» with the title as a link to the source while the
 * author's deck is still readable for the owner, otherwise the approved sentence «Автор закрыл исходную колоду. Ваша копия остаётся
 * с вами, но обновлений больше не будет.» Once the owner detached the copy a stamp says «Отвязана от автора». The link is the
 * public view `/d/{code}` (it replaces the address with its canonical one); the glyph repeats the words and is hidden.
 */
@Component({
    selector: 'app-copy-origin',
    imports: [GlyphComponent, RouterLink],
    template: `
      <app-glyph name="copy-branch" />
      <span class="line">
        @if (link(); as source) {
          {{ text.originBefore }}<a [routerLink]="['/d', source.code]">{{ source.title }}</a>{{ text.originAfter }}
        } @else {
          {{ text.sourceClosed }}
        }
      </span>
      @if (fork().detached) { <span class="stamp">{{ text.detached }}</span> }
    `,
    styles: [`
      :host { display: grid; grid-template-columns: auto minmax(0, 1fr); align-items: start; gap: .35rem .6rem; min-inline-size: 0; margin-block: .75rem 0; color: var(--mn-muted); font-size: .95rem; line-height: 1.5; }
      app-glyph { --mn-glyph-size: 1.1rem; margin-block-start: .2rem; color: var(--mn-ink); }
      .line { min-inline-size: 0; max-inline-size: 60ch; overflow-wrap: anywhere; }
      .stamp { grid-column: 2; justify-self: start; }
      a { color: var(--mn-ink); text-underline-offset: .2em; }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class CopyOriginComponent {
    readonly fork = input.required<DeckFork>();

    protected readonly text = COPY_TEXT;
    protected readonly link = computed(() => {
        const fork = this.fork();
        return fork.sourceAvailable && fork.upstreamCode !== null && fork.upstreamTitle !== null
            ? { code: fork.upstreamCode, title: fork.upstreamTitle } : null;
    });
}
