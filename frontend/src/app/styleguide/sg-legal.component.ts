import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

import { LegalOperatorBlockComponent } from '../shared/legal-operator-block.component';

/**
 * Specimen of the public legal-document layout: operator block, `.toc` (contents) and `.facts` (term and description rows).
 * It loads the real stylesheet of the legal pages, so the catalogue shows what the documents use.
 */
@Component({
    selector: 'app-sg-legal',
    imports: [RouterLink, LegalOperatorBlockComponent],
    styleUrl: '../shared/legal-page.css',
    template: `
      <app-legal-operator-block />
      <nav class="toc" aria-label="Пример содержания документа">
        <h2>Содержание</h2>
        <ol>
          <li><a routerLink="/privacy" fragment="general">1. Общие положения</a></li>
          <li><a routerLink="/privacy" fragment="data">2. Какие данные мы обрабатываем</a></li>
          <li><a routerLink="/privacy" fragment="purposes">3. Цели, основания и сроки</a></li>
          <li><a routerLink="/privacy" fragment="rights">4. Ваши права</a></li>
        </ol>
      </nav>
      <dl class="facts">
        <div><dt>Получатель</dt><dd>Название и страна получателя данных.</dd></div>
        <div><dt>Цель</dt><dd>Для чего передаются данные.</dd></div>
      </dl>
    `,
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class SgLegalComponent {}
