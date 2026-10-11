import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Injector, afterNextRender, effect, inject, input, output, signal, untracked, viewChild } from '@angular/core';
import { Subscription } from 'rxjs';

import { MediaPlaybackApi } from '../../content/rendering/media-playback.api';
import { NativeMediaSurfaceComponent } from '../../content/rendering/native-media-surface.component';
import { PublicDeckApiService } from './public-deck-api.service';
import { PublicDeckFailure, PublicMaterialDocument, publicFailureOf } from './public-deck.models';
import { readFailureText } from './public-deck.text';
import { PublicMediaPlaybackApi } from './public-media-playback.api';

type Phase = 'loading' | 'ready' | 'missing' | 'error';

/**
 * One material of a public deck, drawn by the same native renderer as Browse: read-only, no node ids, no Workshop overlays.
 * Its media is resolved through the public media route ({@link PublicMediaPlaybackApi}); until that route answers, a file
 * shows the renderer's «unavailable» state instead of a broken image. A material that is not in the published manifest is a
 * calm «not found» with the way back to the list.
 */
@Component({
    selector: 'app-public-material',
    imports: [NativeMediaSurfaceComponent],
    providers: [PublicMediaPlaybackApi, { provide: MediaPlaybackApi, useExisting: PublicMediaPlaybackApi }],
    template: `
      @switch (phase()) {
        @case ('loading') { <p class="hint" role="status">Загружаем материал…</p> }
        @case ('missing') {
          <h2 #heading id="public-material-heading" class="position" tabindex="-1">Материал не найден</h2>
          <div class="notice" role="status"><p>Такого материала в этой колоде нет. Возможно, автор его убрал.</p></div>
        }
        @case ('error') {
          <h2 #heading id="public-material-heading" class="position" tabindex="-1">Не удалось загрузить материал</h2>
          <div class="notice error" role="alert">
            <p>{{ failureText() }}</p>
            <button class="button" type="button" (click)="load()">Повторить</button>
          </div>
        }
        @default {
          @if (material(); as current) {
            <h2 #heading id="public-material-heading" class="position" tabindex="-1">Материал {{ current.ordinal + 1 }}@if (total() > 0) { из {{ total() }} }</h2>
            <article class="paper-surface" aria-labelledby="public-material-heading">
              <app-native-media-surface [document]="current.document" audience="public" [headingOffset]="1" />
            </article>
          }
        }
      }
    `,
    styles: [`:host { display: block; min-inline-size: 0; } .position { margin: 1.5rem 0 .75rem; font-size: clamp(1.4rem, 3vw, 1.9rem); line-height: 1.2; } .position:focus-visible { outline: none; } .notice { margin-block: 1rem; } .notice p:last-of-type { margin-block-end: .75rem; }`],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class PublicMaterialComponent {
    readonly code = input.required<string>();
    readonly memberKey = input.required<string>();
    /** How many materials the deck has, for «Материал 3 из 12». */
    readonly total = input(0);
    /** Access was withdrawn while the material was being read: the page asks for the summary again. */
    readonly gone = output<PublicDeckFailure>();

    protected readonly phase = signal<Phase>('loading');
    protected readonly material = signal<PublicMaterialDocument | null>(null);
    private readonly failure = signal<PublicDeckFailure | null>(null);
    private readonly api = inject(PublicDeckApiService);
    private readonly media = inject(PublicMediaPlaybackApi);
    private readonly heading = viewChild<ElementRef<HTMLElement>>('heading');
    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
    private readonly injector = inject(Injector);
    private subscription: Subscription | null = null;
    private sequence = 0;

    constructor() {
        inject(DestroyRef).onDestroy(() => this.subscription?.unsubscribe());
        effect(() => {
            this.code();
            this.memberKey();
            untracked(() => this.load());
        });
        // The heading of the material takes the focus once it is there (a row or a «Следующий» link was just used and is gone or
        // moved on), unless the reader has already moved the focus somewhere else on the page.
        effect(() => {
            if (this.phase() === 'loading') return;
            untracked(() => afterNextRender(() => this.focusHeading(), { injector: this.injector }));
        });
    }

    private focusHeading(): void {
        const element = this.host.nativeElement;
        const active = element.ownerDocument.activeElement;
        const page = element.closest('app-public-deck-page') ?? element;
        if (active !== null && active !== element.ownerDocument.body && !page.contains(active)) return;
        this.heading()?.nativeElement.focus();
    }

    protected load(): void {
        const sequence = ++this.sequence;
        this.subscription?.unsubscribe();
        this.media.code = this.code();
        this.phase.set('loading');
        this.material.set(null);
        this.subscription = this.api.material(this.code(), this.memberKey()).subscribe({
            next: material => {
                if (sequence !== this.sequence) return;
                this.material.set(material);
                this.phase.set('ready');
            },
            error: (error: unknown) => {
                if (sequence !== this.sequence) return;
                const failure = publicFailureOf(error);
                this.failure.set(failure);
                if (failure.kind === 'invite-only') { this.gone.emit(failure); return; }
                this.phase.set(failure.kind === 'not-found' ? 'missing' : 'error');
            }
        });
    }

    protected failureText(): string { return readFailureText(this.failure(), 'материал'); }
}
