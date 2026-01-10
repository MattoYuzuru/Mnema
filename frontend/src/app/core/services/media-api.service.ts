import { Injectable } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable } from 'rxjs';
import { appConfig } from '../../app.config';

export type MediaKind = 'avatar' | 'deck_icon' | 'card_image' | 'card_audio' | 'card_video' | 'import_file';

export interface CreateUploadRequest {
    kind: MediaKind;
    contentType: string;
    sizeBytes: number;
    fileName?: string;
}

export interface PresignedPart {
    partNumber: number;
    url: string;
}

export interface CreateUploadResponse {
    uploadId: string;
    url?: string;
    headers?: Record<string, string>;
    parts?: PresignedPart[];
    partsCount?: number;
    partSizeBytes?: number;
}

export interface CompletePart {
    partNumber: number;
    eTag: string;
}

export interface CompleteUploadResponse {
    mediaId: string;
}

export interface ResolvedMedia {
    mediaId: string;
    kind: MediaKind;
    url: string;
    mimeType: string;
    sizeBytes: number;
    durationSeconds?: number;
    width?: number;
    height?: number;
    expiresAt: string;
}

@Injectable({ providedIn: 'root' })
export class MediaApiService {
    private readonly baseUrl = appConfig.mediaApiBaseUrl;

    constructor(private http: HttpClient) {}

    createUpload(request: CreateUploadRequest): Observable<CreateUploadResponse> {
        return this.http.post<CreateUploadResponse>(`${this.baseUrl}/uploads`, request);
    }

    completeUpload(uploadId: string, parts?: CompletePart[]): Observable<CompleteUploadResponse> {
        const body = parts ? { parts } : {};
        return this.http.post<CompleteUploadResponse>(`${this.baseUrl}/uploads/${uploadId}/complete`, body);
    }

    abortUpload(uploadId: string): Observable<void> {
        return this.http.post<void>(`${this.baseUrl}/uploads/${uploadId}/abort`, {});
    }

    resolve(mediaIds: string[]): Observable<ResolvedMedia[]> {
        return this.http.post<ResolvedMedia[]>(`${this.baseUrl}/resolve`, { mediaIds });
    }

    toUrlMap(resolved: ResolvedMedia[]): Record<string, string> {
        const map: Record<string, string> = {};
        for (const media of resolved) {
            map[media.mediaId] = media.url;
        }
        return map;
    }

    deleteMedia(mediaId: string): Observable<void> {
        return this.http.delete<void>(`${this.baseUrl}/assets/${mediaId}`);
    }

    async uploadFile(
        file: File,
        kind: MediaKind,
        onProgress?: (percent: number) => void
    ): Promise<string> {
        const createResponse = await this.createUpload({
            kind,
            contentType: file.type,
            sizeBytes: file.size,
            fileName: file.name
        }).toPromise();

        if (!createResponse) {
            throw new Error('Failed to create upload');
        }

        if (createResponse.url) {
            const headers: Record<string, string> = createResponse.headers || {};
            const uploadHeaders = new HttpHeaders(headers);

            await fetch(createResponse.url, {
                method: 'PUT',
                headers: Object.fromEntries(Object.entries(headers)),
                body: file
            });

            if (onProgress) {
                onProgress(100);
            }

            const completeResponse = await this.completeUpload(createResponse.uploadId).toPromise();
            if (!completeResponse) {
                throw new Error('Failed to complete upload');
            }
            return completeResponse.mediaId;
        } else if (createResponse.parts && createResponse.parts.length > 0) {
            const chunkSize = createResponse.partSizeBytes || (8 * 1024 * 1024);
            const parts: CompletePart[] = [];

            for (let i = 0; i < createResponse.parts.length; i++) {
                const part = createResponse.parts[i];
                const start = i * chunkSize;
                const end = Math.min(start + chunkSize, file.size);
                const chunk = file.slice(start, end);

                const response = await fetch(part.url, {
                    method: 'PUT',
                    body: chunk
                });

                if (!response.ok) {
                    const errorText = await response.text();
                    throw new Error(`Part ${i + 1} upload failed: ${response.status} ${response.statusText} - ${errorText}`);
                }

                const eTag = response.headers.get('ETag')?.replace(/"/g, '');
                if (!eTag) {
                    throw new Error('No ETag returned from part upload');
                }

                parts.push({ partNumber: part.partNumber, eTag });

                if (onProgress) {
                    onProgress(Math.round((i + 1) / createResponse.parts!.length * 100));
                }
            }

            const completeResponse = await this.completeUpload(createResponse.uploadId, parts).toPromise();
            if (!completeResponse) {
                throw new Error('Failed to complete upload');
            }
            return completeResponse.mediaId;
        }

        throw new Error('Invalid upload response');
    }
}
