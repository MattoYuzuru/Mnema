import { Injectable, signal } from '@angular/core';

const MODE_STORAGE_KEY = 'mnema_theme_mode';
const ACCENT_STORAGE_KEY = 'mnema_theme_accent';

type ThemeMode = 'light' | 'dark';
type ThemeAccent = 'neo' | 'vintage';

interface ThemePalette {
    readonly background: string;
    readonly sheet: string;
    readonly ink: string;
    readonly body: string;
    readonly muted: string;
    readonly line: string;
    readonly soft: string;
}

const LIGHT: ThemePalette = {
    background: '#f4f0e5',
    sheet: '#fbf8ef',
    ink: '#281378',
    body: '#342e44',
    muted: '#625c70',
    line: '#c9c0ce',
    soft: '#e8e1ed'
};

const LIGHT_WARM: ThemePalette = {
    ...LIGHT,
    background: '#f2eadb',
    sheet: '#fcf6e9',
    line: '#cbbca9',
    soft: '#e9dfd1'
};

const DARK: ThemePalette = {
    background: '#19152c',
    sheet: '#211b38',
    ink: '#ded5ff',
    body: '#f1edf8',
    muted: '#c4bbd1',
    line: '#554b6b',
    soft: '#31294b'
};

const DARK_WARM: ThemePalette = {
    ...DARK,
    background: '#201a24',
    sheet: '#2b222d',
    line: '#665466',
    soft: '#3b2e3d'
};

const THEME_PALETTES: Record<ThemeMode, Record<ThemeAccent, ThemePalette>> = {
    light: { neo: LIGHT, vintage: LIGHT_WARM },
    dark: { neo: DARK, vintage: DARK_WARM }
};

/**
 * Keeps the legacy settings API while mapping every choice to opaque paper surfaces.
 * Canonical pages therefore cannot be switched back to the retired glass treatment.
 */
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
        const properties: Readonly<Record<string, string>> = {
            '--paper': palette.background,
            '--sheet': palette.sheet,
            '--ink': palette.ink,
            '--body': palette.body,
            '--muted': palette.muted,
            '--line': palette.line,
            '--soft': palette.soft,
            '--color-primary-accent': palette.ink,
            '--color-secondary-accent': palette.muted,
            '--color-background': palette.background,
            '--color-card-background': palette.sheet,
            '--color-text-primary': palette.body,
            '--color-text-secondary': palette.body,
            '--color-text-tertiary': palette.body,
            '--color-text-muted': palette.muted,
            '--border-color': palette.line,
            '--border-color-hover': palette.ink,
            '--color-surface-solid': palette.sheet,
            '--glass-surface': palette.sheet,
            '--glass-surface-strong': palette.sheet,
            '--glass-border': palette.line,
            '--glass-border-strong': palette.line,
            '--glass-blur': '0px',
            '--accent-shadow': 'none',
            '--accent-shadow-hover': 'none',
            '--page-gradient': 'none',
            '--focus-ring': `0 0 0 3px color-mix(in srgb, ${palette.ink} 25%, transparent)`
        };

        for (const [name, value] of Object.entries(properties)) root.style.setProperty(name, value);
    }

    private loadFromStorage(): void {
        const savedMode = localStorage.getItem(MODE_STORAGE_KEY);
        const savedAccent = localStorage.getItem(ACCENT_STORAGE_KEY);
        if (savedMode === 'light' || savedMode === 'dark') this._mode.set(savedMode);
        if (savedAccent === 'neo' || savedAccent === 'vintage') this._accent.set(savedAccent);
    }

    private saveToStorage(): void {
        localStorage.setItem(MODE_STORAGE_KEY, this._mode());
        localStorage.setItem(ACCENT_STORAGE_KEY, this._accent());
    }
}
