import { ChangeDetectionStrategy, Component, ElementRef, computed, input, output, signal, viewChild } from '@angular/core';

/** Zoomable figure with native modal focus behavior and a static first frame for animated GIFs. */
@Component({
    selector: 'app-native-media-image',
    templateUrl: './native-media-image.component.html',
    styleUrl: './native-media-image.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class NativeMediaImageComponent {
    readonly source = input.required<string>();
    readonly poster = input<string | null>(null);
    readonly download = input.required<string>();
    readonly alt = input.required<string>();
    readonly animated = input(false);
    readonly sourceFailed = output<void>();
    readonly playing = signal(false);
    readonly zoom = signal(1);
    readonly visibleSource = computed(() => this.animated() && !this.playing() && this.poster()
        ? this.poster()! : this.source());
    private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');

    open(): void {
        this.zoom.set(1);
        this.dialog().nativeElement.showModal();
    }

    close(): void { this.dialog().nativeElement.close(); }

    changeZoom(delta: number): void {
        this.zoom.update(current => Math.max(0.5, Math.min(4, Math.round((current + delta) * 100) / 100)));
    }

    reset(): void { this.zoom.set(1); }

    toggleAnimation(): void { this.playing.update(current => !current); }
}
