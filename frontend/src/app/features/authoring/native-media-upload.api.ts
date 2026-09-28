import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { appConfig } from '../../app.config';

export type NativeMediaKind = 'image' | 'audio' | 'video';
export type AssetState = 'PENDING_UPLOAD' | 'VERIFYING' | 'PROCESSING' | 'READY'
    | 'FAILED_RETRYABLE' | 'REJECTED' | 'DELETED';
export type UploadState = 'INITIATING' | 'OPEN' | 'FINALIZING' | 'SEALED'
    | 'ABORTING' | 'ABORTED' | 'EXPIRED';

export interface UploadPart {
    number: number;
    length: number;
    url: string;
    headers: Record<string, string>;
    expiresAt: string;
}

export interface UploadView {
    assetId: string;
    generation: number;
    currentGeneration: number;
    state: UploadState;
    assetState: AssetState;
    method: 'SINGLE' | 'MULTIPART';
    declaredLength: number;
    declaredMime: string;
    expiresAt: string;
    partSize: number | null;
    partCount: number | null;
    url: string | null;
    headers: Record<string, string> | null;
    urlExpiresAt: string | null;
    parts: readonly UploadPart[];
}

/** New Learning media transfer contract. Presigned object URLs never receive account credentials. */
@Injectable({ providedIn: 'root' })
export class NativeMediaUploadApi {
    private readonly http = inject(HttpClient);
    private readonly base = `${appConfig.learningApiBaseUrl.replace(/\/$/, '')}/media-assets`;

    intent(intentId: string, origin: 'upload' | 'recording', kind: NativeMediaKind,
           mime: string, byteLength: number): Promise<UploadView> {
        return firstValueFrom(this.http.post<UploadView>(`${this.base}/upload-intents`, {
            intentId, origin, kind, mime, byteLength
        }));
    }

    status(assetId: string): Promise<UploadView> {
        return firstValueFrom(this.http.get<UploadView>(`${this.base}/${encodeURIComponent(assetId)}/upload`));
    }

    renewSingle(assetId: string, generation: number): Promise<UploadView> {
        return firstValueFrom(this.http.post<UploadView>(`${this.base}/${encodeURIComponent(assetId)}/upload/url`, {
            generation
        }));
    }

    partUrls(assetId: string, generation: number, firstPart: number, count: number): Promise<UploadView> {
        return firstValueFrom(this.http.post<UploadView>(`${this.base}/${encodeURIComponent(assetId)}/upload/part-urls`, {
            generation, firstPart, count
        }));
    }

    completedParts(assetId: string, generation: number): Promise<readonly number[]> {
        return firstValueFrom(this.http.get<number[]>(`${this.base}/${encodeURIComponent(assetId)}/upload/parts`, {
            params: new HttpParams().set('generation', generation)
        }));
    }

    finalize(assetId: string, generation: number, commandId: string): Promise<UploadView> {
        return firstValueFrom(this.http.post<UploadView>(`${this.base}/${encodeURIComponent(assetId)}/upload/finalize`, {
            generation, commandId
        }));
    }

    retry(assetId: string, commandId: string, kind: NativeMediaKind, mime: string,
          byteLength: number): Promise<UploadView> {
        return firstValueFrom(this.http.post<UploadView>(`${this.base}/${encodeURIComponent(assetId)}/upload/retry`, {
            commandId, kind, mime, byteLength
        }));
    }

    cancel(assetId: string, generation: number): Promise<void> {
        return firstValueFrom(this.http.delete<void>(`${this.base}/${encodeURIComponent(assetId)}/upload`, {
            params: new HttpParams().set('generation', generation)
        }));
    }

    put(url: string, body: Blob, headers: Readonly<Record<string, string>>,
        progress: (loaded: number) => void, signal: AbortSignal): Promise<void> {
        return new Promise((resolve, reject) => {
            const request = new XMLHttpRequest();
            request.open('PUT', url);
            for (const [name, value] of Object.entries(headers)) {
                // User agents set this forbidden request header from the exact Blob length.
                if (name.toLowerCase() !== 'content-length') request.setRequestHeader(name, value);
            }
            request.upload.onprogress = event => {
                if (event.lengthComputable) progress(event.loaded);
            };
            request.onload = () => request.status >= 200 && request.status < 300
                ? resolve() : reject(new Error(`Object transfer failed (${request.status}).`));
            request.onerror = () => reject(new Error('Object transfer failed.'));
            request.onabort = () => reject(new DOMException('Upload cancelled.', 'AbortError'));
            if (signal.aborted) { reject(new DOMException('Upload cancelled.', 'AbortError')); return; }
            signal.addEventListener('abort', () => request.abort(), { once: true });
            request.send(body);
        });
    }
}
