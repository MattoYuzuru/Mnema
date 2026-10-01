import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { NativeYoutubeComponent } from '../rendering/native-youtube.component';
import { LearnerBlock } from './exercise-content.models';
import { LearnerMediaComponent } from './learner-media.component';

interface BlockView {
    readonly block: LearnerBlock;
    /** Accessible name of a media block; learner AUDIO/VIDEO blocks carry no author title. */
    readonly name: string;
}

const KIND_NAMES = { AUDIO: 'Аудио', VIDEO: 'Видео', IMAGE: 'Изображение' } as const;

/**
 * Neutral accessible name for media without a learner-facing title: «Аудио, вариант 2» or «Видео в вопросе».
 * `suffix` is appended verbatim, so callers choose the grammar (" в вопросе", ", вариант 2").
 */
export function mediaName(kind: keyof typeof KIND_NAMES, ordinal: number, suffix: string): string {
    return `${KIND_NAMES[kind]}${ordinal > 1 ? ` ${ordinal}` : ''}${suffix}`;
}

/** The single renderer of learner blocks, shared by Study and the author preview. */
@Component({
    selector: 'app-learner-blocks',
    imports: [LearnerMediaComponent, NativeYoutubeComponent],
    template: `
      @for (view of views(); track $index) {
        @switch (view.block.kind) {
          @case ('TEXT') { <p class="learner-text">{{ view.block.text }}</p> }
          @case ('IMAGE') { <app-learner-media kind="image" [assetId]="view.block.assetId" [name]="view.name" /> }
          @case ('AUDIO') {
            <app-learner-media kind="audio" [assetId]="view.block.assetId" [name]="view.name" />
            @if (view.block.transcript !== undefined) {
              <p class="learner-transcript"><strong>Транскрипт:</strong> {{ view.block.transcript }}</p>
            }
          }
          @case ('VIDEO') {
            <app-learner-media kind="video" [assetId]="view.block.assetId" [name]="view.name" />
            @if (view.block.transcript !== undefined) {
              <p class="learner-transcript"><strong>Транскрипт:</strong> {{ view.block.transcript }}</p>
            }
          }
          @case ('YOUTUBE') { <app-native-youtube [videoId]="view.block.videoId" [title]="view.block.title" /> }
        }
      }
    `,
    styles: [`
      :host { display: grid; gap: .75rem; min-inline-size: 0; max-inline-size: 100%; }
      .learner-text { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.6; }
      .learner-transcript { margin: 0; color: var(--mn-muted); overflow-wrap: anywhere; white-space: pre-wrap; }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class LearnerBlocksComponent {
    readonly blocks = input.required<readonly LearnerBlock[]>();
    /** Appended to generated media names, e.g. " в вопросе" or ", вариант 2". */
    readonly nameSuffix = input('');
    /** Render only media and embeds. Used where the text sits inside a selection label. */
    readonly mediaOnly = input(false);

    readonly views = computed<readonly BlockView[]>(() => {
        const seen = { AUDIO: 0, VIDEO: 0 };
        return this.blocks().filter(block => !this.mediaOnly() || block.kind !== 'TEXT').map(block => {
            if (block.kind === 'AUDIO' || block.kind === 'VIDEO') {
                seen[block.kind] += 1;
                return { block, name: mediaName(block.kind, seen[block.kind], this.nameSuffix()) };
            }
            return { block, name: block.kind === 'IMAGE' ? block.alt : '' };
        });
    });
}
