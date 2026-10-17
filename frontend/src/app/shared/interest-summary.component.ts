import { ChangeDetectionStrategy, Component, ElementRef, Injector, afterNextRender, effect, inject, input, output, signal } from '@angular/core';
import { CommunityTopic } from '../features/community/community.models';
/** Readable, private interest summary with an inline confirmation for the meaningful reset. */
@Component({
    selector: 'app-interest-summary',
    template: `
      <p>Выбраны вами: {{ names(selected()) || 'пока нет' }}.</p>
      <p class="hint">По вашим действиям: {{ names(inferred()) || 'пока нет' }}.</p>
      <button type="button" class="button" [disabled]="busy()" (click)="edit.emit()">Изменить интересы</button>
      <button type="button" class="button quiet" [disabled]="busy()" (click)="confirming.set(true)">Сбросить интересы</button>
      @if (confirming()) {
        <div class="notice"><p>Сбросить выбранные темы и историю, по которой подбираются колоды? Новые действия будут формировать профиль заново.</p>
          <button type="button" class="button" [disabled]="busy()" (click)="resetRequested.emit()">Да, сбросить интересы</button>
          <button type="button" class="button quiet" [disabled]="busy()" (click)="cancel()">Отмена</button></div>
      }
    `,
    styles: `:host{display:block}button{margin:4px 8px 4px 0}.notice{margin-top:16px}`,
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class InterestSummaryComponent {
    readonly selected = input.required<readonly CommunityTopic[]>();
    readonly inferred = input.required<readonly CommunityTopic[]>();
    readonly busy = input(false);
    readonly edit = output<void>();
    readonly resetRequested = output<void>();
    protected readonly confirming = signal(false);
    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
    private readonly injector = inject(Injector);
    constructor() {
        effect(() => { this.selected(); this.inferred(); this.confirming.set(false); });
    }
    protected names(topics: readonly CommunityTopic[]): string { return topics.map(topic => topic.name).join(', '); }
    protected cancel(): void {
        this.confirming.set(false);
        afterNextRender(() => this.host.nativeElement.querySelectorAll<HTMLButtonElement>('button')[1]?.focus(), { injector: this.injector });
    }
}
