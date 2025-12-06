import { Injectable } from '@angular/core';

interface MemoryTip {
    category: string;
    content: string;
}

const MEMORY_TIPS: MemoryTip[] = [
    { category: 'neuroscience', content: 'Active recall strengthens neural pathways more than passive review.' },
    { category: 'productivity', content: 'Spaced repetition leverages the spacing effect for optimal retention.' },
    { category: 'discipline', content: 'Consistency is the bridge between goals and accomplishment.' },
    { category: 'method', content: 'Ancient orators memorized hours of speeches through structured technique.' },
    { category: 'ritual', content: 'Daily practice, even for 5 minutes, compounds over time.' },
    { category: 'wisdom', content: 'Memory is the treasury and guardian of all things.' },
];

@Injectable({ providedIn: 'root' })
export class MemoryTipsService {
    getRandomTip(): MemoryTip | null {
        if (!MEMORY_TIPS.length) return null;
        const randomIndex = Math.floor(Math.random() * MEMORY_TIPS.length);
        return MEMORY_TIPS[randomIndex];
    }

    getTipsByCategory(category: string): MemoryTip[] {
        return MEMORY_TIPS.filter(tip => tip.category === category);
    }

    getAllTips(): MemoryTip[] {
        return MEMORY_TIPS;
    }
}
