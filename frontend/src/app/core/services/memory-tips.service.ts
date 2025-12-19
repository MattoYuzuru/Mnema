import { Injectable } from '@angular/core';
import { I18nService } from './i18n.service';

interface MemoryTip {
    category: string;
    content: string;
}

const MEMORY_TIP_KEYS: string[] = [
    'memoryTips.tip1',
    'memoryTips.tip2',
    'memoryTips.tip3',
    'memoryTips.tip4',
    'memoryTips.tip5',
    'memoryTips.tip6',
];

@Injectable({ providedIn: 'root' })
export class MemoryTipsService {
    constructor(private i18n: I18nService) {}

    getRandomTip(): MemoryTip | null {
        if (!MEMORY_TIP_KEYS.length) return null;
        const randomIndex = Math.floor(Math.random() * MEMORY_TIP_KEYS.length);
        const key = MEMORY_TIP_KEYS[randomIndex];
        return {
            category: 'general',
            content: this.i18n.translate(key)
        };
    }

    getTipsByCategory(category: string): MemoryTip[] {
        return [];
    }

    getAllTips(): MemoryTip[] {
        return MEMORY_TIP_KEYS.map(key => ({
            category: 'general',
            content: this.i18n.translate(key)
        }));
    }
}
