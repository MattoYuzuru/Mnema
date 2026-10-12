import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { ActionMenuItem, ActionMenuComponent } from './action-menu.component';
import { audienceCount } from './public-deck-card';
import { SHARE_MENU_ITEM, ShareLinkService } from './share-link.service';
import { ShareLinkFieldComponent } from './share-link-field.component';

/** What Identity publishes about an author who has agreed to a public profile. Everything but the login is optional. */
export interface AuthorProfile {
    readonly accountId: string;
    readonly profileUsername: string;
    readonly displayName: string | null;
    readonly bio: string | null;
    readonly avatarSrc: string | null;
}

const COMPLAIN_ITEM: ActionMenuItem = { id: 'complain', label: 'Пожаловаться' };

/**
 * The head of an author's page `/u/{username}`: a 64 px photo (without one, or when it cannot be loaded, the neutral disc with the first
 * letter of the author chip), the `h1` (the display name, else `@login`; `headingLevel` lowers it where the header is not the page title), the `@login` line when the heading is a name, the bio, «Учат сейчас: N» and the
 * «⋯» menu. The bio is plain text: line breaks are kept, nothing is parsed as HTML and no link is made of it. «Учат сейчас» is drawn from 10 up
 * and rounded like on the cards. The menu has «Поделиться» (the address of the profile, `profileUrl`; the link field shows when copying
 * failed) and «Пожаловаться» (the output `complain`; the page opens the complaint dialog). For one's own page (`isSelf`) the menu has no
 * complaint, and a notice says «Так ваш профиль видят другие» with a link to the profile settings.
 */
@Component({
    selector: 'app-author-header',
    imports: [RouterLink, ActionMenuComponent, ShareLinkFieldComponent],
    template: `
      <header class="author">
        <div class="identity">
          @if (photo(); as src) {
            <img class="face" [src]="src" alt="" width="64" height="64" decoding="async" referrerpolicy="no-referrer" draggable="false" (error)="failed.set(src)" />
          } @else {
            <span class="face placeholder" aria-hidden="true">{{ initial() }}</span>
          }
          <div class="names">
            @switch (headingLevel()) {
              @case (2) { <h2 class="name">{{ heading() }}</h2> }
              @case (3) { <h3 class="name">{{ heading() }}</h3> }
              @case (4) { <h4 class="name">{{ heading() }}</h4> }
              @default { <h1 class="name">{{ heading() }}</h1> }
            }
            @if (author().displayName) { <p class="login">&#64;{{ author().profileUsername }}</p> }
          </div>
          <app-action-menu class="menu" [label]="'Действия с профилем «' + heading() + '»'" [items]="menuItems()" (chosen)="chosen($event)" />
        </div>
        @if (author().bio; as text) { <p class="bio">{{ text }}</p> }
        @if (learning(); as count) {
          <p class="learning">Учат сейчас: <span aria-hidden="true">{{ count.short }}</span><span class="visually-hidden">{{ count.spoken }}</span></p>
        }
        @if (isSelf()) {
          <div class="notice self">
            <p>Так ваш профиль видят другие</p>
            <a class="button small" routerLink="/profile">Настроить профиль</a>
          </div>
        }
        @if (fallbackUrl(); as address) { <app-share-link-field [url]="address" label="Ссылка на профиль" /> }
      </header>
    `,
    styleUrl: './author-header.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class AuthorHeaderComponent {
    readonly author = input.required<AuthorProfile>();
    /** The public address of the profile, absolute, for «Поделиться». */
    readonly profileUrl = input.required<string>();
    /** The sum of «Учат сейчас» over the author's decks; the line is drawn from 10 up. */
    readonly learningNow = input(0);
    /** The level of the name: 1 on the author's page (the page title), deeper only where the header sits inside another page (the styleguide). */
    readonly headingLevel = input<1 | 2 | 3 | 4>(1);
    /** The reader is looking at their own profile. */
    readonly isSelf = input(false);
    /** «Пожаловаться» was chosen. */
    readonly complain = output<void>();

    private readonly links = inject(ShareLinkService);
    /** The address that already failed to load: a new address is tried again. */
    protected readonly failed = signal<string | null>(null);
    protected readonly fallbackUrl = signal<string | null>(null);

    protected readonly heading = computed(() => this.author().displayName ?? `@${this.author().profileUsername}`);
    protected readonly initial = computed(() => (this.author().displayName ?? this.author().profileUsername).charAt(0).toUpperCase());
    protected readonly photo = computed(() => {
        const src = this.author().avatarSrc;
        return src !== null && src !== this.failed() ? src : null;
    });
    protected readonly learning = computed(() => audienceCount(this.learningNow()));
    protected readonly menuItems = computed<readonly ActionMenuItem[]>(() => this.isSelf() ? [SHARE_MENU_ITEM] : [SHARE_MENU_ITEM, COMPLAIN_ITEM]);

    protected async chosen(id: string): Promise<void> {
        if (id === COMPLAIN_ITEM.id) { this.complain.emit(); return; }
        if (id !== SHARE_MENU_ITEM.id) return;
        this.fallbackUrl.set(null);
        const outcome = await this.links.share(this.profileUrl(), this.heading());
        if (outcome === 'failed') this.fallbackUrl.set(this.profileUrl());
    }
}
