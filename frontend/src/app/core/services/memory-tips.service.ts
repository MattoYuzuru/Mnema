import { Injectable } from '@angular/core';
import { ThemeService } from './theme.service';
import { MemoryTip } from '../models/theme.models';

@Injectable({ providedIn: 'root' })
export class MemoryTipsService {
    constructor(private themeService: ThemeService) {}

    getRandomTip(themeId?: string): MemoryTip | null {
        const targetThemeId = themeId || this.themeService.currentThemeId();
        const theme = this.themeService.getAllThemes().find(t => t.id === targetThemeId);

        if (!theme || !theme.memoryTips.length) {
            return null;
        }

        const randomIndex = Math.floor(Math.random() * theme.memoryTips.length);
        return theme.memoryTips[randomIndex];
    }

    getTipsByCategory(category: string, themeId?: string): MemoryTip[] {
        const targetThemeId = themeId || this.themeService.currentThemeId();
        const theme = this.themeService.getAllThemes().find(t => t.id === targetThemeId);

        if (!theme) {
            return [];
        }

        return theme.memoryTips.filter(tip => tip.category === category);
    }

    getAllTips(themeId?: string): MemoryTip[] {
        const targetThemeId = themeId || this.themeService.currentThemeId();
        const theme = this.themeService.getAllThemes().find(t => t.id === targetThemeId);

        return theme?.memoryTips || [];
    }
}
