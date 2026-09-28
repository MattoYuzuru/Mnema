import { ChangeDetectionStrategy, Component, OnDestroy, effect, input, signal } from '@angular/core';

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
        <details><summary>Исходный текст Mermaid</summary><pre><code>{{ source() }}</code></pre></details>
      </figure>
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; margin-block: 1.5rem; }
      .diagram { min-inline-size: 0; margin: 0; border: 1px solid var(--line, #c9c0ce); background: #fbf8ef; }
      .diagram-image { display: grid; place-items: center; min-block-size: 8rem; padding: clamp(.75rem, 3vw, 1.5rem); }
      img { display: block; max-inline-size: 100%; block-size: auto; }
      figcaption { border-block-start: 1px solid var(--line, #c9c0ce); padding: .75rem 1rem; }
      figcaption strong { color: var(--ink, #281378); }
      figcaption p { margin: .35rem 0 0; }
      details { border-block-start: 1px solid var(--line, #c9c0ce); padding: .5rem 1rem; }
      summary { min-block-size: 2.75rem; color: var(--ink, #281378); cursor: pointer; }
      pre { max-inline-size: 100%; overflow-x: auto; white-space: pre-wrap; overflow-wrap: anywhere; }
      :where(summary):focus-visible { outline: 3px solid var(--ink, #281378); outline-offset: 3px; }
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

    private async render(source: string, generation: number): Promise<void> {
        this.releaseUrl();
        this.imageUrl.set(null);
        this.failed.set(false);
        try {
            const { default: mermaid } = await import('mermaid');
            mermaid.initialize({
                startOnLoad: false, securityLevel: 'strict', suppressErrorRendering: true,
                maxTextSize: 16_384, maxEdges: 200, theme: 'base',
                themeVariables: { background: '#fbf8ef', primaryColor: '#e8e1ed',
                    primaryTextColor: '#281378', primaryBorderColor: '#281378', lineColor: '#281378',
                    fontFamily: 'system-ui, sans-serif' },
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
