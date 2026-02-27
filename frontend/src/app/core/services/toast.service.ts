import { Injectable, signal } from '@angular/core';

export type ToastTone = 'info' | 'success' | 'warning' | 'error';

export interface ToastItem {
    id: number;
    messageKey: string;
    tone: ToastTone;
    durationMs: number;
}

@Injectable({ providedIn: 'root' })
export class ToastService {
    private readonly _items = signal<ToastItem[]>([]);
    readonly items = this._items.asReadonly();

    private nextId = 0;
    private readonly timers = new Map<number, ReturnType<typeof setTimeout>>();

    show(messageKey: string, tone: ToastTone = 'info', durationMs = 3200): number {
        const id = ++this.nextId;
        const item: ToastItem = { id, messageKey, tone, durationMs };
        this._items.update(items => [...items, item].slice(-5));

        if (durationMs > 0) {
            const timer = setTimeout(() => this.dismiss(id), durationMs);
            this.timers.set(id, timer);
        }
        return id;
    }

    info(messageKey: string, durationMs?: number): number {
        return this.show(messageKey, 'info', durationMs);
    }

    success(messageKey: string, durationMs?: number): number {
        return this.show(messageKey, 'success', durationMs);
    }

    warning(messageKey: string, durationMs?: number): number {
        return this.show(messageKey, 'warning', durationMs);
    }

    error(messageKey: string, durationMs?: number): number {
        return this.show(messageKey, 'error', durationMs);
    }

    dismiss(id: number): void {
        const timer = this.timers.get(id);
        if (timer) {
            clearTimeout(timer);
            this.timers.delete(id);
        }
        this._items.update(items => items.filter(item => item.id !== id));
    }
}

