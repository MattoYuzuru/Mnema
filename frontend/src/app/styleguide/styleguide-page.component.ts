import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, ViewEncapsulation, afterNextRender, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { SgControlsComponent } from './sg-controls.component';
import { SgFoundationsComponent } from './sg-foundations.component';
import { SgScreenComponent } from './sg-screen.component';
import { SgSurfacesComponent } from './sg-surfaces.component';
import { SECTION_GROUPS } from './styleguide.data';

/**
 * The living styleguide of Mnema, `/styleguide`, registered only in development builds (see `app.routes.ts`). Every example is a
 * real component or class of the app; the page adds navigation and the «Спокойное движение» preview. No auth, no backend.
 */
@Component({
    selector: 'app-styleguide-page',
    encapsulation: ViewEncapsulation.None,
    imports: [RouterLink, SgFoundationsComponent, SgControlsComponent, SgSurfacesComponent, SgScreenComponent],
    templateUrl: './styleguide-page.component.html',
    styleUrl: './styleguide.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class StyleguidePageComponent {
    protected readonly groups = SECTION_GROUPS;
    protected readonly calm = signal(false);
    protected readonly active = signal('principles');
    protected readonly systemCalm = signal(false);

    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
    private readonly destroyRef = inject(DestroyRef);

    constructor() {
        afterNextRender(() => {
            this.systemCalm.set(window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false);
            this.observeSections();
        });
    }

    protected setCalm(event: Event): void { this.calm.set((event.target as HTMLInputElement).checked); }

    /** Marks the section nearest the top as the current one in the sidebar (`aria-current="location"`). */
    private observeSections(): void {
        if (typeof IntersectionObserver === 'undefined') return;
        const sections = Array.from(this.host.nativeElement.querySelectorAll<HTMLElement>('.sg-section'));
        const visible = new Set<string>();
        const observer = new IntersectionObserver(entries => {
            for (const entry of entries) {
                if (entry.isIntersecting) visible.add(entry.target.id); else visible.delete(entry.target.id);
            }
            const first = sections.find(section => visible.has(section.id));
            if (first) this.active.set(first.id);
        }, { rootMargin: '0px 0px -65% 0px' });
        sections.forEach(section => observer.observe(section));
        this.destroyRef.onDestroy(() => observer.disconnect());
    }
}
