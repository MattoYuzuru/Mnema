import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

import { LEGAL_DOCUMENT_VERSION, LEGAL_EFFECTIVE_DATE } from './shared/legal-operator';
import { LegalOperatorBlockComponent } from './shared/legal-operator-block.component';

/** Table of contents and headings share these ids; the spec checks that every entry resolves to a heading. */
export const TERMS_SECTIONS = [
    { id: 'parties', title: '1. Стороны и принятие Соглашения' },
    { id: 'subject', title: '2. Предмет Соглашения' },
    { id: 'account', title: '3. Аккаунт' },
    { id: 'plans', title: '4. Тарифы, промокоды и платежи' },
    { id: 'content', title: '5. Ваш контент' },
    { id: 'rules', title: '6. Запрещённое использование' },
    { id: 'ai', title: '7. Функции искусственного интеллекта' },
    { id: 'availability', title: '8. Доступность и изменение Сервиса' },
    { id: 'liability', title: '9. Ответственность' },
    { id: 'termination', title: '10. Прекращение использования' },
    { id: 'law', title: '11. Применимое право и споры' },
    { id: 'amendments', title: '12. Изменение Соглашения' },
    { id: 'contacts', title: '13. Контакты и реквизиты' }
] as const;

@Component({
    selector: 'app-terms-page',
    imports: [RouterLink, LegalOperatorBlockComponent],
    templateUrl: './terms-page.component.html',
    styleUrl: './shared/legal-page.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class TermsPageComponent {
    protected readonly sections = TERMS_SECTIONS;
    protected readonly effectiveDate = LEGAL_EFFECTIVE_DATE;
    protected readonly version = LEGAL_DOCUMENT_VERSION;
}
