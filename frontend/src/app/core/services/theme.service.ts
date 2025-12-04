import { Injectable, signal } from '@angular/core';
import { ThemeConfig } from '../models/theme.models';

const THEME_STORAGE_KEY = 'mnema_theme_id';
const MODE_STORAGE_KEY = 'mnema_theme_mode';

const NEO_THEME: ThemeConfig = {
    id: 'neo',
    name: 'Neo',
    description: 'Future, cyberpunk, neon aesthetics',
    mode: 'dark',
    colors: {
        primaryAccent: '#00ffff',
        secondaryAccent: '#ff00ff',
        background: '#0a0a0a',
        cardBackground: '#1a1a1a',
        textPrimary: '#ffffff',
        textMuted: '#a0a0a0'
    },
    assets: {
        logoUrl: 'assets/logo-neo.svg',
        faviconUrl: 'assets/favicon-neo.ico',
        cornerIllustrationUrl: 'assets/neo-circuit.svg'
    },
    memoryTips: [
        { category: 'neuroscience', content: 'Active recall strengthens neural pathways more than passive review.' },
        { category: 'productivity', content: 'Spaced repetition leverages the spacing effect for optimal retention.' },
        { category: 'ai', content: 'AI can generate cards, but human review ensures quality and relevance.' }
    ]
};

const OLD_JAPAN_THEME: ThemeConfig = {
    id: 'old-japan',
    name: 'Old Japan',
    description: 'Traditional Japanese aesthetics and discipline',
    mode: 'light',
    colors: {
        primaryAccent: '#c41e3a',
        secondaryAccent: '#f4a460',
        background: '#faf8f3',
        cardBackground: '#ffffff',
        textPrimary: '#2d2d2d',
        textMuted: '#6b6b6b'
    },
    assets: {
        logoUrl: 'assets/logo-old-japan.svg',
        faviconUrl: 'assets/favicon-old-japan.ico',
        cornerIllustrationUrl: 'assets/torii-gate.svg'
    },
    memoryTips: [
        { category: 'discipline', content: 'Consistency is the bridge between goals and accomplishment.' },
        { category: 'ritual', content: 'Daily practice, even for 5 minutes, compounds over time.' },
        { category: 'patience', content: 'A river cuts through rock not by force, but by persistence.' }
    ]
};

const ANCIENT_GREEK_THEME: ThemeConfig = {
    id: 'ancient-greek',
    name: 'Ancient Greek',
    description: 'Sacred memory and classical rhetoric',
    mode: 'light',
    colors: {
        primaryAccent: '#8b7355',
        secondaryAccent: '#d4af37',
        background: '#f5f1e8',
        cardBackground: '#ffffff',
        textPrimary: '#3e3e3e',
        textMuted: '#7a7a7a'
    },
    assets: {
        logoUrl: 'assets/logo-ancient-greek.svg',
        faviconUrl: 'assets/favicon-ancient-greek.ico',
        cornerIllustrationUrl: 'assets/greek-column.svg'
    },
    memoryTips: [
        { category: 'rhetoric', content: 'The Method of Loci: place memories in imagined spaces.' },
        { category: 'method', content: 'Ancient orators memorized hours of speeches through structured technique.' },
        { category: 'wisdom', content: 'Memory is the treasury and guardian of all things.' }
    ]
};

@Injectable({ providedIn: 'root' })
export class ThemeService {
    private readonly themes: Map<string, ThemeConfig> = new Map([
        ['neo', NEO_THEME],
        ['old-japan', OLD_JAPAN_THEME],
        ['ancient-greek', ANCIENT_GREEK_THEME]
    ]);

    private readonly _currentThemeId = signal<string>('neo');
    private readonly _mode = signal<'light' | 'dark'>('dark');

    readonly currentThemeId = this._currentThemeId.asReadonly();
    readonly mode = this._mode.asReadonly();

    constructor() {
        this.loadFromStorage();
        this.applyTheme();
    }

    getCurrentTheme(): ThemeConfig | undefined {
        return this.themes.get(this._currentThemeId());
    }

    getAllThemes(): ThemeConfig[] {
        return Array.from(this.themes.values());
    }

    setTheme(themeId: string): void {
        if (this.themes.has(themeId)) {
            this._currentThemeId.set(themeId);
            this.saveToStorage();
            this.applyTheme();
        }
    }

    setMode(mode: 'light' | 'dark'): void {
        this._mode.set(mode);
        this.saveToStorage();
        this.applyTheme();
    }

    toggleMode(): void {
        this.setMode(this._mode() === 'light' ? 'dark' : 'light');
    }

    addCustomTheme(theme: ThemeConfig): void {
        this.themes.set(theme.id, theme);
    }

    private applyTheme(): void {
        const theme = this.getCurrentTheme();
        if (!theme) return;

        const root = document.documentElement;
        const colors = theme.colors;

        root.style.setProperty('--color-primary-accent', colors.primaryAccent);
        root.style.setProperty('--color-secondary-accent', colors.secondaryAccent);
        root.style.setProperty('--color-background', colors.background);
        root.style.setProperty('--color-card-background', colors.cardBackground);
        root.style.setProperty('--color-text-primary', colors.textPrimary);
        root.style.setProperty('--color-text-muted', colors.textMuted);
    }

    private loadFromStorage(): void {
        const savedThemeId = localStorage.getItem(THEME_STORAGE_KEY);
        const savedMode = localStorage.getItem(MODE_STORAGE_KEY);

        if (savedThemeId && this.themes.has(savedThemeId)) {
            this._currentThemeId.set(savedThemeId);
        }

        if (savedMode === 'light' || savedMode === 'dark') {
            this._mode.set(savedMode);
        }
    }

    private saveToStorage(): void {
        localStorage.setItem(THEME_STORAGE_KEY, this._currentThemeId());
        localStorage.setItem(MODE_STORAGE_KEY, this._mode());
    }
}
