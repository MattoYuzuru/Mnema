import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

import { LEGAL_DOCUMENT_VERSION, LEGAL_EFFECTIVE_DATE } from './shared/legal-operator';
import { LegalOperatorBlockComponent } from './shared/legal-operator-block.component';

/** Table of contents and headings share these ids; the spec checks that every entry resolves to a heading. */
export const TERMS_SECTIONS = [
    { id: 'parties', title: '1. Стороны и принятие соглашения' },
    { id: 'subject', title: '2. Предмет соглашения' },
    { id: 'account', title: '3. Аккаунт' },
    { id: 'plans', title: '4. Тарифы, промокоды и платежи' },
    { id: 'content', title: '5. Ваш контент' },
    { id: 'sharing', title: '6. Общий доступ и публичные колоды' },
    { id: 'rules', title: '7. Запрещённое использование' },
    { id: 'ai', title: '8. Функции искусственного интеллекта' },
    { id: 'availability', title: '9. Доступность и изменение сервиса' },
    { id: 'liability', title: '10. Ответственность' },
    { id: 'termination', title: '11. Прекращение использования' },
    { id: 'law', title: '12. Применимое право и споры' },
    { id: 'amendments', title: '13. Изменение соглашения' },
    { id: 'contacts', title: '14. Контакты и реквизиты' }
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
