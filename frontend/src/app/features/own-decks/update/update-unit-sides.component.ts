import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import type { NativeDocument } from '../../../content/native-document';
import { CompareColumnsComponent } from '../../../shared/compare-columns.component';
import { ComparedDocumentComponent } from '../../../shared/compared-document.component';
import { UnavailableNoteComponent } from '../../../shared/unavailable-note.component';
import { AuthorMediaScopeComponent } from './author-media-scope.component';
import { SideLine, sideLines } from './exercise-projection';
import { UnitSide, UnitSides } from './update-plan.models';

type SideView =
    | { readonly kind: 'document'; readonly document: NativeDocument; readonly counterpart: NativeDocument | null }
    | { readonly kind: 'unavailable'; readonly subject: 'material' | 'exercise' }
    | { readonly kind: 'lines'; readonly lines: readonly SideLine[] };

function viewOf(side: UnitSide | null, other: UnitSide | null): SideView | null {
    if (side === null) return null;
    if (side.kind === 'MATERIAL') {
        if (side.document === null) return { kind: 'unavailable', subject: 'material' };
        return { kind: 'document', document: side.document, counterpart: other?.kind === 'MATERIAL' ? other.document : null };
    }
    if (side.kind === 'EXERCISE' && side.unavailable) return { kind: 'unavailable', subject: 'exercise' };
    return { kind: 'lines', lines: sideLines(side) };
}

/**
 * The two sides of one unit of the plan inside an open row: `app-compare-columns` with the shared document renderer for a material
 * (the same renderer as Browse; the blocks are matched by their stable node ids) and a plain read-only projection for everything else
 * (an exercise's question, options and answers; a goal; the deck's title and description; the moved materials with their old and new
 * places). Media: the reader's side reads its files through the owner's playback route (the copy's own files), the author's side through
 * the public media route of the source by `upstreamCode` (see `AuthorMediaScopeComponent`); a file neither route can give keeps the
 * renderer's «недоступно» state. A side that does not exist is the columns' «Нет у вас» / «Автор удалил»; a side the moderation took down is
 * `app-unavailable-note`. The column headings sit at level 4, one below the row's heading.
 */
@Component({
    selector: 'app-update-unit-sides',
    imports: [NgTemplateOutlet, AuthorMediaScopeComponent, CompareColumnsComponent, ComparedDocumentComponent, UnavailableNoteComponent],
    template: `
      <app-compare-columns [mineAbsent]="sides().mine === null" [authorAbsent]="sides().author === null" [headingLevel]="4">
        <div compare-mine>
          @if (mine(); as view) {
            @switch (view.kind) {
              @case ('document') { <app-compared-document [document]="view.document" [counterpart]="view.counterpart" side="mine" mediaAudience="owner" /> }
              @case ('unavailable') { <app-unavailable-note [subject]="view.subject" audience="owner" /> }
              @case ('lines') { <ng-container [ngTemplateOutlet]="lines" [ngTemplateOutletContext]="{ $implicit: view.lines }" /> }
            }
          }
        </div>
        <div compare-author>
          @if (author(); as view) {
            @switch (view.kind) {
              @case ('document') {
                @if (upstreamCode(); as code) {
                  <app-author-media-scope [code]="code">
                    <app-compared-document [document]="view.document" [counterpart]="view.counterpart" side="author" mediaAudience="public" />
                  </app-author-media-scope>
                } @else {
                  <app-compared-document [document]="view.document" [counterpart]="view.counterpart" side="author" />
                }
              }
              @case ('unavailable') { <app-unavailable-note [subject]="view.subject" audience="owner" /> }
              @case ('lines') { <ng-container [ngTemplateOutlet]="lines" [ngTemplateOutletContext]="{ $implicit: view.lines }" /> }
            }
          }
        </div>
      </app-compare-columns>
      <ng-template #lines let-rows>
        <dl class="lines">
          @for (row of rows; track $index) {
            <div class="line">
              <dt class="eyebrow">{{ row.label }}</dt>
              @for (value of row.values; track $index) { <dd>{{ value }}</dd> }
            </div>
          }
        </dl>
      </ng-template>
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; }
      .lines { display: grid; gap: .85rem; margin: 0; }
      .line { display: grid; gap: .2rem; min-inline-size: 0; }
      .line dt { margin: 0; }
      .line dd { margin: 0; max-inline-size: 62ch; line-height: 1.5; white-space: pre-line; overflow-wrap: anywhere; }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class UpdateUnitSidesComponent {
    readonly sides = input.required<UnitSides>();
    /** The public code of the source (`fork.upstreamCode`); `null` when the copy has none, and the author's files then show as unavailable. */
    readonly upstreamCode = input<string | null>(null);

    protected readonly mine = computed(() => viewOf(this.sides().mine, this.sides().author));
    protected readonly author = computed(() => viewOf(this.sides().author, this.sides().mine));
}
