import { ChangeDetectionStrategy, Component, ElementRef, Injector, afterNextRender, effect, inject, input, linkedSignal, signal, untracked } from '@angular/core';
import { AuthService } from '../../auth.service';
import { InterestSummaryComponent } from '../../shared/interest-summary.component';
import { InterestPickerComponent } from '../../shared/interest-picker.component';
import { InterestStore } from './interest-store.service';
@Component({
    selector: 'app-interest-settings',
    imports: [InterestPickerComponent, InterestSummaryComponent],
    template: `
      <p class="hint" role="status" aria-atomic="true">{{ store.notice() }}</p>
      @if (store.profile(); as profile) {
        @if (!onboarding() || store.onboarding()) {
          <section class="interest-settings" aria-label="Интересы в Сообществе" data-testid="interest-settings">
            <h2 tabindex="-1">{{ onboarding() ? 'Подберём колоды для вас' : 'Интересы в Сообществе' }}</h2>
            @if (onboarding() || editing()) {
              <app-interest-picker [topics]="profile.availableTopics" [selected]="selected()" [busy]="store.busy() || store.retrying()" [skippable]="onboarding()"
                (changed)="selected.set($event)" (save)="save($event)" />
              @if (!onboarding()) { <button type="button" class="button quiet" [disabled]="store.busy() || store.retrying()" (click)="cancelEdit()">Отмена</button> }
            } @else {
              <app-interest-summary [selected]="profile.selectedTopics" [inferred]="profile.inferredTopics" [busy]="store.busy() || store.retrying()"
                (edit)="edit()" (resetRequested)="reset()" />
            }
          </section>
        }
      }
      @if (store.error()) {
        <p class="field-error" role="alert">{{ store.error() }}</p>
        <button type="button" class="button" [disabled]="store.busy()" (click)="retry()">Повторить</button>
      }
    `,
    styles: `:host{display:block}.interest-settings{padding-block:8px}h2{font-family:var(--mn-font-display);color:var(--mn-ink)}button{margin:4px 8px 4px 0}.notice{margin-top:16px}`,
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class InterestSettingsComponent {
    readonly onboarding = input(false);
    protected readonly store = inject(InterestStore);
    private readonly auth = inject(AuthService);
    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
    private readonly injector = inject(Injector);
    protected readonly selected = linkedSignal<readonly string[]>(() => this.store.profile()?.selectedTopics.map(topic => topic.id) ?? []);
    protected readonly editing = signal(false);
    private readonly retryFocus = signal(false);
    constructor() {
        const cachedProfile = this.store.profile();
        const cachedAccount = this.store.account();
        void this.auth.restore().then(() => {
            this.store.ensure();
            if (!this.onboarding()) this.store.refreshKnownProfile(cachedProfile, cachedAccount);
        });
        effect(() => {
            const notice = this.store.notice();
            if (!this.store.busy() && (notice !== '' || this.retryFocus())) untracked(() => { this.retryFocus.set(false); this.editing.set(false); this.focus(); });
        });
    }
    protected edit(): void {
        if (this.store.busy() || this.store.retrying()) return;
        this.editing.set(true);
        afterNextRender(() => {
            (this.host.nativeElement.querySelector<HTMLElement>('app-interest-picker button:not(:disabled)') ?? this.host.nativeElement.querySelector<HTMLElement>('app-interest-picker legend'))?.focus();
        }, { injector: this.injector });
    }
    protected cancelEdit(): void {
        if (this.store.busy() || this.store.retrying()) return;
        this.selected.set(this.store.profile()?.selectedTopics.map(topic => topic.id) ?? []);
        this.editing.set(false);
        afterNextRender(() => this.host.nativeElement.querySelector<HTMLElement>('app-interest-summary button')?.focus(), { injector: this.injector });
    }
    protected save(ids: readonly string[]): void { this.store.save(ids); }
    protected reset(): void { this.store.save([], true); }
    protected retry(): void { this.retryFocus.set(true); this.store.retry(); }
    protected focus(): void { afterNextRender(() => (this.host.nativeElement.querySelector<HTMLElement>('h2') ?? this.host.nativeElement.closest('app-community-page')?.querySelector<HTMLElement>('h1'))?.focus(), { injector: this.injector }); }
}
