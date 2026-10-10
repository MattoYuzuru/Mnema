import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { ActionMenuComponent, ActionMenuItem } from './action-menu.component';
import { AuthorChipComponent } from './author-chip.component';
import { GlyphComponent } from './glyph.component';
import { MEDIA_LABELS, PublicDeckCard, audienceCount, sizeLine, updatedLabel } from './public-deck-card';
import { ShareLinkFieldComponent } from './share-link-field.component';
import { SHARE_MENU_ITEM, ShareLinkService } from './share-link.service';

let nextCard = 0;

/**
 * The card of a public deck in the catalogue, a shelf or an author's profile (skeleton: the layout and states; the data
 * source and the actions are wired by the screens that use it; «Поделиться» is done by the card itself). Title (a link when `link` is given), a two-line
 * description, topic and language (each with a hidden «Тема:» / «Язык:» prefix), the author chip, the size, media badges with words, the audience counts (only from
 * 10 and rounded), the update date and the «⋯» menu. There are no stars and no likes. Without an author (hidden
 * profile) the chip draws nothing; without media or with small audiences the row is simply absent.
 */
@Component({
    selector: 'app-public-deck-card',
    imports: [NgTemplateOutlet, RouterLink, ActionMenuComponent, AuthorChipComponent, GlyphComponent, ShareLinkFieldComponent],
    template: `
      <article class="card" [attr.aria-labelledby]="titleId">
        <div class="head">
          <ng-template #titleText>
            @if (link(); as to) { <a [routerLink]="to">{{ deck().title }}</a> } @else { {{ deck().title }} }
          </ng-template>
          @switch (headingLevel()) {
            @case (2) { <h2 class="title" [id]="titleId"><ng-container [ngTemplateOutlet]="titleText" /></h2> }
            @case (4) { <h4 class="title" [id]="titleId"><ng-container [ngTemplateOutlet]="titleText" /></h4> }
            @default { <h3 class="title" [id]="titleId"><ng-container [ngTemplateOutlet]="titleText" /></h3> }
          }
          <app-action-menu [label]="'Действия с колодой «' + deck().title + '»'" [items]="menuItems()" (chosen)="chosen($event)" />
        </div>
        <p class="description">{{ deck().description }}</p>
        <p class="tags">
          <span class="stamp"><span class="visually-hidden">Тема: </span>{{ deck().topic }}</span>
          <span class="stamp"><span class="visually-hidden">Язык: </span>{{ deck().language }}</span>
        </p>
        <app-author-chip [username]="deck().author.username" [avatarSrc]="deck().author.avatarSrc" />
        <p class="size">{{ size() }}</p>
        @if (deck().media.length > 0) {
          <ul class="media" role="list" aria-label="Что внутри">
            @for (kind of deck().media; track kind) {
              <li><app-glyph [name]="kind" /> {{ mediaLabel(kind) }}</li>
            }
          </ul>
        }
        @if (added() !== null || learning() !== null) {
          <dl class="audience">
            @if (added(); as count) { <div><dt>Добавили</dt> <dd><span aria-hidden="true">{{ count.short }}</span><span class="visually-hidden">{{ count.spoken }}</span></dd></div> }
            @if (learning(); as count) { <div><dt>Учат сейчас</dt> <dd><span aria-hidden="true">{{ count.short }}</span><span class="visually-hidden">{{ count.spoken }}</span></dd></div> }
          </dl>
        }
        @if (updated(); as text) { <p class="updated"><time [attr.datetime]="deck().updatedAt">{{ text }}</time></p> }
        @if (fallbackUrl(); as address) { <app-share-link-field [url]="address" [label]="'Ссылка на колоду «' + deck().title + '»'" /> }
      </article>
    `,
    styleUrl: './public-deck-card.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class PublicDeckCardComponent {
    readonly deck = input.required<PublicDeckCard>();
    /** The rows of the «⋯» menu; the screen decides which further actions exist. «Поделиться» is handled by the card. */
    readonly menuItems = input<readonly ActionMenuItem[]>([SHARE_MENU_ITEM]);
    /** The level of the title heading: 2 where the card sits under a page title, 3 under a shelf heading (default), 4 one level deeper. */
    readonly headingLevel = input<2 | 3 | 4>(3);
    /** The route of the deck page; without it the title is plain text. */
    readonly link = input<string | readonly string[] | null>(null);
    /** The `id` of a chosen menu row other than «Поделиться». */
    readonly action = output<string>();

    private readonly links = inject(ShareLinkService);
    protected readonly fallbackUrl = signal<string | null>(null);

    private readonly uid = `mn-deck-card-${nextCard++}`;
    protected readonly titleId = `${this.uid}-title`;
    protected readonly size = computed(() => sizeLine(this.deck().materialCount, this.deck().exerciseCount));
    protected readonly added = computed(() => audienceCount(this.deck().addedCount));
    protected readonly learning = computed(() => audienceCount(this.deck().learningNowCount));
    protected readonly updated = computed(() => updatedLabel(this.deck().updatedAt));

    protected async chosen(id: string): Promise<void> {
        if (id !== SHARE_MENU_ITEM.id) {
            this.action.emit(id);
            return;
        }
        this.fallbackUrl.set(null);
        const outcome = await this.links.share(this.deck().shareUrl, this.deck().title);
        if (outcome === 'failed') this.fallbackUrl.set(this.deck().shareUrl);
    }

    protected mediaLabel(kind: keyof typeof MEDIA_LABELS): string { return MEDIA_LABELS[kind]; }
}
