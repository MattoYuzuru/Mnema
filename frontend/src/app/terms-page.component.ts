import { Component, ChangeDetectionStrategy } from '@angular/core';
import { SupportContactComponent } from './shared/support-contact.component';

@Component({
    selector: 'app-terms-page',
    imports: [SupportContactComponent],
    template: `
    <div class="legal-page" lang="ru">
      <h1 tabindex="-1">Условия использования</h1>
      <p class="last-updated">Последнее обновление: октябрь 2026</p>

      <section>
        <h2>Принятие условий</h2>
        <p>Получая доступ и используя Mnema, вы принимаете и соглашаетесь соблюдать условия и положения настоящего соглашения.</p>
      </section>

      <section>
        <h2>Доступ к сервису</h2>
        <p>Настоящие Условия регулируют доступ к размещённому сервису Mnema. Они не передают право собственности и не предоставляют прав на исходный код репозитория: исходный код лицензируется отдельно на условиях, опубликованных в репозитории.</p>
      </section>

      <section>
        <h2>Пользовательский контент</h2>
        <p>Вы сохраняете все права на созданный вами контент карточек. Делая колоды публичными, вы предоставляете другим пользователям право форкать и использовать ваш контент для личного обучения.</p>
      </section>

      <section>
        <h2>Запрещенное использование</h2>
        <p>Вы не можете использовать Mnema в незаконных целях или для нарушения каких-либо законов. Вы не можете пытаться получить несанкционированный доступ к какой-либо части сервиса.</p>
      </section>

      <section>
        <h2>Отказ от ответственности</h2>
        <p>Mnema предоставляется &quot;как есть&quot; без каких-либо заявлений или гарантий. Мы не гарантируем, что сервис будет бесперебойным или безошибочным.</p>
      </section>

      <section>
        <h2>Ограничение ответственности</h2>
        <p>Ни при каких обстоятельствах Mnema не несет ответственности за какие-либо убытки, возникающие в результате использования или невозможности использования сервиса.</p>
      </section>

      <section>
        <h2>Изменения условий</h2>
        <p>Мы оставляем за собой право изменять эти условия в любое время. Продолжение использования сервиса после изменений означает принятие новых условий.</p>
      </section>

      <section>
        <h2>Контакты</h2>
        <p>По вопросам об этих Условиях использования напишите нам через Telegram-бота.</p>
        <app-support-contact />
      </section>
    </div>
  `,
    changeDetection: ChangeDetectionStrategy.OnPush,
    styles: [`
      .legal-page {
        max-width: 56rem;
        margin: 0 auto;
        padding: var(--mn-space-7) var(--mn-page-gutter);
      }

      h1 {
        font-size: clamp(2.5rem, 5vw, 3.5rem);
        margin: 0 0 var(--mn-space-2) 0;
      }

      .last-updated {
        font-size: 0.9rem;
        color: var(--mn-muted);
        margin: 0 0 var(--mn-space-7) 0;
      }

      section {
        margin-bottom: var(--mn-space-7);
      }

      h2 {
        font-size: 2rem;
        margin: 0 0 var(--mn-space-4) 0;
      }

      p {
        line-height: 1.6;
        color: var(--mn-body);
        margin: 0 0 var(--mn-space-4) 0;
      }

      @media (max-width: 768px) {
        .legal-page {
          padding-block: var(--mn-space-5);
        }

        h1 {
          font-size: 2.5rem;
        }

        h2 {
          font-size: 1.75rem;
        }
      }

      @media (max-width: 480px) {
        .legal-page {
          padding-block: var(--mn-space-4);
        }

        h1 {
          font-size: 2.25rem;
        }
      }
    `]
})
export class TermsPageComponent {}
