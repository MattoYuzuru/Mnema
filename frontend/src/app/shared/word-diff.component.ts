import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import type { DiffParagraph, DiffSegment } from '../content/word-diff';
import { UPDATE_TEXT } from './copy-text';

/**
 * The words of a text that changed between two versions, drawn from {@link diffLines} paragraphs. A removed word is `<del>` (struck
 * through) and an inserted one `<ins>` (underlined), each also announced as «удалено:» or «добавлено:» by a visually hidden prefix, so
 * a change never depends on colour or on the line style alone; in forced-colors mode the line styles stay and the inserted words take
 * the link colour. Not verified: how real screen readers voice `<del>` and `<ins>` together with the hidden prefixes (some may
 * announce the change twice, once from the element and once from the prefix); that needs a check with real assistive technology. `side` picks the column of a comparison: `mine` keeps what the reader has now (the author's insertions are left
 * out, the words the author took away are struck through), `author` keeps the author's version (the removed words are left out, the
 * new ones are underlined), `both` shows the whole diff in one flow.
 */
@Component({
    selector: 'app-word-diff',
    template: `
      @for (paragraph of view(); track $index) {
        <p class="diff-paragraph">
          @for (segment of paragraph; track $index) {
            @if (segment.kind === 'del') {
              <del><span class="visually-hidden">{{ text.removedWord }}</span>{{ segment.text }}</del>
            } @else if (segment.kind === 'ins') {
              <ins><span class="visually-hidden">{{ text.insertedWord }}</span>{{ segment.text }}</ins>
            } @else {
              <ng-container>{{ segment.text }}</ng-container>
            }
          }
        </p>
      }
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; }
      .diff-paragraph { margin-block: 0 .85em; white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.6; }
      .diff-paragraph:last-child { margin-block-end: 0; }
      del { color: var(--mn-danger); text-decoration: line-through; text-decoration-thickness: .08em; }
      ins { background: var(--mn-soft); color: var(--mn-ink); text-decoration: underline; text-decoration-thickness: .1em; text-underline-offset: .18em; }
      @media (forced-colors: active) {
        del { text-decoration-thickness: .15em; }
        ins { forced-color-adjust: none; color: LinkText; background: transparent; text-decoration-thickness: .15em; }
      }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class WordDiffComponent {
    readonly paragraphs = input.required<readonly DiffParagraph[]>();
    readonly side = input<'mine' | 'author' | 'both'>('both');

    protected readonly text = UPDATE_TEXT;
    /** The runs of the chosen side, paragraph by paragraph; a paragraph left with nothing is dropped. */
    protected readonly view = computed<readonly (readonly DiffSegment[])[]>(() => {
        const side = this.side();
        return this.paragraphs()
            .map(paragraph => paragraph.segments.filter(segment => segment.kind === 'same' || (segment.kind === 'del' ? side !== 'author' : side !== 'mine')))
            .filter(segments => segments.length > 0);
    });
}
