import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { SupportContactComponent } from '../../shared/support-contact.component';

/**
 * `/ai` «Как Mnema использует ИИ»: public, linked from the footer and from the «Что это?» toggletips beside the AI actions.
 * Static text on purpose: it states the product's AI promises (what AI does, where data goes, limits, how to switch it off,
 * no «created with AI» marks) and must read the same signed in or out. The sources of the claims are
 * `docs/product/ai-layer-2026-10.md` sections 3 and 6.
 */
@Component({
    selector: 'app-ai-page',
    imports: [RouterLink, SupportContactComponent],
    template: `
      <article class="ai-page" lang="ru" aria-labelledby="ai-title">
        <p class="eyebrow">Мнема и ИИ</p>
        <h1 id="ai-title" tabindex="-1">Как Mnema использует ИИ</h1>
        <p class="lede">Мнема использует ИИ, чтобы помогать создавать качественные материалы и упражнения для более эффективного обучения. Здесь рассказываем, как это работает, какие данные нужны и как вы управляете ИИ.</p>

        <nav class="toc" aria-label="Содержание страницы">
          <a [routerLink]="[]" fragment="what">Что делает ИИ</a>
          <a [routerLink]="[]" fragment="data">Куда уходят данные</a>
          <a [routerLink]="[]" fragment="limits">Лимиты</a>
          <a [routerLink]="[]" fragment="off">Как выключить</a>
          <a [routerLink]="[]" fragment="marks">Пометки «создано ИИ»</a>
          <a [routerLink]="[]" fragment="contact">Связаться</a>
        </nav>

        <section id="what" aria-labelledby="what-title">
          <h2 id="what-title">Что делает ИИ</h2>
          <p>Помощник называется Мнема. Он работает только тогда, когда вы сами запускаете действие, и ничего не попадает в колоду и в занятия, пока вы не одобрите.</p>
          <ul>
            <li><strong>Материалы.</strong> Из ваших заметок и описания Мнема предлагает черновик материала.</li>
            <li><strong>Упражнения.</strong> По выбранным материалам Мнема предлагает упражнения; каждое можно попробовать до сохранения.</li>
            <li><strong>Правки.</strong> Выделите часть материала или упражнения и попросите изменить: результат показывается как предложение, которое можно оставить, вернуть или повторить.</li>
            <li><strong>Проверка ответов.</strong> Если вы отвечаете своими словами, ИИ сравнивает ответ с эталоном. Если проверка недоступна, вы оцениваете себя сами.</li>
            <li><strong>Голос.</strong> Озвучка материалов и распознавание речи, если они включены для вашего аккаунта.</li>
            <li><strong>Картинки.</strong> Мнема подбирает изображения из открытых лицензионных источников и показывает источник и лицензию. Картинки она пока не рисует.</li>
            <li><strong>Источники.</strong> Если включён режим «Проверять факты», Мнема ищет сведения в сети и показывает найденные источники. Это не гарантирует достоверность каждого утверждения. Сверяйте факты в черновике с источниками: это ещё одна возможность разобраться в теме и лучше её запомнить.</li>
          </ul>
        </section>

        <section id="data" aria-labelledby="data-title">
          <h2 id="data-title">Куда уходят данные</h2>
          <p>Мнема использует внешнюю инфраструктуру, в том числе серверы ИИ-провайдеров. В зависимости от функции и подключённого маршрута данные могут обрабатываться в России или за рубежом.</p>
          <ul>
            <li><strong>Текст.</strong> В запрос могут войти ваши заметки, учебные материалы, сведения о колоде, задание и ответ на упражнение. При подключённых внешних моделях запрос передаётся ИИ-провайдеру и может обрабатываться за рубежом. Перед отправкой Мнема маскирует найденные адреса почты, распространённые записи телефонов и номера карт. Имена, адреса и другие сведения могут остаться: фильтр не гарантирует обезличивание.</li>
            <li><strong>Распознавание речи.</strong> На сервер распознавания передаётся запись голоса, которая может содержать личные сведения. Перед голосовым вводом мы спрашиваем отдельное согласие на регион обработки. В зависимости от подключённого маршрута запись обрабатывается на отдельном сервере в России или у внешнего провайдера за рубежом. В профиле можно отозвать согласие.</li>
            <li><strong>Озвучка.</strong> Для синтеза речи провайдер получает текст, язык и выбранный голос. Это отдельное действие: согласие на распознавание записи не управляет озвучкой. Для текста действует тот же ограниченный фильтр; при подключённом зарубежном маршруте обработка происходит за рубежом.</li>
            <li><strong>Картинки.</strong> В сервис поиска изображений уходит только поисковый запрос по теме.</li>
            <li><strong>Веб-поиск.</strong> Для поиска источников отправляются поисковые запросы. В зависимости от подключённого сервиса они обрабатываются в России или за рубежом.</li>
            <li><strong>Хранение в Mnema.</strong> Аккаунт, колоды, материалы и прогресс хранятся на серверах в России. Это не означает, что запросы к внешним провайдерам остаются в России.</li>
          </ul>
          <p>Зарубежные маршруты подключаются после проверки условий обработки и необходимых раскрытий. Не включайте личные или конфиденциальные сведения в запросы к ИИ.</p>
          <p>Подробнее об обработке данных: <a routerLink="/privacy">политика конфиденциальности</a>.</p>
        </section>

        <section id="limits" aria-labelledby="limits-title">
          <h2 id="limits-title">Лимиты и добросовестное использование</h2>
          <p>У каждого тарифа есть бюджет ИИ на месяц; он показан в профиле простой полосой и обновляется в начале месяца. Голос и проверка ответов не тратят этот бюджет, но у них свой потолок на месяц и на день: так сервис остаётся доступным для всех.</p>
          <p>Когда лимит заканчивается, Mnema продолжает работать: колоды, занятия и повторения никуда не деваются, недоступны только действия ИИ. Состав тарифов и цены — на странице <a routerLink="/plans">тарифов</a>.</p>
        </section>

        <section id="off" aria-labelledby="off-title">
          <h2 id="off-title">Как выключить ИИ</h2>
          <ul>
            <li>Не запускайте действия ИИ: без вашего запроса Мнема ничего не отправляет.</li>
            <li>Все колоды можно вести вручную: материалы и упражнения создаются и редактируются без ИИ.</li>
            <li>Голос работает только когда вы сами его запускаете: распознавание и озвучка не включаются сами.</li>
            <li>Согласие на распознавание можно <a routerLink="/profile" fragment="speech-consent">отозвать в профиле</a>. Уже начатая обработка записи может завершиться.</li>
          </ul>
        </section>

        <section id="marks" aria-labelledby="marks-title">
          <h2 id="marks-title">Пометки «создано ИИ»</h2>
          <p>Мы не ставим на материалы и упражнения метку «создано с ИИ»: то, что вы одобрили и сохранили, становится вашим учебным материалом. Внутри сервиса мы храним происхождение записи для учёта расходов и проверки качества, но вам эта пометка не показывается.</p>
        </section>

        <section id="contact" aria-labelledby="contact-title">
          <h2 id="contact-title">Связаться с нами</h2>
          <p>Если у вас есть пожелания, жалобы, предложения или другая обратная связь, напишите нам через Telegram-бота.</p>
          <app-support-contact />
        </section>
      </article>
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; color: var(--mn-body); overflow-wrap: anywhere; }
      .ai-page { max-inline-size: 52rem; margin-inline: auto; padding: var(--mn-space-7) var(--mn-page-gutter); }
      h1 { margin: 0 0 var(--mn-space-3); color: var(--mn-ink); font-family: var(--mn-font-display); font-weight: 500; font-size: clamp(2.2rem, 5vw, 3.5rem); line-height: 1; overflow-wrap: anywhere; }
      h2 { margin: 0 0 var(--mn-space-3); color: var(--mn-ink); font-family: var(--mn-font-display); font-weight: 500; font-size: clamp(1.5rem, 3vw, 2rem); line-height: 1.1; overflow-wrap: anywhere; }
      .lede { max-inline-size: 52ch; color: var(--mn-muted); margin: 0 0 var(--mn-space-5); }
      .toc { display: flex; flex-wrap: wrap; gap: .25rem 1.25rem; margin-block-end: var(--mn-space-6); }
      .toc a { display: inline-flex; align-items: center; min-block-size: var(--mn-touch-min); color: var(--mn-ink); text-underline-offset: .22em; }
      section { margin-block-end: var(--mn-space-6); scroll-margin-block-start: 1rem; }
      p, li { line-height: 1.6; }
      p { margin: 0 0 var(--mn-space-3); }
      ul { margin: 0 0 var(--mn-space-3); padding-inline-start: 1.25rem; display: grid; gap: .5rem; }
      a { color: var(--mn-ink); text-underline-offset: .22em; }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class AiPageComponent {}
