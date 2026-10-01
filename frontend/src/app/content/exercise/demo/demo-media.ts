import { Observable, of } from 'rxjs';

import { MediaPlaybackResolver } from '../../../features/study/media-playback-resolver';
import { SignedMediaSource } from '../../rendering/media-playback.api';

/**
 * Bundled, original demo media (src/assets/demo): two sine tones generated for this project (262 Hz and 880 Hz)
 * and two plotted wave drawings. The ids are fixed demo UUIDs that exist only in the demo fixtures: they are never
 * stored, never uploaded and never sent to the media API.
 */
export const DEMO_ASSETS = {
    toneLow: 'd3000000-0000-4000-8000-000000000001',
    toneHigh: 'd3000000-0000-4000-8000-000000000002',
    waveSparse: 'd3000000-0000-4000-8000-000000000003',
    waveDense: 'd3000000-0000-4000-8000-000000000004'
} as const;

const FAR_FUTURE = '2999-01-01T00:00:00Z';
const SOURCES: Readonly<Record<string, SignedMediaSource>> = {
    [DEMO_ASSETS.toneLow]: { url: '/assets/demo/tone-low.mp3', expiresAt: FAR_FUTURE, mimeType: 'audio/mpeg' },
    [DEMO_ASSETS.toneHigh]: { url: '/assets/demo/tone-high.mp3', expiresAt: FAR_FUTURE, mimeType: 'audio/mpeg' },
    [DEMO_ASSETS.waveSparse]: { url: '/assets/demo/wave-sparse.svg', expiresAt: FAR_FUTURE, mimeType: 'image/svg+xml' },
    [DEMO_ASSETS.waveDense]: { url: '/assets/demo/wave-dense.svg', expiresAt: FAR_FUTURE, mimeType: 'image/svg+xml' }
};

export function isDemoAsset(assetId: string): boolean { return Object.hasOwn(SOURCES, assetId); }

/**
 * Plays demo assets from the bundle and hands every other asset to the real resolver, so author media keep the
 * owner-authorized playback path and a demo id can never reach the media API.
 */
export function demoAwareResolver(real: MediaPlaybackResolver): MediaPlaybackResolver {
    return {
        resolve(assetId: string): Observable<SignedMediaSource | null> {
            return isDemoAsset(assetId) ? of(SOURCES[assetId]) : real.resolve(assetId);
        }
    };
}
