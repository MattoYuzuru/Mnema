export interface PublicDeckDTO {
    deckId: string;
    version: number;
    authorId: string;
    name: string;
    description: string;
    templateId: string;
    isPublic: boolean;
    isListed: boolean;
    language: string;
    tags: string[];
    createdAt: string;
    updatedAt: string;
    publishedAt?: string | null;
    forkedFromDeck?: string | null;
}

export interface PublicCardDTO {
    deckId: string;
    deckVersion: number;
    cardId: string;
    content: Record<string, string>;
    orderIndex: number;
    tags: string[];
    createdAt: string;
    updatedAt?: string | null;
    active: boolean;
    checksum: string;
}

export interface PublicDeckCardsPage {
    content: PublicCardDTO[];
    fields: import('./template.models').FieldTemplateDTO[];
    pageable: {
        pageNumber: number;
        pageSize: number;
        sort: {
            sorted: boolean;
            empty: boolean;
            unsorted: boolean;
        };
        offset: number;
        paged: boolean;
        unpaged: boolean;
    };
    last: boolean;
    totalPages: number;
    totalElements: number;
    size: number;
    number: number;
    first: boolean;
    numberOfElements: number;
    empty: boolean;
}
