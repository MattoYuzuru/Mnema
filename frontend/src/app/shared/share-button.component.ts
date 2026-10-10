import { ChangeDetectionStrategy, Component, inject, input, signal } from '@angular/core';

import { GlyphComponent } from './glyph.component';
import { ShareLinkFieldComponent } from './share-link-field.component';
import { ShareLinkService } from './share-link.service';

/**
 * The visible «Поделиться» button (the public view puts it next to the main action). It runs {@link ShareLinkService}
 * and, when the clipboard refuses, shows the link in a selectable field right under itself. The result is announced by
 * the toast («Ссылка скопирована» or the error), not by the button.
 */
@Component({
    selector: 'app-share-button',
    imports: [GlyphComponent, ShareLinkFieldComponent],
    template: `
      <button type="button" class="button" (click)="share()"><app-glyph name="share" />Поделиться</button>
      @if (fallbackUrl(); as link) { <app-share-link-field [url]="link" [label]="'Ссылка на колоду «' + title() + '»'" /> }
    `,
    styles: [`:host { display: grid; justify-items: start; gap: .75rem; min-inline-size: 0; } app-share-link-field { inline-size: min(100%, 26rem); }`],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class ShareButtonComponent {
    private readonly links = inject(ShareLinkService);

    /** The address to share. */
    readonly url = input.required<string>();
    /** The deck title: it goes to the system sheet and names the fallback field. */
    readonly title = input.required<string>();

    protected readonly fallbackUrl = signal<string | null>(null);

    protected async share(): Promise<void> {
        this.fallbackUrl.set(null);
        const outcome = await this.links.share(this.url(), this.title());
        if (outcome === 'failed') this.fallbackUrl.set(this.url());
    }
}
