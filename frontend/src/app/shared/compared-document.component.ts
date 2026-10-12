import { ChangeDetectionStrategy, Component, TemplateRef, computed, inject, input, viewChild } from '@angular/core';

import type { NativeDocument } from '../content/native-document';
import { AssetPlaybackSource, NativeDocumentRendererComponent } from '../content/rendering/native-document-renderer.component';
import { NativeMediaSurfaceComponent } from '../content/rendering/native-media-surface.component';
import type { BlockOverlay, BlockSlotContext } from '../content/rendering/native-top-block.directive';
import { CompareColumnsComponent } from './compare-columns.component';
import { UPDATE_TEXT } from './copy-text';
import { BlockChange, CompareSide, NO_CHANGES, compareDocuments } from './document-changes';
import { WordDiffComponent } from './word-diff.component';

const NONE: ReadonlySet<string> = new Set();

/**
 * One side of a comparison of two versions of a material: the document drawn by the same renderer as Browse, with what differs from
 * the other side marked. A block is found on the other side by its stable node id. A changed block is marked through the renderer's
 * overlay (`BlockOverlay.marked`, its selection tint) and always carries a small stamp above it, «Изменено», «Добавлено» or «Удалено
 * автором», so the change is a word and not only a colour. A paragraph whose words changed is drawn by {@link WordDiffComponent}
 * instead of the plain block (as the Workshop does for a rewrite): on the reader's side the words the author took away are struck
 * through, on the author's side the new ones are underlined, both with hidden «удалено:» and «добавлено:» prefixes. Without a
 * `counterpart` (the unit does not exist on the other side) the document is drawn plainly. Node ids appear in the DOM (the overlay
 * needs them); media files arrive through `assetSources` like in the renderer, or through the media surface when `mediaAudience` is set.
 */
@Component({
    selector: 'app-compared-document',
    imports: [NativeDocumentRendererComponent, NativeMediaSurfaceComponent, WordDiffComponent],
    template: `
      @if (mediaAudience(); as audience) {
        <app-native-media-surface [document]="document()" [audience]="audience" [exposeNodeIds]="true" [overlay]="overlay()" [headingOffset]="offset()" />
      } @else {
        <app-native-document-renderer [document]="document()" [assetSources]="assetSources()" [assetStatuses]="assetStatuses()"
          [exposeNodeIds]="true" [overlay]="overlay()" [headingOffset]="offset()" />
      }
      <ng-template #slot let-id let-placement="placement">
        @if (placement === 'before') {
          <div class="change" [attr.data-change]="changes().marks.get(id)">
            <p class="change-label"><span class="stamp">{{ label(changes().marks.get(id)) }}</span></p>
            @if (changes().diffs.get(id); as paragraphs) {
              <div class="changed-text"><app-word-diff [paragraphs]="paragraphs" [side]="side()" /></div>
            }
          </div>
        }
      </ng-template>
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; }
      .change { margin-block: 0 .6rem; }
      .change-label { margin: 0 0 .3rem; }
      .changed-text { border-inline-start: 3px solid var(--mn-ink); padding-inline-start: .75rem; }
      @media (forced-colors: active) { .changed-text { border-inline-start-color: CanvasText; } }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class ComparedDocumentComponent {
    /** The version drawn in this column. */
    readonly document = input.required<NativeDocument>();
    /** The version in the other column; `null` when the unit is missing there, and nothing is marked. */
    readonly counterpart = input<NativeDocument | null>(null);
    readonly side = input.required<CompareSide>();
    readonly assetSources = input<Readonly<Record<string, string | AssetPlaybackSource>>>({});
    readonly assetStatuses = input<Readonly<Record<string, string | undefined>>>({});
    /**
     * Where the media of this side comes from. `null` (the default) draws the files named in `assetSources`, as the styleguide does;
     * `owner` or `public` hands the document to the renderer's media surface, which asks the `MediaPlaybackApi` provided above this
     * element for each file (the owner's own playback route for the reader's copy; a provider for the public media route of the source
     * for the author's side) and tells the reader, in words, about a file it cannot get. `audience` only chooses the wording of that.
     */
    readonly mediaAudience = input<'owner' | 'public' | null>(null);
    /**
     * Levels the document's headings sit below the renderer's default (its level 1 is `h2` at 0). Left unset, it follows the enclosing
     * `app-compare-columns`: the columns' heading is at level H, so the document's level 1 is drawn at H + 1 (offset H - 1). Outside
     * columns the default is 0. An explicit number wins.
     */
    readonly headingOffset = input<number | null>(null);

    private readonly columns = inject(CompareColumnsComponent, { optional: true });
    protected readonly offset = computed(() => this.headingOffset() ?? (this.columns === null ? 0 : this.columns.headingLevel() - 1));

    private readonly slot = viewChild.required<TemplateRef<BlockSlotContext>>('slot');
    protected readonly changes = computed(() => {
        const counterpart = this.counterpart();
        return counterpart === null ? NO_CHANGES : compareDocuments(this.document(), counterpart, this.side());
    });
    protected readonly overlay = computed<BlockOverlay | null>(() => {
        const { marks, diffs } = this.changes();
        if (marks.size === 0) return null;
        const ids = new Set(marks.keys());
        return { busy: NONE, marked: ids, hidden: new Set(diffs.keys()), before: ids, after: NONE, template: this.slot() };
    });

    protected label(change: BlockChange | undefined): string {
        return change === 'added' ? UPDATE_TEXT.added : change === 'removed' ? UPDATE_TEXT.removed : UPDATE_TEXT.changed;
    }
}
