import {
    ChangeDetectionStrategy, Component, ElementRef, Injector, OnInit, afterNextRender, computed, inject, input, model, output, signal, viewChild
} from '@angular/core';

import { GlyphComponent } from './glyph.component';
import { TypewriterHintComponent } from './typewriter-hint.component';

let nextSearch = 0;
/** The opening of the book is played once per page load: the first book that is drawn open takes it, later ones (and re-renders) stay still. */
let openingPlayed = false;

/** Lets the next open book play its opening again (a styleguide button, and the specs). */
export function rearmBookOpening(): void { openingPlayed = false; }

/**
 * The search of the community catalogue, drawn as a book. Semantics: `<search aria-label="Поиск колод">` (the one search landmark) › a plain `<form>` › a visible
 * `<label>` «Найти колоду» › `<input type="search" name="q" maxlength="100">` › the submit button «Найти». Enter or the
 * button submits. An empty submit is allowed (it means «browse everything»); `(searched)` carries the trimmed text. The
 * placeholder is not used: the typewriter hint over the empty field is a decorative `aria-hidden` layer, so a screen reader hears
 * the label and nothing else.
 *
 * `mode="open"` is the community page: the book lies open (two leaves and a spine, drawn in CSS around the field) and
 * the field is visible. Its opening (at most 600 ms, a clip from the spine outwards) plays once per page load and not for reduced motion.
 * `mode="closed"` is the header entry: a button with the book-and-lens glyph (`aria-expanded`, `aria-controls`)
 * that reveals the same form inline and moves the focus into the field. Esc folds it back and returns the focus to the button; an
 * empty field also folds when the focus leaves the search. `expanded` is a model, so the header may fold it after it has navigated.
 */
@Component({
    selector: 'app-book-search',
    imports: [GlyphComponent, TypewriterHintComponent],
    host: { '[attr.data-mode]': 'mode()' },
    template: `
      <search class="book" aria-label="Поиск колод" [class.is-book]="mode() === 'open'" (keydown.escape)="escape($event)" (focusout)="leaving($event)">
        @if (mode() === 'closed') {
          <button #entry type="button" class="button entry" aria-label="Открыть поиск колод" [attr.aria-expanded]="expanded()"
            [attr.aria-controls]="formId" (click)="toggle()"><app-glyph name="book-search" /></button>
        }
        <form class="form" [id]="formId" [hidden]="mode() === 'closed' && !expanded()" (submit)="submit($event)">
          <div class="spread" [class.is-opening]="opening()">
            @if (mode() === 'open') {
              <span class="leaf cover" aria-hidden="true"><app-glyph name="book-open" /></span>
              <span class="spine" aria-hidden="true"></span>
            }
            <div class="leaf page">
              <label class="eyebrow" [for]="inputId">Найти колоду</label>
              <div class="row">
                <div class="box">
                  <input #field [id]="inputId" type="search" name="q" maxlength="100" autocomplete="off" enterkeyhint="search"
                    [value]="value()" (input)="typed($event)" (focus)="focused.set(true)" (blur)="focused.set(false)" />
                  @if (hint()) { <app-typewriter-hint [suppressed]="hintSuppressed()" /> }
                </div>
                <button type="submit" class="button primary submit" aria-label="Найти"><app-glyph name="book-search" /></button>
              </div>
            </div>
          </div>
        </form>
      </search>
    `,
    styleUrl: './book-search.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class BookSearchComponent implements OnInit {
    /** `open`: the book on the community page. `closed`: the entry button of the header. */
    readonly mode = input<'open' | 'closed'>('open');
    /** The text in the field. */
    readonly value = model('');
    /** Closed mode: the form is shown. */
    readonly expanded = model(false);
    /** The typewriter hint over the empty field. */
    readonly hint = input(true);
    /** The trimmed text, on Enter or a click on the book; empty means «browse». */
    readonly searched = output<string>();

    private readonly entry = viewChild<ElementRef<HTMLButtonElement>>('entry');
    private readonly field = viewChild<ElementRef<HTMLInputElement>>('field');
    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
    private readonly injector = inject(Injector);

    private readonly uid = `mn-book-search-${nextSearch++}`;
    protected readonly formId = `${this.uid}-form`;
    protected readonly inputId = `${this.uid}-input`;
    protected readonly focused = signal(false);
    protected readonly hintSuppressed = computed(() => this.focused() || this.value() !== '' || (this.mode() === 'closed' && !this.expanded()));

    /**
     * Decided once, when the book is created (the inputs are known in `ngOnInit`, before the first render): the first book that is drawn
     * open takes the opening, later ones and re-renders stay still. A book that is switched to `open` later does not play it.
     */
    protected readonly opening = signal(false);

    ngOnInit(): void {
        if (this.mode() !== 'open') return;
        this.opening.set(!openingPlayed);
        openingPlayed = true;
    }

    protected typed(event: Event): void { this.value.set((event.target as HTMLInputElement).value); }

    protected submit(event: Event): void {
        event.preventDefault();
        this.searched.emit(this.value().trim());
    }

    protected toggle(): void {
        if (this.expanded()) { this.fold(true); return; }
        this.expanded.set(true);
        afterNextRender(() => this.field()?.nativeElement.focus(), { injector: this.injector });
    }

    protected escape(event: Event): void {
        if (this.mode() !== 'closed' || !this.expanded()) return;
        // A search field clears itself on Esc in some browsers; here the key only folds the book.
        event.preventDefault();
        event.stopPropagation();
        this.fold(true);
    }

    /** An empty field folds when the focus moves out of the search to another element. */
    protected leaving(event: FocusEvent): void {
        if (this.mode() !== 'closed' || !this.expanded() || this.value() !== '') return;
        const next = event.relatedTarget;
        if (next instanceof Node && !this.host.nativeElement.contains(next)) this.fold(false);
    }

    private fold(refocus: boolean): void {
        this.expanded.set(false);
        if (refocus) this.entry()?.nativeElement.focus();
    }
}
