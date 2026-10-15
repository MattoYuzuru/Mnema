import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';

let nextEmpty = 0;

/**
 * The community catalogue when no deck has been published yet. There are no starter decks, so the page says so plainly (the
 * approved text of the product contract, unchanged), offers the next step and explains it: «Как поделиться колодой» opens three
 * short steps and the catalogue threshold in plain words (a native `details`, so Enter, Space and the state come from the platform). A signed-in reader is led to «Мои колоды»,
 * a guest to «Войти». It uses the shared `.empty-state.unruled` for an uninterrupted catalogue page.
 */
@Component({
    selector: 'app-community-empty',
    imports: [RouterLink],
    template: `
      <section class="empty-state unruled" [attr.aria-labelledby]="titleId">
        <h2 [id]="titleId">Пока здесь пусто.</h2>
        <p>Сделайте свою колоду публичной, и она появится здесь первой.</p>
        <details class="how">
          <summary>Как поделиться колодой</summary>
          <ol>
            <li>Откройте свою колоду.</li>
            <li>В разделе «Доступ и публикация» выберите «Публичная».</li>
            <li>Пройдите чек-лист: описание, тема и язык, публичный профиль.</li>
          </ol>
          <p class="threshold">В колоде должно быть не меньше 10 материалов, хотя бы одно упражнение и описание.</p>
        </details>
        <p class="actions">
          @if (signedIn()) { <a class="button primary" routerLink="/decks">Мои колоды</a> }
          @else { <a class="button primary" routerLink="/login">Войти</a> }
        </p>
      </section>
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; }
      .empty-state h2 { margin-block: 0 .75rem; }
      .how { margin-block: 1rem; }
      .how summary { min-block-size: var(--mn-touch-min); padding-block: .6rem; color: var(--mn-ink); font-weight: 600; text-decoration: underline; text-underline-offset: .22em; cursor: pointer; }
      .how ol { margin: .25rem 0 0; padding-inline-start: 1.5rem; line-height: 1.6; }
      .how li + li { margin-block-start: .35rem; }
      .threshold { margin: .75rem 0 0; max-inline-size: 48ch; line-height: 1.6; }
      .actions { margin-block: 1rem 0; }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class CommunityEmptyComponent {
    /** A signed-in reader goes to their decks, a guest to the sign-in page. */
    readonly signedIn = input(false);

    protected readonly titleId = `mn-community-empty-${nextEmpty++}-title`;
}
