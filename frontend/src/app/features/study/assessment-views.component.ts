import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

import { ExclusivePlaybackDirective } from '../../content/exercise/exclusive-playback.directive';
import { LearnerBlocksComponent } from '../../content/exercise/learner-blocks.component';
import {
    AssessmentFeedback, SELF_RATINGS, SELF_RATING_LABELS, SelfCheckAttempt, SelfCheckReason, SelfRating
} from './study.models';

/** Words for the learner; the reasons are the server's stable codes and never carry a provider detail. */
const REASON_TEXT: Readonly<Record<SelfCheckReason, string>> = {
    LEARNER_CHOICE: 'Вы решили оценить себя сами. Ответ сохранён: сверьтесь с эталоном и пунктами.',
    PROVIDER_UNCERTAIN: 'Мнема не уверена в оценке этого ответа и не будет гадать. Сверьтесь с эталоном и оцените себя сами.',
    PROVIDER_UNAVAILABLE: 'Проверка сейчас недоступна. Ответ не потерян: сверьтесь с эталоном и оцените себя сами.',
    DEADLINE: 'Проверка заняла слишком много времени. Ответ не потерян: сверьтесь с эталоном и оцените себя сами.',
    USAGE_LIMIT: 'На сегодня проверки ответов с ИИ закончились. Сверьтесь с эталоном и оцените себя сами.',
    CAPABILITY_UNAVAILABLE: 'Проверка ответов с ИИ сейчас отключена. Сверьтесь с эталоном и оцените себя сами.',
    BUSY: 'Мнема уже проверяет несколько ваших ответов, поэтому этот оцените сами: сверьтесь с эталоном.'
};

const SHARED_STYLES = `
  :host { display: block; min-inline-size: 0; }
  p, h3, ul, dl, dd { margin: 0; }
  .answer-text { white-space: pre-wrap; overflow-wrap: anywhere; }
  :where(a, button, input, textarea, summary):focus-visible { outline: 3px solid var(--mn-focus, var(--mn-ink)); outline-offset: 3px; }
  .visually-hidden { position: absolute; inline-size: 1px; block-size: 1px; margin: -1px; overflow: hidden; clip-path: inset(50%); white-space: nowrap; }
`;

/**
 * Shown while the model grades an answer: a polite status, the learner's answer (read-only) and, once the wait has
 * lasted 5 seconds, a secondary «Оценить себя». Nothing here depends on how the grader works.
 */
@Component({
    selector: 'app-assessment-waiting',
    template: `
      <div class="waiting">
        <p class="status" role="status">
          <span class="spinner" aria-hidden="true"></span>
          <span>{{ offered() ? 'Проверка занимает дольше обычного.' : 'Смотрим, что в вашем ответе есть по смыслу. Обычно это несколько секунд.' }}</span>
        </p>
        <section class="answer" aria-labelledby="assessing-answer-title">
          <h3 id="assessing-answer-title">Ваш ответ</h3>
          <p class="answer-text">{{ answer() ?? 'Ответ принят. Его текст не сохранился в этой вкладке.' }}</p>
        </section>
        @if (offered()) {
          <div class="offer">
            <p class="hint">Можно не ждать: сверьтесь с эталоном и оцените себя сами. Если оценка придёт раньше, вы увидите её.</p>
            <button class="button" type="button" data-self-check [attr.aria-disabled]="busy() ? 'true' : null" (click)="selfCheck.emit()">Оценить себя</button>
          </div>
        }
        @if (problem(); as message) {
          <div class="notice error" role="alert">
            <p>{{ message }}</p>
            <button class="button" type="button" data-retry (click)="retry.emit()">Проверить ещё раз</button>
          </div>
        }
      </div>
    `,
    styles: [SHARED_STYLES + `
      .waiting { display: grid; gap: 1.25rem; min-inline-size: 0; }
      .status { display: flex; align-items: center; gap: .75rem; color: var(--mn-ink); font-weight: 600; }
      .spinner { flex: 0 0 auto; inline-size: 1.1rem; block-size: 1.1rem; border: 2px solid var(--mn-rule); border-block-start-color: var(--mn-ink); border-radius: 50%; animation: assessing-spin 1s linear infinite; }
      @keyframes assessing-spin { to { transform: rotate(360deg); } }
      @media (prefers-reduced-motion: reduce) { .spinner { animation: none; border-color: var(--mn-ink); } }
      .answer { display: grid; gap: .4rem; border-block-start: 1px solid var(--mn-rule); padding-block-start: .75rem; }
      .answer h3 { color: var(--mn-muted); font: 600 .8rem/1.4 var(--mn-font-body, system-ui, sans-serif); }
      .offer { display: grid; gap: .6rem; justify-items: start; }
      .notice { display: grid; gap: .6rem; justify-items: start; }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class AssessmentWaitingComponent {
    /** The answer the learner sent; `null` after a reload when the tab no longer holds it. */
    readonly answer = input<string | null>(null);
    readonly offered = input(false);
    readonly busy = input(false);
    readonly problem = input<string | null>(null);
    readonly selfCheck = output<void>();
    readonly retry = output<void>();
}

/**
 * Self-check mode of an explanation: why Мнема did not grade it, the answer next to the reference, the key points to
 * compare with, and the existing four self-rating controls. The rating completes the same answer.
 */
@Component({
    selector: 'app-assessment-self-check',
    imports: [LearnerBlocksComponent, ExclusivePlaybackDirective],
    template: `
      <div class="self-check" appExclusivePlayback>
        <p class="notice" role="status">{{ reasonText() }}</p>
        <dl class="comparison">
          <div><dt>Ваш ответ</dt><dd class="answer-text">{{ answer() ?? 'Ответ принят. Его текст не сохранился в этой вкладке.' }}</dd></div>
          <div><dt>Эталон</dt><dd class="answer-text">{{ view().selfCheck.reference }}</dd></div>
        </dl>
        @if (view().selfCheck.referenceContent.length > 0) {
          <app-learner-blocks [blocks]="view().selfCheck.referenceContent" nameSuffix=" в эталоне" />
        }
        <section class="points" aria-labelledby="self-check-points-title">
          <h3 id="self-check-points-title">Что должно быть в хорошем ответе</h3>
          <ul>
            @for (point of view().selfCheck.criteria; track point.criterionId) { <li>{{ point.description }}</li> }
          </ul>
        </section>
        <fieldset [attr.aria-busy]="busy() ? 'true' : null">
          <legend>Как получилось на самом деле?</legend>
          <div class="ratings">
            @for (rating of ratings; track rating; let first = $first) {
              <button class="button" type="button" [attr.data-first-rating]="first ? '' : null" [attr.aria-disabled]="busy() ? 'true' : null" (click)="rated.emit(rating)">{{ label(rating) }}</button>
            }
          </div>
        </fieldset>
        @if (problem(); as message) { <p class="notice error" role="alert">{{ message }}</p> }
      </div>
    `,
    styles: [SHARED_STYLES + `
      .self-check { display: grid; gap: 1.25rem; min-inline-size: 0; }
      .comparison { display: grid; grid-template-columns: 1fr 1fr; gap: 1rem; }
      .comparison div { min-inline-size: 0; border-block-start: 1px solid var(--mn-rule); padding-block-start: .75rem; }
      .comparison dt { color: var(--mn-muted); font-size: .8rem; }
      .comparison dd { margin: .4rem 0 0; }
      .points { display: grid; gap: .5rem; }
      h3 { color: var(--mn-ink); font: 500 1.2rem/1.25 var(--mn-font-display, Georgia, serif); }
      ul { display: grid; gap: .4rem; padding-inline-start: 1.2rem; }
      fieldset { min-inline-size: 0; margin: 0; border: 0; padding: 0; }
      legend { padding: 0 0 .5rem; color: var(--mn-ink); font-weight: 700; }
      .ratings { display: flex; flex-wrap: wrap; gap: .6rem; }
      @media (max-width: 36rem) { .comparison { grid-template-columns: 1fr; } .ratings .button { inline-size: 100%; } }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class AssessmentSelfCheckComponent {
    readonly view = input.required<SelfCheckAttempt>();
    readonly answer = input<string | null>(null);
    readonly busy = input(false);
    readonly problem = input<string | null>(null);
    readonly rated = output<SelfRating>();
    readonly ratings = SELF_RATINGS;
    readonly reasonText = computed(() => REASON_TEXT[this.view().reason]);

    label(rating: SelfRating): string { return SELF_RATING_LABELS[rating]; }
}

/**
 * The learner-facing result of a model grade: what the answer covered (with their own words), what is missing,
 * anything that contradicts the reference, and a note when the next attempt will be stricter. Every state has words,
 * not only a colour or a mark.
 */
@Component({
    selector: 'app-assessment-result',
    template: `
      <div class="result">
        @if (assessment().covered.length > 0) {
          <section aria-labelledby="assessment-covered-title">
            <h3 id="assessment-covered-title">Есть</h3>
            <ul class="points covered">
              @for (point of assessment().covered; track point.criterionId) {
                <li>
                  <p><span class="mark" aria-hidden="true">✓</span> {{ point.description }}@if (point.partial) { <span class="tag">сказано не до конца</span> }</p>
                  <p class="quote"><span class="quote-label">Вы написали: </span><q>{{ point.quote }}</q></p>
                </li>
              }
            </ul>
          </section>
        }
        @if (assessment().missing.length > 0) {
          <section aria-labelledby="assessment-missing-title">
            <h3 id="assessment-missing-title">Не хватает</h3>
            <ul class="points missing">
              @for (point of assessment().missing; track point.criterionId) {
                <li><p><span class="mark" aria-hidden="true">○</span> {{ point.description }}@if (point.partial) { <span class="tag">есть начало, нужно полнее</span> }</p></li>
              }
            </ul>
          </section>
        }
        @if (assessment().contradicted.length > 0) {
          <section aria-labelledby="assessment-contradicted-title">
            <h3 id="assessment-contradicted-title">Противоречит эталону</h3>
            <ul class="points contradicted">
              @for (point of assessment().contradicted; track point.criterionId) {
                <li><p><span class="mark" aria-hidden="true">✗</span> {{ point.description }}</p>
                  @if (point.note) { <p class="quote">{{ point.note }}</p> }</li>
              }
            </ul>
          </section>
        }
        @if (nextNote(); as note) { <p class="next" data-next-stricter>{{ note }}</p> }
      </div>
    `,
    styles: [SHARED_STYLES + `
      .result { display: grid; gap: 1.25rem; min-inline-size: 0; }
      section { display: grid; gap: .5rem; min-inline-size: 0; }
      h3 { color: var(--mn-ink); font: 500 1.35rem/1.25 var(--mn-font-display, Georgia, serif); }
      .points { display: grid; gap: .75rem; padding: 0; list-style: none; }
      .points li { display: grid; gap: .25rem; min-inline-size: 0; border-block-start: 1px solid var(--mn-rule); padding-block-start: .6rem; overflow-wrap: anywhere; }
      .mark { display: inline-block; inline-size: 1.2rem; font-weight: 700; }
      .covered .mark { color: var(--mn-positive); }
      .contradicted .mark { color: var(--mn-danger); }
      .tag { display: inline-block; margin-inline-start: .5rem; border: 1px solid var(--mn-rule); padding: 0 .4rem; color: var(--mn-muted); font-size: .8rem; }
      .quote { padding-inline-start: 1.2rem; color: var(--mn-muted); }
      .quote-label { font-size: .8rem; }
      .quote q { color: var(--mn-body); font-style: italic; white-space: pre-wrap; }
      .next { border-inline-start: 4px solid var(--mn-ink); padding: .8rem 1rem; background: var(--mn-soft); }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class AssessmentResultComponent {
    readonly assessment = input.required<AssessmentFeedback>();
    /** After S1 and S2 the next attempt asks for more: say what is missing so the learner can prepare. */
    readonly nextNote = computed(() => {
        const value = this.assessment();
        if (!value.nextStricter) return null;
        const open = [...value.missing.map(point => point.description), ...value.contradicted.map(point => point.description)];
        return open.length === 0 ? 'В следующий раз проверка будет строже: нужно будет сказать всё полно.'
            : `В следующий раз я попрошу точнее: ${open.join('; ')}.`;
    });
}
