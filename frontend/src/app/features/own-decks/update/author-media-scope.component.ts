import { ChangeDetectionStrategy, Component, effect, inject, input } from '@angular/core';

import { MediaPlaybackApi } from '../../../content/rendering/media-playback.api';
import { PublicMediaPlaybackApi } from '../../public-deck/public-media-playback.api';

/**
 * The scope of the author's side of a comparison: everything projected into it asks for its media files through the public media route
 * of the source deck (`GET /api/public/decks/{code}/media/{assetId}`, derived variants only), by the code the copy was made by
 * (`fork.upstreamCode`). It provides {@link PublicMediaPlaybackApi} in place of the owner's {@link MediaPlaybackApi}, exactly like the
 * public view of a deck does for a material, so the renderer's media surface needs no second code path. The reader's own side stays
 * outside this scope and keeps the owner's route (the copy's own files, by the COPY grant).
 */
@Component({
    selector: 'app-author-media-scope',
    providers: [PublicMediaPlaybackApi, { provide: MediaPlaybackApi, useExisting: PublicMediaPlaybackApi }],
    template: `<ng-content />`,
    styles: [`:host { display: block; min-inline-size: 0; }`],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class AuthorMediaScopeComponent {
    /** The public code of the source deck. */
    readonly code = input.required<string>();

    constructor() {
        const media = inject(PublicMediaPlaybackApi);
        effect(() => { media.code = this.code(); });
    }
}
