import { ChangeDetectionStrategy, Component, ElementRef, computed, inject, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';

import { NotificationCenter } from './notification-center';
import { NotificationGlyphComponent } from './notification-glyph.component';
import { plural } from './notification-presenter';

let nextBell = 0;
const UNREAD: readonly [string, string, string] = ['непрочитанное', 'непрочитанных', 'непрочитанных'];
const TIME = new Intl.DateTimeFormat('ru', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });

/**
 * The bell of the app shell and the «Входящие» panel. The button names the exact unread count (the badge text is only
 * a visual summary and hidden from assistive technology); the panel is a native `popover="auto"` list of links, not a
 * menu: light dismiss and Esc come from the platform. Opening it moves the read watermark.
 */
@Component({
    selector: 'app-notification-bell',
    imports: [RouterLink, NotificationGlyphComponent],
    templateUrl: './notification-bell.component.html',
    styleUrl: './notification-bell.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class NotificationBellComponent {
    protected readonly center = inject(NotificationCenter);
    private readonly panel = viewChild<ElementRef<HTMLElement>>('panel');

    private readonly uid = `mn-inbox-${nextBell++}`;
    protected readonly panelId = `${this.uid}-panel`;
    protected readonly titleId = `${this.uid}-title`;
    protected readonly anchorName = `--${this.uid}`;
    protected readonly label = computed(() => {
        const count = this.center.unreadCount();
        return count === 0 ? 'Уведомления' : `Уведомления, ${count} ${plural(count, UNREAD)}`;
    });

    protected onToggle(event: Event): void {
        if ((event as ToggleEvent).newState === 'open') this.center.open(); else this.center.close();
    }

    /** A link inside a popover does not close it; leaving for another page should. */
    protected followed(): void {
        const panel = this.panel()?.nativeElement;
        if (panel !== undefined && typeof panel.hidePopover === 'function') {
            try { panel.hidePopover(); } catch { /* already hidden */ }
        }
    }

    protected textId(notificationId: string): string { return `${this.uid}-text-${notificationId}`; }
    protected isNew(seq: string): boolean { return BigInt(seq) > BigInt(this.center.freshAfter()); }
    protected time(iso: string): string { return TIME.format(Date.parse(iso)); }
}
