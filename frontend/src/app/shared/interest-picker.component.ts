import { DOCUMENT } from '@angular/common';
import { ChangeDetectionStrategy, Component, ElementRef, Injector, afterNextRender, computed, inject, input, output, signal } from '@angular/core';
import { CommunityTopic } from '../features/community/community.models';
import { InterestTopicGroup, interestTopicGroups, relatedInterestTopics } from './interest-topics';

let nextPicker = 0;
@Component({
    selector: 'app-interest-picker',
    template: `
      <fieldset [disabled]="busy()" [attr.aria-describedby]="hintId">
        <legend tabindex="-1">Что вы хотите изучать в Мнеме?</legend>
        <p class="hint intro" [id]="hintId">Найдите то, что вам интересно: начните с направления, затем выберите темы. Мы подберём колоды, а интересы можно будет изменить в профиле.</p>
        @if (selectedTopics().length > 0) {
          <div class="chosen">
            <p class="eyebrow">Ваши темы</p>
            <div class="topics" role="group" aria-label="Выбранные темы">
              @for (topic of selectedTopics(); track topic.id) {
                <button type="button" class="button topic" aria-pressed="true" [attr.data-interest-topic]="topic.id" (click)="toggle(topic.id, $event)">
                  <span>{{ topic.name }}</span><span class="topic-mark" aria-hidden="true">×</span>
                </button>
              }
            </div>
          </div>
        }
        <div class="topics categories" role="group" aria-label="Направления обучения">
          @for (group of groups(); track group.id) {
            @if (group.topics.length === 1 && group.topics[0].parentId === null) {
              <button type="button" class="button topic" [attr.aria-pressed]="selected().includes(group.id)" [attr.data-interest-topic]="group.id"
                [disabled]="full() && !selected().includes(group.id)" [attr.aria-describedby]="full() ? limitId : null" (click)="toggle(group.id, $event)">
                {{ group.name }}<span class="topic-mark" aria-hidden="true">{{ selected().includes(group.id) ? '✓' : '+' }}</span>
              </button>
            } @else {
              <button type="button" class="button category" [attr.aria-expanded]="activeGroup() === group.id" [attr.aria-controls]="branchId"
                [attr.data-interest-category]="group.id" (click)="openGroup(group)">{{ group.name }}<span aria-hidden="true">↗</span></button>
            }
          }
        </div>
        <div class="branches" [id]="branchId">
          @if (currentGroup(); as group) {
            <p class="branch-heading">{{ anchor() ? 'Рядом с «' + anchor()!.name + '»' : group.name }}</p>
            <div class="topics" role="group" [attr.aria-label]="anchor() ? 'Связанные темы' : group.name">
              @for (topic of suggestions(); track topic.id) {
                <button type="button" class="button topic" [attr.aria-pressed]="selected().includes(topic.id)" [attr.data-interest-topic]="topic.id"
                  [disabled]="full() && !selected().includes(topic.id)" [attr.aria-describedby]="full() ? limitId : null" (click)="toggle(topic.id, $event)">
                  <span>{{ topic.name }}</span><span class="topic-mark" aria-hidden="true">{{ selected().includes(topic.id) ? '✓' : '+' }}</span>
                </button>
              }
            </div>
            @if (canExploreMore()) { <button type="button" class="button quiet small more" (click)="exploreMore()">Ещё темы: {{ group.name }}</button> }
          }
        </div>
        <p class="hint selection-status" role="status" [id]="limitId">{{ full() ? 'Вы выбрали 20 тем. Уберите одну из выбранных, чтобы добавить другую.' : 'Можно выбрать до 20 тем. Нажмите на выбранную тему, чтобы убрать её.' }}</p>
      </fieldset>
      <div class="actions">
        <button type="button" class="button primary" [disabled]="busy()" (click)="save.emit(selected())">{{ busy() ? 'Сохраняем…' : 'Сохранить интересы' }}</button>
        @if (skippable()) { <button type="button" class="button quiet" [disabled]="busy()" (click)="save.emit([])">Пока пропустить</button> }
      </div>
    `,
    styleUrl: './interest-picker.component.css',
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
    private readonly uid = `interest-${nextPicker++}`;
    protected readonly hintId = `${this.uid}-hint`;
    protected readonly limitId = `${this.uid}-limit`;
    protected readonly branchId = `${this.uid}-branches`;
    protected readonly groups = computed(() => interestTopicGroups(this.topics()));
    protected readonly activeGroup = signal<string | null>(null);
    private readonly anchorId = signal<string | null>(null);
    private readonly offset = signal(0);
    protected readonly anchor = computed(() => this.topics().find(topic => topic.id === this.anchorId()) ?? null);
    protected readonly currentGroup = computed(() => this.groups().find(group => group.id === this.activeGroup()) ?? null);
    protected readonly selectedTopics = computed(() => this.selected().flatMap(id => this.topics().find(topic => topic.id === id) ?? []));
    protected readonly suggestions = computed(() => {
        const anchor = this.anchor();
        if (anchor !== null) return relatedInterestTopics(this.topics(), anchor.id, this.selected());
        const group = this.currentGroup();
        if (group === null) return [];
        const topics = group.topics;
        return [...topics.slice(this.offset()), ...topics.slice(0, this.offset())].slice(0, 5);
    });
    protected readonly canExploreMore = computed(() => (this.currentGroup()?.topics.length ?? 0) > 5 || this.anchor() !== null);
    protected readonly full = computed(() => this.selected().length >= 20);

    protected openGroup(group: InterestTopicGroup): void {
        this.activeGroup.set(this.activeGroup() === group.id ? null : group.id);
        this.anchorId.set(null);
        this.offset.set(0);
    }
    protected exploreMore(): void {
        const group = this.currentGroup();
        if (group === null) return;
        this.offset.set(this.anchor() === null ? (this.offset() + 5) % group.topics.length : 0);
        this.anchorId.set(null);
    }
    protected toggle(id: string, event: Event): void {
        if (this.busy()) return;
        const selected = this.selected().includes(id);
        if (!selected && this.full()) return;
        const topic = this.topics().find(topic => topic.id === id);
        if (topic === undefined) return;
        const target = event.currentTarget;
        const wasFocused = this.document.activeElement === target;
        if (!selected) {
            this.activeGroup.set(topic.parentId);
            this.anchorId.set(topic.parentId === null ? null : id);
        }
        this.changed.emit(selected ? this.selected().filter(topic => topic !== id) : [...this.selected(), id]);
        if (wasFocused) afterNextRender(() => {
            if (this.document.activeElement !== this.document.body && this.document.activeElement !== target) return;
            const button = [...this.host.nativeElement.querySelectorAll<HTMLButtonElement>('button[data-interest-topic]')].find(item => item.dataset['interestTopic'] === id);
            (button ?? this.host.nativeElement.querySelector<HTMLElement>('button[data-interest-category]') ?? this.host.nativeElement.querySelector<HTMLElement>('legend'))?.focus();
        }, { injector: this.injector });
    }
}
