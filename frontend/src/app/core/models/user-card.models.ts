export interface UserCardDTO {
    userCardId: string;
    publicCardId: string;
    isCustom: boolean;
    isDeleted: boolean;
    personalNote?: string | null;
    effectiveContent: Record<string, string>;
}

export interface CreateCardRequest {
    content: Record<string, string>;
    orderIndex?: number;
    tags?: string[];
}
