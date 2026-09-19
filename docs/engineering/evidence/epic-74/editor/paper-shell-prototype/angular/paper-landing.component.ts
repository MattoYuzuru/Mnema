import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
    selector: 'app-paper-landing',
    standalone: true,
    imports: [RouterLink],
    templateUrl: './paper-landing.component.html',
    styleUrl: './paper-landing.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class PaperLandingComponent {
    readonly authenticated = input(false);
    readonly decksRoute = input('/decks');
    readonly createDeckRoute = input('/create-deck');
    readonly engravingSrc = input<string | null>(null);

    readonly primaryRoute = computed(() => this.authenticated() ? this.decksRoute() : this.createDeckRoute());
    readonly primaryLabel = computed(() => this.authenticated() ? 'Открыть мои колоды' : 'Начать с моей колоды');
}
