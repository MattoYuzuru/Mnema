export interface ThemeConfig {
    id: string;
    name: string;
    description: string;
    mode: 'light' | 'dark';
    colors: {
        primaryAccent: string;
        secondaryAccent: string;
        background: string;
        cardBackground: string;
        textPrimary: string;
        textMuted: string;
    };
    assets: {
        logoUrl: string;
        faviconUrl: string;
        cornerIllustrationUrl?: string;
    };
    memoryTips: MemoryTip[];
}

export interface MemoryTip {
    category: string;
    content: string;
}
