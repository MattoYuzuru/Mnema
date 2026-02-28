export type AiProviderStatus = 'active' | 'inactive';

export interface AiProviderCredential {
    id: string;
    provider: string;
    alias?: string | null;
    status: AiProviderStatus;
    createdAt: string;
    lastUsedAt?: string | null;
    updatedAt?: string | null;
}

export interface AiRuntimeCapabilities {
    mode: 'system_managed' | 'user_keys' | string;
    systemProvider?: string | null;
    ollama?: {
        enabled: boolean;
        available: boolean;
        baseUrl: string;
        models: Array<{
            name: string;
            sizeBytes?: number | null;
            modifiedAt?: string | null;
            capabilities: string[];
        }>;
    } | null;
    providers?: Array<{
        key: string;
        displayName: string;
        requiresCredential: boolean;
        text: boolean;
        stt: boolean;
        tts: boolean;
        image: boolean;
        video: boolean;
        gif: boolean;
    }>;
}

export interface CreateAiProviderRequest {
    provider: string;
    alias?: string | null;
    secret: string;
}

export type AiJobType = 'generic' | 'enrich' | 'tts';
export type AiJobStatus = 'queued' | 'processing' | 'completed' | 'failed' | 'canceled';

export interface CreateAiJobRequest {
    requestId: string;
    deckId?: string | null;
    type: AiJobType;
    params?: Record<string, unknown> | null;
    inputHash?: string | null;
}

export interface AiJobResponse {
    jobId: string;
    requestId: string;
    deckId?: string | null;
    type: AiJobType;
    status: AiJobStatus;
    progress: number;
    createdAt: string;
    updatedAt?: string | null;
    startedAt?: string | null;
    completedAt?: string | null;
    errorMessage?: string | null;
    providerCredentialId?: string | null;
    provider?: string | null;
    providerAlias?: string | null;
    model?: string | null;
}

export interface AiJobResultResponse {
    jobId: string;
    status: AiJobStatus;
    resultSummary: unknown;
}

export interface AiImportPreviewRequest {
    requestId: string;
    deckId: string;
    sourceMediaId: string;
    providerCredentialId?: string | null;
    provider?: string | null;
    model?: string | null;
    sourceType?: string | null;
    encoding?: string | null;
    language?: string | null;
    instructions?: string | null;
    stt?: Record<string, unknown> | null;
}

export interface AiImportGenerateRequest {
    requestId: string;
    deckId: string;
    sourceMediaId: string;
    fields: string[];
    count: number;
    providerCredentialId?: string | null;
    provider?: string | null;
    model?: string | null;
    sourceType?: string | null;
    encoding?: string | null;
    language?: string | null;
    instructions?: string | null;
    stt?: Record<string, unknown> | null;
    tts?: Record<string, unknown> | null;
    image?: Record<string, unknown> | null;
    video?: Record<string, unknown> | null;
}

export interface AiImportPreviewSummary {
    mode?: string;
    summary?: string;
    estimatedCount?: number;
    truncated?: boolean;
    sourceBytes?: number;
    sourceChars?: number;
    detectedCharset?: string;
    sourceType?: string;
    extraction?: string;
    sourcePages?: number;
    ocrPages?: number;
    audioDurationSeconds?: number;
    audioChunks?: number;
}
