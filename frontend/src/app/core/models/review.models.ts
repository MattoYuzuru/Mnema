export interface ReviewQueueDTO {
    dueCount: number;
    newCount: number;
    totalRemaining?: number;
}

export interface IntervalDTO {
    at: string;
    display: string;
}

export interface ReviewNextCardResponse {
    userCardId: string | null;
    publicCardId: string | null;
    due: boolean;
    effectiveContent: Record<string, string>;
    intervals: Record<string, IntervalDTO> | null;
    queue: ReviewQueueDTO;
}

export interface ReviewAnswerRequest {
    rating: 'AGAIN' | 'HARD' | 'GOOD' | 'EASY';
    responseMs: number;
    source: string;
}

export interface ReviewAnswerResponse {
    userCardId: string;
    next: ReviewNextCardResponse;
}

export interface ReviewDeckAlgorithmResponse {
    algorithmId: string;
    algorithmParams: Record<string, unknown> | null;
    effectiveAlgorithmParams?: Record<string, unknown> | null;
    pendingMigrationCards: number;
}

export interface UpdateAlgorithmRequest {
    algorithmId: string;
    algorithmParams: Record<string, unknown> | null;
}
