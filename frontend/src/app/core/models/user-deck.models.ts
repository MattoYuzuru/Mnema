export interface UserDeckDTO {
    userDeckId: string;
    userId: string;
    publicDeckId: string;
    subscribedVersion: number;
    currentVersion: number;
    templateVersion?: number | null;
    subscribedTemplateVersion?: number | null;
    autoUpdate: boolean;
    algorithmId: string;
    algorithmParams?: Record<string, unknown> | null;
    displayName: string;
    displayDescription: string;
    createdAt: string;
    lastSyncedAt: string;
    archived: boolean;
}
