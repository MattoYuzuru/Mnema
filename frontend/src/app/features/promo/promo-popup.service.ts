import { Injectable, effect, inject, signal, untracked } from '@angular/core';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { QuietZone } from '../../core/notifications/quiet-zone';
import { AuthService } from '../../auth.service';
import { PromoApiService } from './promo-api.service';
import { PopupEvent, PromoCampaign } from './promo.models';

/** Stores the account whose browser session has already decided on a popup; it contains no campaign or promo code. */
export const POPUP_SESSION_KEY = 'mnema.promo-popup.session';
/** Account-scoped preference receipt: campaign id/event only, never the campaign body or offered code. */
export const POPUP_PREFERENCE_KEY = 'mnema.promo-popup.preference.';

interface PopupPreference {
    readonly campaignId: string;
    readonly event: 'DISMISSED' | 'DECLINED';
    readonly confirmed: boolean;
}

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
    private readonly auth = inject(AuthService);
    private readonly visible = signal<PromoCampaign | null>(null);
    /** The campaign being shown now, or null. */
    readonly campaign = this.visible.asReadonly();
    /** Counts requests and closings: an answer that arrives after the learner has moved on is dropped. */
    private epoch = 0;
    private asking = false;
    private owner: string | null = null;
    /** Storage may be blocked; the live tab still honours its once-per-session decision. */
    private remembered = false;
    private readonly preferences = new Map<string, PopupPreference>();
    private readonly confirming = new Set<PopupPreference>();

    constructor() {
        this.synchronize();
        effect(() => {
            this.auth.status();
            this.auth.user();
            untracked(() => this.synchronize());
        });
    }

    /** A natural breakpoint: show the popup if this session has not been asked, the learner has no task open and the server allows it. */
    async request(): Promise<void> {
        const owner = this.synchronize();
        if (owner === null || this.quiet.active() || this.asking || this.visible() !== null) return;
        const preference = this.preference(owner);
        if (preference === null && this.decided(owner)) return;
        this.asking = true;
        const epoch = ++this.epoch;
        try {
            // A failed preference write must not turn into another offer. Retry only this account's idempotent event.
            if (preference !== null) { await this.confirmPreference(owner, preference); return; }
            const campaign = await firstValueFrom(this.api.popup());
            this.synchronize();
            // The learner moved on, or opened a task meanwhile: nothing is shown and the session keeps its one chance.
            if (epoch !== this.epoch || this.quiet.active()) return;
            this.remember(owner);
            if (campaign === null) return;
            this.visible.set(campaign);
            this.send(campaign, 'SHOWN');
        } catch {
            // A failure to ask is not a reason to interrupt: nothing is shown, and the next breakpoint may ask again.
        } finally {
            if (epoch === this.epoch) this.asking = false;
        }
    }

    /** The primary action: closes, stays quiet for the cooldown and opens the plans (with the campaign's code to type, never applied). */
    accept(): void {
        this.synchronize();
        const campaign = this.visible();
        if (campaign === null) return;
        this.finish(campaign, 'DISMISSED');
        void this.router.navigate(['/plans'], { queryParams: campaign.code === null ? {} : { promo: campaign.code } });
    }

    /** «Не сейчас», «×» or Esc. */
    dismiss(): void {
        this.synchronize();
        const campaign = this.visible();
        if (campaign !== null) this.finish(campaign, 'DISMISSED');
    }

    /** «Больше не показывать». */
    decline(): void {
        this.synchronize();
        const campaign = this.visible();
        if (campaign !== null) this.finish(campaign, 'DECLINED');
    }

    /** The learner left the page: the popup goes away without an answer (it counts as shown, and the session has had its one). */
    close(): void {
        this.epoch++;
        this.asking = false;
        this.visible.set(null);
    }

    private finish(campaign: PromoCampaign, event: PopupEvent): void {
        const owner = this.owner;
        this.close();
        if (owner === null || event === 'SHOWN') return;
        const preference: PopupPreference = { campaignId: campaign.id, event, confirmed: false };
        this.persistPreference(owner, preference);
        void this.confirmPreference(owner, preference);
    }

    private send(campaign: PromoCampaign, event: PopupEvent): void {
        this.api.popupEvent(campaign.id, event).subscribe({ error: () => undefined });
    }

    private decided(owner: string): boolean {
        if (this.remembered) return true;
        try { return sessionStorage.getItem(POPUP_SESSION_KEY) === owner; } catch { return false; }
    }

    private remember(owner: string): void {
        this.remembered = true;
        try { sessionStorage.setItem(POPUP_SESSION_KEY, owner); } catch { /* The in-memory decision still holds for this tab. */ }
    }

    private preference(owner: string): PopupPreference | null {
        const cached = this.preferences.get(owner);
        if (cached !== undefined) return cached;
        try {
            const raw = localStorage.getItem(POPUP_PREFERENCE_KEY + owner);
            if (raw === null || raw.length > 240) return null;
            const value: unknown = JSON.parse(raw);
            if (typeof value !== 'object' || value === null || Array.isArray(value)) return null;
            const body = value as Record<string, unknown>;
            if (Object.keys(body).length !== 3 || typeof body['campaignId'] !== 'string' || body['campaignId'].length === 0
                || body['campaignId'].length > 60 || (body['event'] !== 'DISMISSED' && body['event'] !== 'DECLINED')
                || typeof body['confirmed'] !== 'boolean') return null;
            const preference: PopupPreference = { campaignId: body['campaignId'], event: body['event'], confirmed: body['confirmed'] };
            this.preferences.set(owner, preference);
            return preference;
        } catch { return null; }
    }

    private persistPreference(owner: string, preference: PopupPreference): void {
        this.preferences.set(owner, preference);
        try { localStorage.setItem(POPUP_PREFERENCE_KEY + owner, JSON.stringify(preference)); } catch { /* Kept in memory for this tab. */ }
    }

    private async confirmPreference(owner: string, preference: PopupPreference): Promise<void> {
        if (preference.confirmed || this.confirming.has(preference)) return;
        this.confirming.add(preference);
        try {
            const recorded = await firstValueFrom(this.api.popupEvent(preference.campaignId, preference.event));
            if (!recorded || this.preferences.get(owner) !== preference) return;
            if (preference.event === 'DECLINED') {
                // The explicit opt-out also survives blocked session storage and a later browser-session change.
                this.persistPreference(owner, { ...preference, confirmed: true });
            } else {
                this.preferences.delete(owner);
                try { localStorage.removeItem(POPUP_PREFERENCE_KEY + owner); } catch { /* The in-memory decision has ended. */ }
            }
        } catch { /* A later natural breakpoint retries; until then this account sees no further offer. */ }
        finally { this.confirming.delete(preference); }
    }

    /** Account changes invalidate outstanding answers before the new account can ask. A reload preserves its own marker. */
    private synchronize(): string | null {
        const owner = this.auth.status() === 'authenticated' ? this.auth.user()?.accountId ?? null : null;
        if (owner !== this.owner) {
            const previous = this.owner;
            this.owner = owner;
            this.close();
            this.remembered = false;
            if (previous !== null) {
                try { sessionStorage.removeItem(POPUP_SESSION_KEY); } catch { /* Nothing private is retained in memory. */ }
            }
        }
        return owner;
    }
}
