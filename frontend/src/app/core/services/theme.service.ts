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
    surfaceSolid: string;
    glassSurface: string;
    glassSurfaceStrong: string;
    glassBorder: string;
    glassBorderStrong: string;
    shadowColor: string;
    pageGradient: string;
    focusRing: string;
}

const THEME_PALETTES: Record<ThemeMode, Record<ThemeAccent, ThemePalette>> = {
    light: {
        neo: {
            primaryAccent: '#0ea5e9',
            secondaryAccent: '#14b8a6',
            background: '#edf1f8',
            cardBackground: 'rgba(255, 255, 255, 0.55)',
            textPrimary: '#0b1220',
            textSecondary: '#1f2a44',
            textMuted: '#5b6b86',
            borderColor: 'rgba(148, 163, 184, 0.35)',
            borderColorHover: 'rgba(148, 163, 184, 0.6)',
            surfaceSolid: '#f9fbff',
            glassSurface: 'rgba(255, 255, 255, 0.42)',
            glassSurfaceStrong: 'rgba(255, 255, 255, 0.68)',
            glassBorder: 'rgba(255, 255, 255, 0.4)',
            glassBorderStrong: 'rgba(255, 255, 255, 0.6)',
            shadowColor: 'rgba(15, 23, 42, 0.14)',
            pageGradient: 'radial-gradient(900px 600px at 10% -10%, rgba(56, 189, 248, 0.35), transparent 60%), radial-gradient(700px 500px at 85% 0%, rgba(251, 113, 133, 0.25), transparent 55%), radial-gradient(600px 500px at 30% 100%, rgba(45, 212, 191, 0.18), transparent 55%), linear-gradient(180deg, rgba(255, 255, 255, 0.9), rgba(236, 242, 255, 0.8))',
            focusRing: '0 0 0 3px rgba(14, 165, 233, 0.25)'
        },
        vintage: {
            primaryAccent: '#b45309',
            secondaryAccent: '#f97316',
            background: '#fff3e0',
            cardBackground: 'rgba(255, 250, 240, 0.6)',
            textPrimary: '#3b1d05',
            textSecondary: '#5c2a0c',
            textMuted: '#8a4b1b',
            borderColor: 'rgba(249, 115, 22, 0.25)',
            borderColorHover: 'rgba(249, 115, 22, 0.45)',
            surfaceSolid: '#fff7ed',
            glassSurface: 'rgba(255, 247, 237, 0.5)',
            glassSurfaceStrong: 'rgba(255, 239, 213, 0.72)',
            glassBorder: 'rgba(255, 214, 170, 0.45)',
            glassBorderStrong: 'rgba(255, 214, 170, 0.7)',
            shadowColor: 'rgba(120, 53, 15, 0.12)',
            pageGradient: 'radial-gradient(900px 600px at 0% -10%, rgba(251, 146, 60, 0.28), transparent 60%), radial-gradient(700px 500px at 90% 0%, rgba(234, 179, 8, 0.22), transparent 55%), radial-gradient(600px 500px at 30% 100%, rgba(248, 113, 113, 0.18), transparent 55%), linear-gradient(180deg, rgba(255, 247, 237, 0.92), rgba(254, 243, 199, 0.85))',
            focusRing: '0 0 0 3px rgba(249, 115, 22, 0.28)'
        }
    },
    dark: {
        neo: {
            primaryAccent: '#38bdf8',
            secondaryAccent: '#22d3ee',
            background: '#0a0f1e',
            cardBackground: 'rgba(15, 23, 42, 0.65)',
            textPrimary: '#e2e8f0',
            textSecondary: '#cbd5f5',
            textMuted: '#94a3b8',
            borderColor: 'rgba(148, 163, 184, 0.18)',
            borderColorHover: 'rgba(148, 163, 184, 0.4)',
            surfaceSolid: '#0b1220',
            glassSurface: 'rgba(15, 23, 42, 0.5)',
            glassSurfaceStrong: 'rgba(17, 25, 40, 0.72)',
            glassBorder: 'rgba(148, 163, 184, 0.2)',
            glassBorderStrong: 'rgba(148, 163, 184, 0.35)',
            shadowColor: 'rgba(2, 6, 23, 0.55)',
            pageGradient: 'radial-gradient(900px 600px at 10% -10%, rgba(56, 189, 248, 0.2), transparent 60%), radial-gradient(700px 500px at 85% 0%, rgba(251, 113, 133, 0.16), transparent 55%), radial-gradient(600px 500px at 20% 100%, rgba(45, 212, 191, 0.12), transparent 55%), linear-gradient(180deg, rgba(8, 12, 24, 0.95), rgba(10, 16, 30, 0.85))',
            focusRing: '0 0 0 3px rgba(56, 189, 248, 0.35)'
        },
        vintage: {
            primaryAccent: '#f59e0b',
            secondaryAccent: '#fb7185',
            background: '#1c1917',
            cardBackground: 'rgba(41, 37, 36, 0.75)',
            textPrimary: '#fafaf9',
            textSecondary: '#e7e5e4',
            textMuted: '#b9b2ab',
            borderColor: 'rgba(120, 113, 108, 0.35)',
            borderColorHover: 'rgba(120, 113, 108, 0.6)',
            surfaceSolid: '#181310',
            glassSurface: 'rgba(41, 37, 36, 0.55)',
            glassSurfaceStrong: 'rgba(41, 37, 36, 0.78)',
            glassBorder: 'rgba(168, 162, 158, 0.25)',
            glassBorderStrong: 'rgba(168, 162, 158, 0.4)',
            shadowColor: 'rgba(12, 10, 9, 0.6)',
            pageGradient: 'radial-gradient(900px 600px at 5% -10%, rgba(251, 146, 60, 0.18), transparent 60%), radial-gradient(700px 500px at 90% 0%, rgba(244, 114, 182, 0.16), transparent 55%), radial-gradient(600px 500px at 15% 100%, rgba(234, 179, 8, 0.12), transparent 55%), linear-gradient(180deg, rgba(20, 18, 17, 0.95), rgba(28, 25, 23, 0.85))',
            focusRing: '0 0 0 3px rgba(244, 114, 182, 0.3)'
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
        root.style.setProperty('--color-surface-solid', palette.surfaceSolid);
        root.style.setProperty('--glass-surface', palette.glassSurface);
        root.style.setProperty('--glass-surface-strong', palette.glassSurfaceStrong);
        root.style.setProperty('--glass-border', palette.glassBorder);
        root.style.setProperty('--glass-border-strong', palette.glassBorderStrong);
        root.style.setProperty('--shadow-color', palette.shadowColor);
        root.style.setProperty('--page-gradient', palette.pageGradient);
        root.style.setProperty('--focus-ring', palette.focusRing);
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
