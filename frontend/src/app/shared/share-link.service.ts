import { DOCUMENT } from '@angular/common';
import { Injectable, inject } from '@angular/core';

import { ToastService } from '../core/notifications/toast.service';
import type { ActionMenuItem } from './action-menu.component';

/** What «Поделиться» did: the system sheet took the link, it was copied, the learner closed the sheet, or nothing worked. */
export type ShareOutcome = 'shared' | 'copied' | 'cancelled' | 'failed';

/** The menu row of the action («Поделиться» in every «⋯» menu of a public deck). */
export const SHARE_MENU_ITEM: ActionMenuItem = { id: 'share', label: 'Поделиться', glyph: 'share' };

/**
 * The «Поделиться» action. On a touch device whose browser offers the Web Share API the system share sheet opens with
 * the title and the link only: there is no prepared message text. Everywhere else (a computer, or a browser without
 * the API) the link goes to the clipboard and the «Ссылка скопирована» toast confirms it. When the clipboard is closed
 * to us the learner gets an error toast and the outcome `failed`, so the caller can show the link in a selectable field
 * ({@link ShareLinkFieldComponent}); there is no `execCommand` fallback.
 *
 * Call it directly from the click handler: `navigator.share` and the clipboard need the transient user activation, so
 * nothing may be awaited before this call.
 */
@Injectable({ providedIn: 'root' })
export class ShareLinkService {
    private readonly view = inject(DOCUMENT).defaultView;
    private readonly toasts = inject(ToastService);

    async share(url: string, title: string): Promise<ShareOutcome> {
        if (this.systemSheetAvailable(url)) {
            try {
                await this.view!.navigator.share({ url, title });
                return 'shared';
            } catch (error) {
                // The learner closed the sheet: that is an answer, not a failure.
                if ((error as { name?: unknown } | null)?.name === 'AbortError') return 'cancelled';
                // Anything else (no activation, a refused payload): the link can still be copied.
            }
        }
        return this.copy(url);
    }

    private systemSheetAvailable(url: string): boolean {
        const view = this.view;
        if (view === null || typeof view.navigator.share !== 'function' || !view.matchMedia('(pointer: coarse)').matches) return false;
        return typeof view.navigator.canShare !== 'function' || view.navigator.canShare({ url });
    }

    private async copy(url: string): Promise<ShareOutcome> {
        try {
            const clipboard = this.view?.navigator.clipboard;
            if (clipboard === undefined || typeof clipboard.writeText !== 'function') throw new Error('clipboard unavailable');
            await clipboard.writeText(url);
            this.toasts.echo('Ссылка скопирована');
            return 'copied';
        } catch {
            this.toasts.echoError('Не удалось скопировать ссылку. Скопируйте её из поля.');
            return 'failed';
        }
    }
}
