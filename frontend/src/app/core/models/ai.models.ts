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
}

export interface AiJobResultResponse {
    jobId: string;
    status: AiJobStatus;
    resultSummary: unknown;
}
