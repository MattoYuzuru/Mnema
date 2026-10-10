import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

import { LEGAL_DOCUMENT_VERSION, LEGAL_EFFECTIVE_DATE } from './shared/legal-operator';
import { LegalOperatorBlockComponent } from './shared/legal-operator-block.component';

/** Table of contents and headings share these ids; the spec checks that every entry resolves to a heading. */
export const PRIVACY_SECTIONS = [
    { id: 'general', title: '1. Общие положения' },
    { id: 'data', title: '2. Какие данные мы обрабатываем и откуда они берутся' },
    { id: 'purposes', title: '3. Цели, основания и сроки обработки' },
    { id: 'bases', title: '4. Согласие и другие основания' },
    { id: 'methods', title: '5. Действия и способы обработки' },
    { id: 'cookies', title: '6. Cookie и хранилище браузера' },
    { id: 'recipients', title: '7. Кому могут передаваться данные' },
    { id: 'cross-border', title: '8. Трансграничная передача: Cloudflare Turnstile' },
    { id: 'localization', title: '9. Хранение в России' },
    { id: 'retention', title: '10. Сроки хранения, удаление и резервные копии' },
    { id: 'rights', title: '11. Ваши права и как ими воспользоваться' },
    { id: 'security', title: '12. Как мы защищаем данные' },
    { id: 'minors', title: '13. Дети' },
    { id: 'changes', title: '14. Изменения Политики' },
    { id: 'public-profile', title: '15. Публичный профиль и колоды сообщества' },
    { id: 'sources', title: '16. Нормативная база' }
] as const;

@Component({
    selector: 'app-privacy-page',
    imports: [RouterLink, LegalOperatorBlockComponent],
    templateUrl: './privacy-page.component.html',
    styleUrl: './shared/legal-page.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class PrivacyPageComponent {
    protected readonly sections = PRIVACY_SECTIONS;
    protected readonly effectiveDate = LEGAL_EFFECTIVE_DATE;
    protected readonly version = LEGAL_DOCUMENT_VERSION;
}
