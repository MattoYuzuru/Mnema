export interface UserCardDTO {
    userCardId: string;
    publicCardId: string;
    isCustom: boolean;
    isDeleted: boolean;
    isSuspended: boolean;
    personalNote?: string | null;
    effectiveContent: Record<string, string>;
    lastReviewAt?: string | null;
    nextReviewAt?: string | null;
    reviewCount: number;
}

export interface CreateCardRequest {
    content: Record<string, string>;
    orderIndex?: number;
    tags?: string[];
}
