import { Injectable, signal } from '@angular/core';

const MODE_STORAGE_KEY = 'mnema_theme_mode';
const ACCENT_STORAGE_KEY = 'mnema_theme_accent';

type ThemeMode = 'light' | 'dark';
type ThemeAccent = 'neo' | 'vintage';

interface ThemePalette {
    primaryAccent: string;
    secondaryAccent: string;
    background: string;
    cardBackground: string;
    textPrimary: string;
    textSecondary: string;
    textMuted: string;
    borderColor: string;
    borderColorHover: string;
}

const THEME_PALETTES: Record<ThemeMode, Record<ThemeAccent, ThemePalette>> = {
    light: {
        neo: {
            primaryAccent: '#0891b2',
            secondaryAccent: '#06b6d4',
            background: '#f8fafc',
            cardBackground: '#ffffff',
            textPrimary: '#0f172a',
            textSecondary: '#334155',
            textMuted: '#64748b',
            borderColor: '#e2e8f0',
            borderColorHover: '#cbd5e1'
        },
        vintage: {
            primaryAccent: '#92400e',
            secondaryAccent: '#b45309',
            background: '#fef3c7',
            cardBackground: '#fef9e7',
            textPrimary: '#451a03',
            textSecondary: '#78350f',
            textMuted: '#92400e',
            borderColor: '#fde68a',
            borderColorHover: '#fcd34d'
        }
    },
    dark: {
        neo: {
            primaryAccent: '#06b6d4',
            secondaryAccent: '#22d3ee',
            background: '#020617',
            cardBackground: '#0f172a',
            textPrimary: '#f1f5f9',
            textSecondary: '#cbd5e1',
            textMuted: '#64748b',
            borderColor: '#1e293b',
            borderColorHover: '#334155'
        },
        vintage: {
            primaryAccent: '#d97706',
            secondaryAccent: '#f59e0b',
            background: '#1c1917',
            cardBackground: '#292524',
            textPrimary: '#fafaf9',
            textSecondary: '#e7e5e4',
            textMuted: '#a8a29e',
            borderColor: '#44403c',
            borderColorHover: '#57534e'
        }
    }
};

@Injectable({ providedIn: 'root' })
export class ThemeService {
    private readonly _mode = signal<ThemeMode>('light');
    private readonly _accent = signal<ThemeAccent>('neo');

    readonly mode = this._mode.asReadonly();
    readonly accent = this._accent.asReadonly();

    constructor() {
        this.loadFromStorage();
        this.applyTheme();
    }

    setMode(mode: ThemeMode): void {
        this._mode.set(mode);
        this.saveToStorage();
        this.applyTheme();
    }

    setAccent(accent: ThemeAccent): void {
        this._accent.set(accent);
        this.saveToStorage();
        this.applyTheme();
    }

    toggleMode(): void {
        this.setMode(this._mode() === 'light' ? 'dark' : 'light');
    }

    private applyTheme(): void {
        const palette = THEME_PALETTES[this._mode()][this._accent()];
        const root = document.documentElement;

        root.style.setProperty('--color-primary-accent', palette.primaryAccent);
        root.style.setProperty('--color-secondary-accent', palette.secondaryAccent);
        root.style.setProperty('--color-background', palette.background);
        root.style.setProperty('--color-card-background', palette.cardBackground);
        root.style.setProperty('--color-text-primary', palette.textPrimary);
        root.style.setProperty('--color-text-secondary', palette.textSecondary);
        root.style.setProperty('--color-text-muted', palette.textMuted);
        root.style.setProperty('--border-color', palette.borderColor);
        root.style.setProperty('--border-color-hover', palette.borderColorHover);
    }

    private loadFromStorage(): void {
        const savedMode = localStorage.getItem(MODE_STORAGE_KEY);
        const savedAccent = localStorage.getItem(ACCENT_STORAGE_KEY);

        if (savedMode === 'light' || savedMode === 'dark') {
            this._mode.set(savedMode);
        }

        if (savedAccent === 'neo' || savedAccent === 'vintage') {
            this._accent.set(savedAccent);
        }
    }

    private saveToStorage(): void {
        localStorage.setItem(MODE_STORAGE_KEY, this._mode());
        localStorage.setItem(ACCENT_STORAGE_KEY, this._accent());
    }
}
