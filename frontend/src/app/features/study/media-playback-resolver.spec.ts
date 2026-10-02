import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';

import { MediaPlaybackApi } from '../../content/rendering/media-playback.api';
import { MEDIA_PLAYBACK_RESOLVER } from './media-playback-resolver';

describe('Study media playback resolver', () => {
    it('uses the owner-authorized READY source and withholds pending storage URLs', async () => {
        const assetId = '00000000-0000-4000-8000-000000000001';
        const source = { url: 'https://storage.example/signed', expiresAt: '2030-01-01T00:00:00Z',
            mimeType: 'audio/mp4' };
        const api = {
            read: vi.fn().mockName("MediaPlaybackApi.read")
        };
        api.read.mockReturnValueOnce(Promise.resolve({ assetId, state: 'READY', playback: source, poster: null, download: null })).mockReturnValueOnce(Promise.resolve({ assetId, state: 'PROCESSING', playback: null, poster: null, download: null }));
        TestBed.configureTestingModule({ providers: [{ provide: MediaPlaybackApi, useValue: api }] });
        const resolver = TestBed.inject(MEDIA_PLAYBACK_RESOLVER);

        expect(await firstValueFrom(resolver.resolve(assetId))).toEqual(source);
        expect(await firstValueFrom(resolver.resolve(assetId))).toBeNull();
        expect(api.read).toHaveBeenCalledTimes(2);
        expect(api.read).toHaveBeenCalledWith(assetId);
    });
});
