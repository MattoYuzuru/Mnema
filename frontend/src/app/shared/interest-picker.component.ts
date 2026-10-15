import { DOCUMENT } from '@angular/common';
import { ChangeDetectionStrategy, Component, ElementRef, Injector, afterNextRender, computed, inject, input, output } from '@angular/core';
import { CommunityTopic } from '../features/community/community.models';

let nextPicker = 0;
/** Optional topic selection; native checkboxes keep keyboard and screen-reader behavior. */
@Component({
    selector: 'app-interest-picker',
    template: `
      <fieldset [disabled]="busy()" [attr.aria-describedby]="hintId">
        <legend tabindex="-1">Что хотите изучать?</legend>
        <p class="hint" [id]="hintId">Выберите до 20 тем. Так Мнема предложит подходящие колоды. Можно пропустить и изменить выбор в профиле.</p>
        <div class="topics">
          @for (topic of initialTopics(); track topic.id) {
            <label class="check-row"><input type="checkbox" [attr.data-interest-topic]="topic.id" [checked]="selected().includes(topic.id)"
              [disabled]="!selected().includes(topic.id) && full()" (change)="toggle(topic.id, $event)" /><span>{{ topic.name }}</span></label>
          }
        </div>
        @if (otherTopics().length > 0) {
          <details><summary>Все темы ({{ topics().length }})</summary><div class="topics more-topics">
            @for (topic of otherTopics(); track topic.id) {
              <label class="check-row"><input type="checkbox" [attr.data-interest-topic]="topic.id" [checked]="selected().includes(topic.id)"
                [disabled]="!selected().includes(topic.id) && full()" (change)="toggle(topic.id, $event)" /><span>{{ topic.name }}</span></label>
            }
          </div></details>
        }
        <p class="hint">Выбрано {{ selected().length }} из 20</p>
      </fieldset>
      <div class="actions">
        <button type="button" class="button primary" [disabled]="busy()" (click)="save.emit(selected())">{{ busy() ? 'Сохраняем…' : 'Сохранить интересы' }}</button>
        @if (skippable()) { <button type="button" class="button quiet" [disabled]="busy()" (click)="save.emit([])">Пока пропустить</button> }
      </div>
    `,
    styles: `:host{display:block}fieldset{border:0;padding:0;margin:0;min-width:0}legend{font-weight:600}.topics,.actions{display:flex;flex-wrap:wrap;gap:var(--mn-space-3,12px)}.topics .check-row{border:1px solid var(--mn-rule);padding:8px 12px;max-width:100%;overflow-wrap:anywhere}.actions{margin-top:16px}.more-topics{margin-top:12px}summary{cursor:pointer;min-height:var(--mn-touch-min);display:list-item;padding:8px 0}`,
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class InterestPickerComponent {
    readonly topics = input.required<readonly CommunityTopic[]>();
    readonly selected = input.required<readonly string[]>();
    readonly busy = input(false);
    readonly skippable = input(false);
    readonly changed = output<readonly string[]>();
    readonly save = output<readonly string[]>();
    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
    private readonly document = inject(DOCUMENT);
    private readonly injector = inject(Injector);
    protected readonly hintId = `interest-hint-${nextPicker++}`;
    protected readonly initialTopics = computed(() => {
        const groups = new Map<string, CommunityTopic[]>();
        for (const topic of this.topics()) {
            const key = topic.parentId ?? topic.id;
            const group = groups.get(key) ?? [];
            group.push(topic); groups.set(key, group);
        }
        const first: CommunityTopic[] = [];
        for (let round = 0; round < 2; round++) for (const group of groups.values()) if (group[round] !== undefined && first.length < 12) first.push(group[round]);
        const chosen = new Set(first.map(topic => topic.id));
        return [...first, ...this.topics().filter(topic => this.selected().includes(topic.id) && !chosen.has(topic.id))];
    });
    protected readonly otherTopics = computed(() => {
        const initial = new Set(this.initialTopics().map(topic => topic.id));
        return this.topics().filter(topic => !initial.has(topic.id));
    });
    protected readonly full = computed(() => this.selected().length >= 20);
    protected toggle(id: string, event: Event): void {
        const checked = (event.target as HTMLInputElement).checked;
        const rest = this.selected().filter(topic => topic !== id);
        if (checked && rest.length >= 20) return;
        const wasFocused = this.document.activeElement === event.target;
        this.changed.emit(checked ? [...rest, id] : rest);
        if (wasFocused) afterNextRender(() => {
            if (this.document.activeElement !== this.document.body && this.document.activeElement !== event.target) return;
            const input = [...this.host.nativeElement.querySelectorAll<HTMLInputElement>('input')].find(box => box.dataset['interestTopic'] === id);
            const disclosure = input?.closest('details');
            if (disclosure !== null && disclosure !== undefined && !disclosure.open) disclosure.querySelector('summary')?.focus();
            else input?.focus();
        }, { injector: this.injector });
    }
}
