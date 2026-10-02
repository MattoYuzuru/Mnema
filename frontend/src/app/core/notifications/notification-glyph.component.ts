import { ChangeDetectionStrategy, Component, input } from '@angular/core';

import { NotificationSeverity } from './notification.models';

/**
 * Decorative severity mark with a different silhouette per level (circle, triangle, octagon), so it never relies on
 * colour. The sentence next to it always names the outcome; the mark is hidden from assistive technology.
 */
@Component({
    selector: 'app-notification-glyph',
    template: `
      <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" focusable="false" fill="none"
           stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
        @switch (severity()) {
          @case ('ERROR') { <path d="M8.3 3h7.4L21 8.3v7.4L15.7 21H8.3L3 15.7V8.3z" /><path d="M12 7.5v5.5" /><circle cx="12" cy="16.4" r=".6" fill="currentColor" /> }
          @case ('WARNING') { <path d="M12 3.5 21.5 20h-19z" /><path d="M12 10v4.6" /><circle cx="12" cy="17.2" r=".6" fill="currentColor" /> }
          @default { <circle cx="12" cy="12" r="9" /><path d="M12 11v6" /><circle cx="12" cy="7.9" r=".7" fill="currentColor" /> }
        }
      </svg>
    `,
    styles: [`
      :host { display: inline-flex; flex: none; }
      :host([data-severity=ERROR]) { color: var(--mn-danger); }
      :host([data-severity=WARNING]) { color: var(--mn-caution); }
      :host([data-severity=INFO]) { color: var(--mn-ink); }
      @media (forced-colors: active) { :host { color: CanvasText; } }
    `],
    host: { '[attr.data-severity]': 'severity()' },
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class NotificationGlyphComponent {
    readonly severity = input.required<NotificationSeverity>();
}
