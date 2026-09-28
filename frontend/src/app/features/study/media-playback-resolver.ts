import { InjectionToken } from '@angular/core';
import { Observable, of } from 'rxjs';

/** #239 provides owner-authorized, short-lived playback URLs without coupling Study to its HTTP route. */
export interface MediaPlaybackResolver {
    resolve(assetId: string): Observable<string | null>;
}

export const MEDIA_PLAYBACK_RESOLVER = new InjectionToken<MediaPlaybackResolver>('MediaPlaybackResolver', {
    providedIn: 'root', factory: () => ({ resolve: () => of(null) })
});
