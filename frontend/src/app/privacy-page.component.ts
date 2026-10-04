import { Component, ChangeDetectionStrategy } from '@angular/core';

@Component({
    selector: 'app-privacy-page',
    template: `
    <div class="legal-page" lang="ru">
      <h1>Политика конфиденциальности</h1>
      <p class="last-updated">Последнее обновление: октябрь 2026</p>

      <section>
        <h2>Собираемая информация</h2>
        <p>Когда вы используете Mnema, мы обрабатываем информацию, которую вы предоставляете нам напрямую, включая адрес электронной почты, имя пользователя, учебные материалы и упражнения.</p>
      </section>

      <section>
        <h2>Как мы используем вашу информацию</h2>
        <p>Мы используем эти данные для работы аккаунта, сохранения учебных материалов, проведения учебных сессий и отслеживания прогресса обучения.</p>
      </section>

      <section>
        <h2>Обмен информацией</h2>
        <p>Мы не продаем и не передаем вашу личную информацию третьим лицам, за исключением случаев, необходимых для предоставления наших услуг или требуемых законом.</p>
      </section>

      <section>
        <h2>Защита входа Cloudflare Turnstile</h2>
        <p>Когда проверка включена, браузер связывается с Cloudflare и передаёт технические данные соединения и устройства, включая IP-адрес, сведения о браузере и имя сайта. Сервис аккаунтов передаёт Cloudflare одноразовый токен проверки; пароль, почту и учебные материалы в этот запрос мы не включаем.</p>
        <p>Cloudflare обрабатывает эти данные для защиты от автоматизированного злоупотребления и согласно своим условиям.
          Подробнее: <a href="https://www.cloudflare.com/turnstile-privacy-policy/" target="_blank" rel="noopener noreferrer">Turnstile Privacy Addendum</a>.
          Проверка может не завершиться при блокировке скриптов или недоступности сервиса; в этом случае вход и регистрация остаются закрыты до новой успешной проверки.</p>
      </section>

      <section>
        <h2>Безопасность данных</h2>
        <p>Мы применяем соответствующие меры безопасности для защиты вашей личной информации от несанкционированного доступа, изменения или уничтожения.</p>
      </section>

      <section>
        <h2>Ваши права</h2>
        <p>Вы имеете право в любое время получать доступ, обновлять или удалять вашу личную информацию через настройки аккаунта.</p>
      </section>

      <section>
        <h2>Связаться с нами</h2>
        <p>Если у вас есть вопросы об этой Политике конфиденциальности, пожалуйста, свяжитесь с нами через наш репозиторий на GitHub.</p>
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
export class PrivacyPageComponent {}
