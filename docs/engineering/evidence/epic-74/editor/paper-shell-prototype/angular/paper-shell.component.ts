import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';

export interface PaperViewer {
    readonly authenticated: boolean;
    readonly displayName: string | null;
}

@Component({
    selector: 'app-paper-shell',
    standalone: true,
    imports: [RouterLink],
    templateUrl: './paper-shell.component.html',
    styleUrl: './paper-shell.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class PaperShellComponent {
    readonly viewer = input<PaperViewer>({ authenticated: false, displayName: null });
    readonly homeRoute = input('/');
    readonly decksRoute = input('/decks');
    readonly createDeckRoute = input('/create-deck');
    readonly loginRoute = input('/login');

    readonly viewerLabel = computed(() => {
        const viewer = this.viewer();
        if (!viewer.authenticated) {
            return 'Войти';
        }

        return viewer.displayName?.trim() || 'Моя Mnema';
    });
}
