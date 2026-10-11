import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { MediaPlaybackApi, MediaPlaybackView, SignedMediaSource } from '../../content/rendering/media-playback.api';
import { appConfig } from '../../app.config';
import { PUBLIC_DECK_CODE } from './public-deck.models';

const STATES: readonly MediaPlaybackView['state'][] = ['PENDING_UPLOAD', 'VERIFYING', 'PROCESSING', 'READY', 'FAILED_RETRYABLE', 'REJECTED', 'DELETED'];

/**
 * Media of a public deck as the renderer's media surface asks for it. The route is Share/9
 * (`GET /api/public/decks/{code}/media/{assetId}` -> `{assetId, state, playback|null, poster|null}`, signed URLs of derived
 * variants); this adapter keeps the page independent of how it is served. It stands in for {@link MediaPlaybackApi} where a
 * public material is shown. A request or a shape that fails is a rejection: the surface then draws its «unavailable» state,
 * never a broken image. The public route has no original download, so `download` is always `null`.
 */
@Injectable()
export class PublicMediaPlaybackApi implements Pick<MediaPlaybackApi, 'read'> {
    private readonly http = inject(HttpClient);
    private readonly base = `${appConfig.learningApiBaseUrl.replace(/\/$/, '')}/public/decks`;
    /** The deck whose media is read; set by the page that shows its materials. */
    code = '';

    async read(assetId: string): Promise<MediaPlaybackView> {
        if (!PUBLIC_DECK_CODE.test(this.code)) throw new Error('No public deck code');
        const body = await firstValueFrom(this.http.get<unknown>(`${this.base}/${this.code}/media/${encodeURIComponent(assetId)}`));
        return parseView(body, assetId);
    }
}

function parseView(value: unknown, assetId: string): MediaPlaybackView {
    if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new Error('Invalid public media');
    const view = value as Record<string, unknown>;
    const keys = Object.keys(view);
    if (keys.length !== 4 || !['assetId', 'state', 'playback', 'poster'].every(key => keys.includes(key))
        || typeof view['assetId'] !== 'string' || view['assetId'].toLowerCase() !== assetId.toLowerCase()
        || !STATES.includes(view['state'] as MediaPlaybackView['state'])) throw new Error('Invalid public media');
    return { assetId: view['assetId'].toLowerCase(), state: view['state'] as MediaPlaybackView['state'],
        playback: source(view['playback']), poster: source(view['poster']), download: null };
}

function source(value: unknown): SignedMediaSource | null {
    if (value === null) return null;
    if (typeof value !== 'object' || Array.isArray(value)) throw new Error('Invalid public media source');
    const item = value as Record<string, unknown>;
    if (typeof item['url'] !== 'string' || !/^https?:\/\/|^\//u.test(item['url']) || item['url'].startsWith('//')
        || typeof item['expiresAt'] !== 'string' || !Number.isFinite(Date.parse(item['expiresAt']))
        || typeof item['mimeType'] !== 'string' || item['mimeType'].length > 100) throw new Error('Invalid public media source');
    return { url: item['url'], expiresAt: item['expiresAt'], mimeType: item['mimeType'] };
}
