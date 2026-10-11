import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Injector, afterNextRender, computed, effect, inject, input, output, untracked, viewChild } from '@angular/core';
import { map } from 'rxjs';

import { MECHANIC_LABELS } from '../own-decks/hub/deck-hub.text';
import { AutoLoadComponent } from '../../shared/auto-load.component';
import { Mechanic } from '../../content/exercise/exercise-content.models';
import { PublicDeckApiService } from './public-deck-api.service';
import { PublicDeckFailure, PublicExercise } from './public-deck.models';
import { EXERCISE_FALLBACK_LABEL, RESTART_NOTICE, readFailureText } from './public-deck.text';
import { PublicPager } from './public-pager';

/**
 * The exercises of a public deck as a preview: the type and a plain-text summary of the question. No answer keys, options or
 * references ever arrive here, and nothing can be answered; the learner meets the exercises in their own copy.
 */
@Component({
    selector: 'app-public-exercises-list',
    imports: [AutoLoadComponent],
    template: `
      <section class="list" aria-labelledby="public-exercises-heading">
        <h2 id="public-exercises-heading" #heading>Упражнения <span class="count">· {{ pager.total() }}</span></h2>
        <p class="hint">Показаны только тексты вопросов, без ответов.</p>
        <p class="status" role="status" [class.notice]="restartNotice() !== ''">{{ restartNotice() }}</p>
        @switch (pager.phase()) {
          @case ('loading') { <p class="hint" role="status">Загружаем упражнения…</p> }
          @case ('error') {
            <div class="notice error" role="alert">
              <p>{{ failureText() }}</p>
              <button class="button" type="button" (click)="pager.start()">Повторить</button>
            </div>
          }
          @default {
            @if (pager.rows().length === 0) {
              <section class="empty-state"><h3>В этой колоде пока нет упражнений</h3></section>
            }
            <ol #rows class="rows" role="list" [hidden]="pager.rows().length === 0">
              @for (exercise of pager.rows(); track exercise.exerciseId) {
                <li class="item-row exercise-row">
                  <span class="folio" aria-hidden="true">{{ exercise.ordinal + 1 }}</span>
                  <div class="copy">
                    <p class="kind">
                      <span class="stamp"><span class="visually-hidden">Тип: </span>{{ typeLabel(exercise) }}</span>
                      @if (!exercise.enabled) { <span class="stamp off">Выключено автором</span> }
                    </p>
                    <p class="prompt" [class.empty]="exercise.prompt === null">{{ exercise.prompt ?? 'Вопрос без текстового описания.' }}</p>
                  </div>
                </li>
              }
            </ol>
            <app-auto-load [content]="rows" [context]="pager.context()" [continuation]="pager.cursor()" [loading]="pager.loadingMore()"
              loadingText="Загружаем следующие упражнения…"
              [error]="pager.moreFailed() ? 'Не удалось загрузить следующие упражнения. Загруженные остались на месте.' : null"
              (loadNext)="pager.next()" />
          }
        }
      </section>
    `,
    styleUrl: './public-deck-lists.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class PublicExercisesListComponent {
    readonly code = input.required<string>();
    /** The deck is gone (hidden or closed) while the list was being read: the page asks for the summary again. */
    readonly gone = output<PublicDeckFailure>();

    private readonly api = inject(PublicDeckApiService);
    private readonly injector = inject(Injector);
    private readonly heading = viewChild<ElementRef<HTMLElement>>('heading');
    protected readonly pager = new PublicPager<PublicExercise>(
        cursor => this.api.exercises(this.code(), cursor).pipe(map(page => ({ rows: page.exercises, total: page.total, nextCursor: page.nextCursor }))),
        row => row.exerciseId, inject(DestroyRef), failure => this.gone.emit(failure));
    protected readonly restartNotice = computed(() => this.pager.restarted() && this.pager.phase() === 'ready' ? RESTART_NOTICE : '');

    constructor() {
        effect(() => {
            this.code();
            untracked(() => this.pager.start());
        });
        // After a restart the list is short again: bring its head into view so the reader is not left in empty space.
        effect(() => {
            if (this.pager.restarted() && this.pager.phase() === 'ready') {
                untracked(() => afterNextRender(() => this.heading()?.nativeElement.scrollIntoView({ block: 'start' }), { injector: this.injector }));
            }
        });
    }

    protected typeLabel(exercise: PublicExercise): string {
        return (MECHANIC_LABELS as Readonly<Record<string, string | undefined>>)[exercise.type as Mechanic] ?? EXERCISE_FALLBACK_LABEL;
    }

    protected failureText(): string { return readFailureText(this.pager.failure(), 'упражнения'); }
}
