import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';

/**
 * The author mark of a public deck: a 20 px photo and `@login`. The photo is optional (the owner decides whether to show
 * it); without one, or when it cannot be loaded, a neutral disc with the first letter takes its place. Without a login
 * there is nothing to show and the chip renders nothing, so a hidden or withdrawn profile leaves no empty shell. The
 * login is real text, so the photo is decorative; the full `@login` is also the (non-interactive) `title`, for the case it is cut with an ellipsis.
 */
@Component({
    selector: 'app-author-chip',
    template: `
      @if (username(); as name) {
        <span class="chip">
          @if (photo(); as src) {
            <img class="face" [src]="src" alt="" width="20" height="20" loading="lazy" decoding="async"
                 referrerpolicy="no-referrer" draggable="false" (error)="failed.set(src)" />
          } @else {
            <span class="face placeholder" aria-hidden="true">{{ initial() }}</span>
          }
          <span class="login" [attr.title]="'@' + name">&#64;{{ name }}</span>
        </span>
      }
    `,
    styles: [`
      :host { display: inline-flex; max-inline-size: 100%; min-inline-size: 0; }
      :host(:empty) { display: none; }
      .chip { display: inline-flex; align-items: center; gap: .4rem; max-inline-size: 100%; min-inline-size: 0; color: var(--mn-ink); font-size: .9rem; line-height: 1.2; }
      .face { flex: none; inline-size: 1.25rem; block-size: 1.25rem; border-radius: 50%; object-fit: cover; }
      .placeholder { display: grid; place-items: center; border: 1px solid var(--mn-field-border); background: var(--mn-soft); font-size: .7rem; font-weight: 600; line-height: 1; }
      .login { min-inline-size: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
      @media (forced-colors: active) { .face { border: 1px solid CanvasText; } }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class AuthorChipComponent {
    /** The login without `@`; `null` hides the chip. */
    readonly username = input<string | null>(null);
    /** The public photo address; `null` shows the placeholder. */
    readonly avatarSrc = input<string | null>(null);

    /** The address that already failed to load: a new address is tried again. */
    protected readonly failed = signal<string | null>(null);
    protected readonly photo = computed(() => {
        const src = this.avatarSrc();
        return src !== null && src !== this.failed() ? src : null;
    });
    protected readonly initial = computed(() => (this.username() ?? '').charAt(0).toUpperCase());
}
