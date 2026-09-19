import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';

import { AuthService } from './auth.service';

@Component({
    selector: 'app-home-page',
    imports: [RouterLink],
    template: `
      <article class="home" aria-labelledby="home-title">
        <section class="hero">
          <p class="eyebrow">Личная библиотека знаний</p>
          <h1 id="home-title" tabindex="-1">Учиться.<br>Понимать.<br>Помнить.</h1>
          <p class="intro">
            Собирайте собственные учебные колоды и сохраняйте контекст в названии и описании.
            Mnema показывает только подтверждённые сервером изменения и помогает безопасно
            разобраться с конфликтом между вкладками.
          </p>
          <div class="actions">
            @if (auth.status() === 'authenticated') {
              <a class="primary" routerLink="/decks">Открыть мои колоды <span aria-hidden="true">→</span></a>
              <a class="secondary" routerLink="/decks/new">Создать колоду</a>
            } @else {
              <a class="primary" routerLink="/login">Войти <span aria-hidden="true">→</span></a>
              <a class="secondary" href="#principles">Как это устроено</a>
            }
          </div>
        </section>

        <section id="principles" class="principles" aria-labelledby="principles-title">
          <header>
            <p class="eyebrow">Сейчас в Mnema</p>
            <h2 id="principles-title">Свои колоды — без выдуманного прогресса</h2>
          </header>
          <ol>
            <li>
              <span class="number" aria-hidden="true">01</span>
              <div><h3>Создайте основу</h3><p>Дайте колоде точное название и описание. Пробелы и переносы сохраняются как введены.</p></div>
            </li>
            <li>
              <span class="number" aria-hidden="true">02</span>
              <div><h3>Редактируйте безопасно</h3><p>Повтор запроса использует ту же команду, а конфликт версий требует явного выбора.</p></div>
            </li>
            <li>
              <span class="number" aria-hidden="true">03</span>
              <div><h3>Продолжите после сбоя</h3><p>Неподтверждённый ввод остаётся в текущей вкладке и привязан к вашему аккаунту.</p></div>
            </li>
          </ol>
        </section>
      </article>
    `,
    styles: [`
      :host {
        --paper: #f4f0e5;
        --sheet: #fbf8ef;
        --ink: #281378;
        --body: #342e44;
        --muted: #625c70;
        --line: #c9c0ce;
        display: block;
        color: var(--body);
        background: var(--paper);
      }

      * { box-sizing: border-box; }

      .home {
        inline-size: min(76rem, 100%);
        margin-inline: auto;
        padding: clamp(3rem, 9vw, 8rem) clamp(1.25rem, 6vw, 5rem);
      }

      .hero {
        display: grid;
        grid-template-columns: minmax(0, 1.1fr) minmax(18rem, .9fr);
        gap: clamp(2.5rem, 8vw, 8rem);
        align-items: end;
        padding-block-end: clamp(4rem, 10vw, 8rem);
      }

      .eyebrow {
        margin: 0 0 1rem;
        color: var(--ink);
        font: 700 .75rem/1.3 system-ui, sans-serif;
        letter-spacing: .14em;
        text-transform: uppercase;
      }

      h1, h2, h3 { color: var(--ink); font-family: Georgia, 'Times New Roman', serif; }
      h1 {
        grid-row: span 2;
        margin: 0;
        font-size: clamp(3rem, 9vw, 7.5rem);
        font-weight: 500;
        letter-spacing: -.045em;
        line-height: .88;
      }
      h2 { max-inline-size: 20ch; margin: 0; font-size: clamp(2rem, 5vw, 4rem); font-weight: 500; line-height: 1.05; }
      h3 { margin: 0 0 .5rem; font-size: 1.4rem; font-weight: 500; }

      .intro { max-inline-size: 48ch; margin: 0; color: var(--muted); font-size: clamp(1rem, 2vw, 1.2rem); line-height: 1.65; }
      .actions { display: flex; flex-wrap: wrap; gap: .75rem 1rem; align-items: center; margin-block-start: 2rem; }
      .actions a {
        min-block-size: 2.75rem;
        display: inline-flex;
        align-items: center;
        justify-content: center;
        border: 1px solid var(--ink);
        border-radius: 2px;
        padding: .7rem 1.1rem;
        color: var(--ink);
        font-weight: 700;
        text-decoration: none;
      }
      .actions .primary { color: #fff; background: var(--ink); }
      .actions .secondary { background: transparent; }
      .actions a:hover { text-decoration: underline; text-underline-offset: .2em; }

      .principles { padding-block-start: clamp(3rem, 7vw, 6rem); border-block-start: 1px solid var(--line); }
      .principles > header { display: grid; grid-template-columns: minmax(10rem, .5fr) minmax(0, 1fr); gap: 2rem; }
      .principles ol { margin: clamp(2.5rem, 6vw, 5rem) 0 0; padding: 0; border-block-start: 1px solid var(--line); list-style: none; }
      .principles li { display: grid; grid-template-columns: 4rem minmax(0, 1fr); gap: 1rem; padding: 1.5rem 0; border-block-end: 1px solid var(--line); }
      .principles li div { display: grid; grid-template-columns: minmax(12rem, .4fr) minmax(0, 1fr); gap: 1rem 2rem; }
      .principles p { max-inline-size: 52ch; margin: 0; color: var(--muted); line-height: 1.6; overflow-wrap: anywhere; }
      .number { color: var(--ink); font: .8rem/1.4 ui-monospace, monospace; }

      :where(a):focus-visible,
      h1:focus-visible { outline: 3px solid var(--ink); outline-offset: 3px; }

      @media (max-width: 48rem) {
        .hero, .principles > header { grid-template-columns: 1fr; }
        h1 { grid-row: auto; }
        .principles li div { grid-template-columns: 1fr; }
      }

      @media (prefers-reduced-motion: reduce) {
        * { scroll-behavior: auto !important; }
      }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class HomePageComponent {
    readonly auth = inject(AuthService);
}
