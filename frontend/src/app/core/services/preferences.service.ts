import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';

export interface UserPreferences {
    hideFieldLabels: boolean;
    showFrontSideAfterFlip: boolean;
}

@Injectable({ providedIn: 'root' })
export class PreferencesService {
    private readonly storageKey = 'mnema_preferences';
    private _preferencesSubject: BehaviorSubject<UserPreferences>;

    preferences$: Observable<UserPreferences>;

    constructor() {
        const saved = this.loadPreferences();
        this._preferencesSubject = new BehaviorSubject<UserPreferences>(saved);
        this.preferences$ = this._preferencesSubject.asObservable();
    }

    private loadPreferences(): UserPreferences {
        try {
            const saved = localStorage.getItem(this.storageKey);
            if (saved) {
                return JSON.parse(saved);
            }
        } catch {
        }
        return this.getDefaultPreferences();
    }

    private getDefaultPreferences(): UserPreferences {
        return {
            hideFieldLabels: false,
            showFrontSideAfterFlip: true
        };
    }

    get hideFieldLabels(): boolean {
        return this._preferencesSubject.value.hideFieldLabels;
    }

    setHideFieldLabels(hide: boolean): void {
        const updated = { ...this._preferencesSubject.value, hideFieldLabels: hide };
        this._preferencesSubject.next(updated);
        this.savePreferences(updated);
    }

    get showFrontSideAfterFlip(): boolean {
        return this._preferencesSubject.value.showFrontSideAfterFlip;
    }

    setShowFrontSideAfterFlip(show: boolean): void {
        const updated = { ...this._preferencesSubject.value, showFrontSideAfterFlip: show };
        this._preferencesSubject.next(updated);
        this.savePreferences(updated);
    }

    private savePreferences(prefs: UserPreferences): void {
        try {
            localStorage.setItem(this.storageKey, JSON.stringify(prefs));
        } catch {
        }
    }
}
