import {
    ChangeDetectionStrategy, Component, ElementRef, Injector, afterNextRender, computed, effect, inject, signal, untracked, viewChild
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { catchError, forkJoin, map, of } from 'rxjs';

import { PageTransition } from '../../shared/page-transition.service';
import { CAPABILITIES_UNAVAILABLE, CapabilitiesApiService, LearningCapabilities } from '../authoring/capabilities-api.service';
import { ItemEditorPageComponent } from '../authoring/item-editor-page.component';
import { OwnDecksApiService } from '../own-decks/own-decks-api.service';
import { GenerationComposerComponent } from './generation-composer.component';
import { SessionDetail } from './generation.models';

/**
 * `/decks/:deckId/materials/new`: the composer where AI generation is available, the plain editor where it is not. The page
 * waits for the capability answer before it shows either, because the editor creates a draft as soon as it opens. A failed
 * read counts as «not available» (fail closed). `?write=1` is the user's own choice of the editor («Или откройте пустой
 * редактор»), `?draft=<id>` opens that draft in it (a material handed over from the Workshop); with the capability off
 * the editor opens with a calm note, never a dead composer.
 */
@Component({
    selector: 'app-new-material-page',
    imports: [GenerationComposerComponent, ItemEditorPageComponent],
    template: `
      @switch (mode()) {
        @case ('loading') {
          <section class="page-loading"><h1 tabindex="-1">Новый материал</h1><p role="status">Готовим страницу…</p></section>
        }
        @case ('composer') {
          <app-generation-composer [deckId]="deckId()" [deckTitle]="deckTitle()" [capabilities]="composerCapabilities()"
            (created)="open($event)" />
        }
        @case ('editor') {
          @if (showNote()) {
            <p class="ai-note" role="status">Помощник Мнема сейчас недоступен. Материал можно написать самому: редактор уже открыт.</p>
          }
          <app-item-editor-page />
        }
      }
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; }
      .page-loading { inline-size: min(56rem, 100%); margin-inline: auto; padding: clamp(1.5rem, 6vw, 5rem) clamp(1.125rem, 5vw, 4rem); color: var(--mn-body); }
      .page-loading h1 { margin: 0 0 1rem; color: var(--mn-ink); font: 500 clamp(2rem, 5vw, 3.5rem)/1.12 var(--mn-font-display, Georgia, serif); }
      .ai-note { inline-size: min(76rem, 100%); margin: 1rem auto 0; padding: .75rem clamp(1.125rem, 5vw, 4rem); box-sizing: border-box; border-inline-start: 4px solid var(--mn-ink); background: var(--mn-soft); color: var(--mn-body); }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class NewMaterialPageComponent {
    readonly deckTitle = signal<string | null>(null);
    /** `null` until the server has answered. */
    readonly capabilities = signal<LearningCapabilities | null>(null);
    protected readonly composerCapabilities = computed(() => this.capabilities() ?? CAPABILITIES_UNAVAILABLE);
    private readonly editor = viewChild(ItemEditorPageComponent);

    private readonly route = inject(ActivatedRoute);
    private readonly transition = inject(PageTransition);
    private readonly capabilityApi = inject(CapabilitiesApiService);
    private readonly decks = inject(OwnDecksApiService);
    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
    private readonly injector = inject(Injector);
    private readonly params = toSignal(this.route.paramMap, { initialValue: this.route.snapshot.paramMap });
    readonly deckId = computed(() => this.params().get('deckId') ?? '');
    private readonly query = toSignal(this.route.queryParamMap, { initialValue: this.route.snapshot.queryParamMap });
    private readonly writeChosen = computed(() => this.query().has('write') || this.query().has('draft'));

    protected readonly mode = computed<'loading' | 'composer' | 'editor'>(() => {
        const capabilities = this.capabilities();
        if (capabilities === null) return 'loading';
        return capabilities.aiGeneration.available && !this.writeChosen() ? 'composer' : 'editor';
    });
    protected readonly showNote = computed(() => this.capabilities() !== null && !this.capabilities()!.aiGeneration.available
        && !this.writeChosen());

    constructor() {
        // The shell focused the heading of the loading page; when that heading is replaced the focus would fall to the page.
        // Give it to the new heading, but only if the user has not put it somewhere else meanwhile.
        effect(() => {
            if (this.mode() === 'loading') return;
            afterNextRender(() => {
                const active = this.host.nativeElement.ownerDocument.activeElement;
                if (active === null || active === this.host.nativeElement.ownerDocument.body) this.host.nativeElement.querySelector<HTMLElement>('h1')?.focus();
            }, { injector: this.injector });
        });
        // The deck can change under a reused page: start over for it.
        effect(onCleanup => {
            const deckId = this.deckId();
            untracked(() => { this.capabilities.set(null); this.deckTitle.set(null); });
            const request = forkJoin({
                capabilities: this.capabilityApi.read().pipe(catchError(() => of(CAPABILITIES_UNAVAILABLE))),
                title: this.decks.detail(deckId).pipe(map(deck => deck.metadata.title), catchError(() => of(null)))
            }).subscribe(result => {
                this.deckTitle.set(result.title);
                this.capabilities.set(result.capabilities);
            });
            onCleanup(() => request.unsubscribe());
        });
    }

    /** The composer made a session: the page becomes its Workshop. */
    open(session: SessionDetail): void {
        void this.transition.navigate(['/decks', this.deckId(), 'workshop', session.sessionId]);
    }

    confirmLeave(): boolean {
        return this.editor()?.confirmLeave() ?? true;
    }
}

export function canLeaveNewMaterial(component: NewMaterialPageComponent): boolean {
    return component.confirmLeave();
}
