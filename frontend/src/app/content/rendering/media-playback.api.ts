import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { appConfig } from '../../app.config';

export interface SignedMediaSource {
    readonly url: string;
    readonly expiresAt: string;
    readonly mimeType: string;
}

export interface MediaPlaybackView {
    readonly assetId: string;
    readonly state: 'PENDING_UPLOAD' | 'VERIFYING' | 'PROCESSING' | 'READY'
        | 'FAILED_RETRYABLE' | 'REJECTED' | 'DELETED';
    readonly playback: SignedMediaSource | null;
    readonly poster: SignedMediaSource | null;
    readonly download: SignedMediaSource | null;
}

@Injectable({ providedIn: 'root' })
export class MediaPlaybackApi {
    private readonly http = inject(HttpClient);
    private readonly base = `${appConfig.learningApiBaseUrl.replace(/\/$/, '')}/media-assets`;

    read(assetId: string): Promise<MediaPlaybackView> {
        return firstValueFrom(this.http.get<MediaPlaybackView>(
            `${this.base}/${encodeURIComponent(assetId)}/playback`));
    }
}
