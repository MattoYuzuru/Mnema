import { Injectable, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { QuietZone } from '../../core/notifications/quiet-zone';
import { PromoApiService } from './promo-api.service';
import { PopupEvent, PromoCampaign } from './promo.models';

/** Written once per browser session, by the first answer of the server: the popup is asked about and shown at most once per session. */
export const POPUP_SESSION_KEY = 'mnema.promo-popup.session';

/**
 * When the promo popup may appear, and what happens to it. A screen asks at a natural breakpoint (the end of a Study session, the
 * deck list once loaded) with `request()`; nothing else ever does, so the popup cannot come up over a task, an editor or the Workshop.
 * It is shown only when the server says this account is eligible (an enabled campaign, outside the cooldown of a dismissal, never
 * after a decline or a purchase), only if the learner has no task open when the answer arrives, and only once per browser session (sessionStorage). The
 * host (`app-promo-popup-host`) renders `campaign()`. The outcomes are sent to the server, which keeps them per account.
 */
@Injectable({ providedIn: 'root' })
export class PromoPopupService {
    private readonly api = inject(PromoApiService);
    private readonly router = inject(Router);
    private readonly quiet = inject(QuietZone);
    private readonly visible = signal<PromoCampaign | null>(null);
    /** The campaign being shown now, or null. */
    readonly campaign = this.visible.asReadonly();
    /** Counts requests and closings: an answer that arrives after the learner has moved on is dropped. */
    private epoch = 0;
    private asking = false;

    /** A natural breakpoint: show the popup if this session has not been asked, the learner has no task open and the server allows it. */
    async request(): Promise<void> {
        if (this.asking || this.visible() !== null || this.decided()) return;
        this.asking = true;
        const epoch = ++this.epoch;
        try {
            const campaign = await firstValueFrom(this.api.popup());
            if (campaign === null) { this.remember(); return; }
            // The learner moved on, or opened a task meanwhile: nothing is shown and the session keeps its one chance.
            if (epoch !== this.epoch || this.quiet.active()) return;
            this.remember();
            this.visible.set(campaign);
            this.send(campaign, 'SHOWN');
        } catch {
            // A failure to ask is not a reason to interrupt: nothing is shown, and the next breakpoint may ask again.
        } finally {
            this.asking = false;
        }
    }

    /** The primary action: closes, stays quiet for the cooldown and opens the plans (with the campaign's code to type, never applied). */
    accept(): void {
        const campaign = this.visible();
        if (campaign === null) return;
        this.finish(campaign, 'DISMISSED');
        void this.router.navigate(['/plans'], { queryParams: campaign.code === null ? {} : { promo: campaign.code } });
    }

    /** «Не сейчас», «×» or Esc. */
    dismiss(): void {
        const campaign = this.visible();
        if (campaign !== null) this.finish(campaign, 'DISMISSED');
    }

    /** «Больше не показывать». */
    decline(): void {
        const campaign = this.visible();
        if (campaign !== null) this.finish(campaign, 'DECLINED');
    }

    /** The learner left the page: the popup goes away without an answer (it counts as shown, and the session has had its one). */
    close(): void {
        this.epoch++;
        this.visible.set(null);
    }

    private finish(campaign: PromoCampaign, event: PopupEvent): void {
        this.close();
        this.send(campaign, event);
    }

    private send(campaign: PromoCampaign, event: PopupEvent): void {
        this.api.popupEvent(campaign.id, event).subscribe({ error: () => undefined });
    }

    private decided(): boolean {
        try { return sessionStorage.getItem(POPUP_SESSION_KEY) !== null; } catch { return false; }
    }

    private remember(): void {
        try { sessionStorage.setItem(POPUP_SESSION_KEY, '1'); } catch { /* storage blocked: the server's cooldown still holds */ }
    }
}
