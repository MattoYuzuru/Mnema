import { HttpErrorResponse } from '@angular/common/http';
import { DestroyRef, Injectable, computed, effect, inject, signal, untracked } from '@angular/core';
import { Subscription } from 'rxjs';
import { AuthService } from '../../auth.service';
import { COMMUNITY_LAUNCH } from '../../shared/community-launch';
import { InterestProfile } from './recommendations.models';
import { InterestCommand, RecommendationsApiService } from './recommendations-api.service';

/** Personal state exists for one restored account only; switching accounts cancels every outstanding command/read. */
@Injectable({ providedIn: 'root' })
export class InterestStore {
    private readonly auth = inject(AuthService);
    private readonly api = inject(RecommendationsApiService);
    private readonly launch = inject(COMMUNITY_LAUNCH);
    private readonly destroyRef = inject(DestroyRef);
    readonly account = computed(() => this.launch && this.auth.status() === 'authenticated' ? this.auth.user()?.accountId ?? null : null);
    private readonly state = signal<InterestProfile | null>(null);
    readonly profile = computed(() => this.owner() === this.account() ? this.state() : null);
    readonly busy = signal(false);
    readonly ready = signal(false);
    readonly notice = signal('');
    readonly error = signal('');
    readonly forYou = computed(() => this.account() !== null && this.profile()?.forYouEnabled === true);
    readonly events = computed(() => this.account() !== null && this.profile()?.eventsEnabled === true);
    readonly onboarding = computed(() => this.account() !== null && this.profile()?.onboardingComplete === false);
    private readonly owner = signal<string | null>(null);
    private sequence = 0;
    private request: Subscription | null = null;
    private pending: { readonly command: InterestCommand; readonly version: string; readonly reset: boolean } | null = null;
    constructor() {
        effect(() => { const account = this.account(); untracked(() => this.switchAccount(account)); });
        this.destroyRef.onDestroy(() => this.request?.unsubscribe());
    }
    private switchAccount(account: string | null): void {
        if (account === this.owner()) return;
        this.owner.set(account);
        this.sequence++;
        this.request?.unsubscribe();
        this.pending = null;
        this.state.set(null);
        this.ready.set(account === null);
        this.busy.set(false);
        this.notice.set(''); this.error.set('');
        if (account !== null) this.reload();
    }
    /** Called after Auth.restore, without waiting for an effect's next render. */
    ensure(): void { this.switchAccount(this.account()); }
    disableEvents(): void { this.state.update(profile => profile === null ? null : { ...profile, eventsEnabled: false }); }
    disableForYou(): void { this.state.update(profile => profile === null ? null : { ...profile, forYouEnabled: false }); }
    reload(): void {
        if (this.account() === null) return;
        const sequence = ++this.sequence;
        const account = this.account();
        this.request?.unsubscribe();
        this.busy.set(true);
        this.error.set('');
        this.request = this.api.profile().subscribe({
            next: profile => { if (sequence !== this.sequence || account !== this.account()) return; this.state.set(profile); this.ready.set(true); this.busy.set(false); },
            error: (error: unknown) => {
                if (sequence !== this.sequence || account !== this.account()) return;
                this.busy.set(false); this.ready.set(true);
                if (error instanceof HttpErrorResponse && error.status === 404) { this.state.set(null); this.notice.set(''); return; }
                this.error.set('Не удалось загрузить интересы. Повторите ещё раз.');
            }
        });
    }
    save(topicIds: readonly string[], reset = false): void {
        const profile = this.profile();
        if (this.busy() || profile === null || this.account() === null || topicIds.length > 20) return;
        // An unknown outcome keeps the exact command and If-Match; editing is available only after it is resolved.
        this.pending ??= { command: reset ? { commandId: crypto.randomUUID() } : { commandId: crypto.randomUUID(), topicIds: [...topicIds] }, version: profile.rowVersion, reset };
        const pending = this.pending;
        const sequence = ++this.sequence;
        const account = this.account();
        this.busy.set(true); this.notice.set(''); this.error.set('');
        this.request = this.api.change(pending.command, pending.version, pending.reset).subscribe({
            next: next => {
                if (sequence !== this.sequence || account !== this.account()) return;
                this.pending = null; this.state.set(next); this.busy.set(false);
                this.notice.set(pending.reset ? 'Интересы сброшены. Новые действия будут формировать профиль заново.' : 'Интересы сохранены.');
            },
            error: (error: unknown) => {
                if (sequence !== this.sequence || account !== this.account()) return;
                this.busy.set(false);
                if (error instanceof HttpErrorResponse && error.status === 412) {
                    this.pending = null; this.notice.set('Интересы изменились в другом окне. Показываем актуальный профиль.'); this.reload();
                } else if (error instanceof HttpErrorResponse && error.status === 404) { this.pending = null; this.state.set(null); this.notice.set(''); }
                else { this.error.set('Не удалось подтвердить сохранение. Повторите тот же запрос.'); }
            }
        });
    }
    retry(): void { if (this.pending !== null) this.save([]); else this.reload(); }
    readonly retrying = computed(() => this.error() !== '' && this.profile() !== null);
}
