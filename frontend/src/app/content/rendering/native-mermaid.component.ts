import { ChangeDetectionStrategy, Component, ElementRef, OnDestroy, effect, input, signal, viewChild } from '@angular/core';

/** Mermaid output stays inside an image document: user-authored SVG never enters Mnema's DOM. */
@Component({
    selector: 'app-native-mermaid',
    template: `
      <figure class="diagram">
        <div class="diagram-image">
          @if (imageUrl(); as url) {
            <img [src]="url" [alt]="title()" />
          } @else if (failed()) {
            <p role="status">Схему не удалось построить. Описание и исходный текст доступны ниже.</p>
          } @else {
            <p role="status">Строим схему…</p>
          }
        </div>
        <figcaption><strong>{{ title() }}</strong><p>{{ description() }}</p></figcaption>
        @if (imageUrl()) {
          <button #zoomButton class="diagram-zoom" type="button" (click)="openDiagram()">Увеличить схему</button>
        }
        <details><summary>Исходный текст Mermaid</summary><pre><code>{{ source() }}</code></pre></details>
      </figure>
      <dialog #diagramDialog class="diagram-dialog" [attr.aria-label]="'Схема крупным планом: ' + title()" (close)="returnFocus()">
        <button class="diagram-close" type="button" autofocus (click)="closeDiagram()">Закрыть схему</button>
        <div class="diagram-scroll">@if (imageUrl(); as url) { <img [src]="url" [alt]="title()" /> }</div>
        <p>{{ description() }}</p>
      </dialog>
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; margin-block: 1.5rem; }
      .diagram { min-inline-size: 0; margin: 0; border: 1px solid var(--mn-rule); border-block-start: 2px solid var(--mn-ink); background: var(--mn-sheet); }
      .diagram-image { display: grid; place-items: center; min-block-size: 8rem; padding: clamp(.75rem, 3vw, 1.5rem); }
      img { display: block; max-inline-size: 100%; block-size: auto; }
      figcaption { border-block-start: 1px solid var(--mn-rule); padding: .75rem 1rem; }
      figcaption strong { color: var(--mn-ink); }
      figcaption p { margin: .35rem 0 0; }
      details { border-block-start: 1px solid var(--mn-rule); padding: .5rem 1rem; }
      .diagram-zoom { min-block-size: var(--mn-touch-min, 2.75rem); margin: .5rem 1rem; border: 1px solid var(--mn-ink); padding: .5rem .8rem; background: var(--mn-sheet); color: var(--mn-ink); font: 600 .9rem var(--mn-font-body, system-ui, sans-serif); cursor: pointer; }
      .diagram-dialog { inline-size: min(94vw, 95rem); max-inline-size: none; max-block-size: 90vh; border: 1px solid var(--mn-ink); padding: 1rem; background: var(--mn-sheet); color: var(--mn-body); }
      .diagram-dialog::backdrop { background: rgb(30 20 50 / 70%); }
      .diagram-close { min-block-size: var(--mn-touch-min, 2.75rem); margin-block-end: .75rem; border: 1px solid var(--mn-ink); padding: .5rem .8rem; background: var(--mn-ink); color: var(--mn-on-ink); font: 600 .9rem var(--mn-font-body, system-ui, sans-serif); cursor: pointer; }
      .diagram-scroll { max-inline-size: 100%; max-block-size: 68vh; overflow: auto; }
      .diagram-scroll img { inline-size: max(100%, 70rem); max-inline-size: none; }
      .diagram-dialog p { margin: .75rem 0 0; }
      summary { min-block-size: var(--mn-touch-min, 2.75rem); color: var(--mn-ink); cursor: pointer; }
      pre { max-inline-size: 100%; overflow-x: auto; white-space: pre-wrap; overflow-wrap: anywhere; }
      :where(summary, button):focus-visible { outline: 3px solid var(--mn-focus, var(--mn-ink)); outline-offset: 3px; }
      @media (forced-colors: active) { .diagram, figcaption, details { border-color: CanvasText; } }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class NativeMermaidComponent implements OnDestroy {
    readonly source = input.required<string>();
    readonly title = input.required<string>();
    readonly description = input.required<string>();
    readonly imageUrl = signal<string | null>(null);
    readonly failed = signal(false);
    private readonly dialog = viewChild<ElementRef<HTMLDialogElement>>('diagramDialog');
    private readonly zoomButton = viewChild<ElementRef<HTMLButtonElement>>('zoomButton');

    private currentUrl: string | null = null;
    private generation = 0;
    private destroyed = false;
    private readonly sourceEffect = effect(() => {
        const source = this.source();
        void this.render(source, ++this.generation);
    });

    ngOnDestroy(): void {
        this.destroyed = true;
        this.generation += 1;
        this.releaseUrl();
    }

    openDiagram(): void {
        if (this.imageUrl() !== null) this.dialog()?.nativeElement.showModal();
    }

    closeDiagram(): void {
        this.dialog()?.nativeElement.close();
        this.returnFocus();
    }

    returnFocus(): void { this.zoomButton()?.nativeElement.focus(); }

    private async render(source: string, generation: number): Promise<void> {
        this.releaseUrl();
        this.imageUrl.set(null);
        this.failed.set(false);
        try {
            const { default: mermaid } = await import('mermaid');
            // SVG is rendered as an isolated image, so resolve theme tokens before rendering it.
            const theme = getComputedStyle(document.documentElement);
            const token = (name: string): string => theme.getPropertyValue(name).trim();
            mermaid.initialize({
                startOnLoad: false, securityLevel: 'strict', suppressErrorRendering: true,
                maxTextSize: 16_384, maxEdges: 200, theme: 'base',
                themeVariables: { background: token('--mn-sheet'), primaryColor: token('--mn-soft'),
                    primaryTextColor: token('--mn-ink'), primaryBorderColor: token('--mn-ink'),
                    lineColor: token('--mn-ink'), fontFamily: token('--mn-font-body') },
                flowchart: { htmlLabels: false }
            });
            const { svg } = await mermaid.render(`mnema-diagram-${crypto.randomUUID()}`, source);
            if (this.destroyed || generation !== this.generation) return;
            const url = URL.createObjectURL(new Blob([svg], { type: 'image/svg+xml' }));
            this.currentUrl = url;
            this.imageUrl.set(url);
        } catch {
            if (!this.destroyed && generation === this.generation) this.failed.set(true);
        }
    }

    private releaseUrl(): void {
        if (this.currentUrl !== null) URL.revokeObjectURL(this.currentUrl);
        this.currentUrl = null;
    }
}
