import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
    selector: 'app-not-found-page',
    imports: [RouterLink],
    template: `<section class="empty-state"><h1 tabindex="-1">Страница не найдена</h1>
      <p>Проверьте адрес или вернитесь на главную.</p><a class="button" routerLink="/">На главную</a></section>`,
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class NotFoundPageComponent {}
