import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

import { SupportContactComponent } from './support-contact.component';

let nextFooterId = 0;

@Component({
    selector: 'app-public-footer',
    imports: [RouterLink, SupportContactComponent],
    template: `
      <footer class="footer">
        <section [attr.aria-labelledby]="uid + '-about'">
          <h2 class="eyebrow" [id]="uid + '-about'">О проекте</h2>
          <a class="footer-brand" routerLink="/" aria-label="Mnema, главная">MNEMA</a>
          <p>Место для ваших материалов, упражнений и повторений.</p>
          <p>ИП Рябушкин Матвей Игоревич</p>
          <dl class="operator-details">
            <div><dt>ИНН</dt><dd>771573834080</dd></div>
            <div><dt>ОГРНИП</dt><dd>326774600705952</dd></div>
          </dl>
        </section>
        <nav [attr.aria-labelledby]="uid + '-sections'">
          <h2 class="eyebrow" [id]="uid + '-sections'">Разделы</h2>
          <ul>
            <li><a routerLink="/" fragment="materials">Материалы</a></li>
            <li><a routerLink="/" fragment="exercises">Упражнения</a></li>
            <li><a routerLink="/plans">Тарифы</a></li>
            <li><a routerLink="/events">События</a></li>
          </ul>
        </nav>
        <nav [attr.aria-labelledby]="uid + '-legal'">
          <h2 class="eyebrow" [id]="uid + '-legal'">Правовая информация</h2>
          <ul>
            <li><a routerLink="/privacy">Политика конфиденциальности</a></li>
            <li><a routerLink="/terms">Условия использования</a></li>
            <li><a routerLink="/ai">Как Mnema использует ИИ</a></li>
          </ul>
        </nav>
        <nav [attr.aria-labelledby]="uid + '-contact'">
          <h2 class="eyebrow" [id]="uid + '-contact'">Контакты</h2>
          <p>Вопросы, идеи, сообщения об ошибках и другая обратная связь.</p>
          <app-support-contact />
        </nav>
        <p class="footer-note">MNEMA · ДЛЯ ТЕХ, КТО ЛЮБИТ УЧИТЬСЯ</p>
      </footer>
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; }
      .footer { inline-size: min(var(--mn-page-width), calc(100% - 2 * var(--mn-page-gutter))); margin-inline: auto; display: grid; grid-template-columns: 1.2fr repeat(3, minmax(0, 1fr)); gap: var(--mn-space-6) var(--mn-space-7); border-block-start: 1px solid var(--mn-rule); padding-block: var(--mn-space-7) var(--mn-space-6); color: var(--mn-body); font: .9375rem/1.6 var(--mn-font-body); }
      section, nav { min-inline-size: 0; }
      h2 { margin: 0 0 var(--mn-space-3); }
      p { margin: 0 0 var(--mn-space-3); overflow-wrap: anywhere; }
      ul { list-style: none; padding: 0; margin: 0; }
      a { display: inline-flex; align-items: center; min-block-size: var(--mn-touch-min); color: var(--mn-ink); text-underline-offset: .22em; overflow-wrap: anywhere; }
      .footer-brand { font: 500 2rem/1 var(--mn-font-display); text-decoration: none; margin-block-end: var(--mn-space-3); }
      .operator-details { margin: 0; font-size: .875rem; color: var(--mn-muted); }
      .operator-details div { display: flex; flex-wrap: wrap; gap: var(--mn-space-1); }
      .operator-details dd { margin: 0; overflow-wrap: anywhere; }
      .footer-note { grid-column: 1 / -1; margin: 0; padding-block-start: var(--mn-space-5); border-block-start: 1px solid var(--mn-rule); color: var(--mn-muted); font: .6875rem/1.7 var(--mn-font-mono); letter-spacing: .03em; }
      @media (max-width: 62.5rem) { .footer { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--mn-space-6); } }
      @media (max-width: 35rem) { .footer { grid-template-columns: minmax(0, 1fr); } }
      @media (forced-colors: active) { .footer, .footer-note { border-color: CanvasText; } }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class PublicFooterComponent {
    protected readonly uid = `mn-footer-${nextFooterId++}`;
}
