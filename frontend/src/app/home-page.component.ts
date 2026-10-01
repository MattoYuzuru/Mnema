import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';

import { AuthService } from './auth.service';

@Component({
    selector: 'app-home-page',
    imports: [RouterLink],
    templateUrl: './home-page.component.html',
    styleUrl: './home-page.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class HomePageComponent {
    readonly auth = inject(AuthService);
}
