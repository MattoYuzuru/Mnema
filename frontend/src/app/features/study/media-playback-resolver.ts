import { InjectionToken, inject } from '@angular/core';
import { Observable, defer, map } from 'rxjs';

import { MediaPlaybackApi, SignedMediaSource } from '../../content/rendering/media-playback.api';

/** #239 provides owner-authorized, short-lived playback URLs without coupling Study to its HTTP route. */
export interface MediaPlaybackResolver {
    resolve(assetId: string): Observable<SignedMediaSource | null>;
}

export const MEDIA_PLAYBACK_RESOLVER = new InjectionToken<MediaPlaybackResolver>('MediaPlaybackResolver', {
    providedIn: 'root', factory: () => {
        const api = inject(MediaPlaybackApi);
        return { resolve: (assetId: string) => defer(() => api.read(assetId))
            .pipe(map(view => view.state === 'READY' ? view.playback : null)) };
    }
});
