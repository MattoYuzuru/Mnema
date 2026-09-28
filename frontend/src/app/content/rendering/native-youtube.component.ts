import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';

import { youtubeVideoId } from '../youtube-video-id';

/** Consent-gated provider frame. Only a validated ID enters a fixed first-party-authored embed URL. */
@Component({
    selector: 'app-native-youtube',
    templateUrl: './native-youtube.component.html',
    styleUrl: './native-youtube.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class NativeYoutubeComponent {
    readonly videoId = input.required<string>();
    readonly title = input.required<string>();
    readonly transcript = input<string | null>(null);
    readonly enabled = signal(false);
    readonly validId = computed(() => youtubeVideoId(this.videoId()));
    readonly externalUrl = computed(() => this.validId() === null ? null
        : `https://www.youtube.com/watch?v=${this.validId()}`);
    private readonly sanitizer = inject(DomSanitizer);
    readonly embedUrl = computed<SafeResourceUrl | null>(() => {
        const id = this.validId();
        if (id === null) return null;
        // Angular requires a SafeResourceUrl for iframe src. Validation narrows input to exactly 11 URL-safe ID chars.
        return this.sanitizer.bypassSecurityTrustResourceUrl(`https://www.youtube-nocookie.com/embed/${id}`);
    });
}
