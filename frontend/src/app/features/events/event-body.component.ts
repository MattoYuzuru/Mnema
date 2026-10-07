import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { eventMarkdown } from './event-markdown';

@Component({
    selector: 'app-event-body',
    imports: [NgTemplateOutlet],
    template: `
      <ng-template #inline let-parts>
        @for (part of parts; track $index) {
          @switch (part.kind) {
            @case ('link') { <a [href]="part.href" rel="noopener noreferrer">{{ part.text }}</a> }
            @case ('strong') { <strong>{{ part.text }}</strong> }
            @case ('em') { <em>{{ part.text }}</em> }
            @case ('code') { <code>{{ part.text }}</code> }
            @default { {{ part.text }} }
          }
        }
      </ng-template>
      @for (block of blocks(); track $index) {
        @switch (block.kind) {
          @case ('heading') { <h3><ng-container *ngTemplateOutlet="inline; context: { $implicit: block.lines[0] }" /></h3> }
          @case ('list') { <ul>@for (line of block.lines; track $index) { <li><ng-container *ngTemplateOutlet="inline; context: { $implicit: line }" /></li> }</ul> }
          @case ('quote') { <blockquote><ng-container *ngTemplateOutlet="inline; context: { $implicit: block.lines[0] }" /></blockquote> }
          @case ('code') { <pre><code>{{ block.lines[0][0].text }}</code></pre> }
          @default { <p><ng-container *ngTemplateOutlet="inline; context: { $implicit: block.lines[0] }" /></p> }
        }
      }
    `,
    styles: [`
      :host { display: block; overflow-wrap: anywhere; line-height: 1.7; color: var(--mn-body); }
      p { white-space: pre-wrap; margin: 0 0 var(--mn-space-4); }
      h3 { font-family: var(--mn-font-body); font-size: 1.05rem; margin: var(--mn-space-5) 0 var(--mn-space-2); }
      ul { padding-left: var(--mn-space-5); margin-block: var(--mn-space-3) var(--mn-space-4); }
      blockquote { margin-inline: 0; padding-left: var(--mn-space-4); border-left: 2px solid var(--mn-rule); }
      code { font-size: .9em; white-space: pre-wrap; }
      pre { padding: var(--mn-space-4); background: var(--mn-soft); overflow: auto; }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class EventBodyComponent {
    readonly markdown = input.required<string>();
    protected readonly blocks = computed(() => eventMarkdown(this.markdown()));
}
