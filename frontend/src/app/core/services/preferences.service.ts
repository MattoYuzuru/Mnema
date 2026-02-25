import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';

export interface UserPreferences {
    hideFieldLabels: boolean;
    showFrontSideAfterFlip: boolean;
    mobileReviewButtonsMode: 'classic' | 'swipe-column';
    mobileReviewButtonsSide: 'left' | 'right';
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
        const defaults = this.getDefaultPreferences();
        try {
            const saved = localStorage.getItem(this.storageKey);
            if (saved) {
                const parsed = JSON.parse(saved) as Partial<UserPreferences>;
                return {
                    ...defaults,
                    ...parsed,
                    mobileReviewButtonsMode: parsed.mobileReviewButtonsMode === 'swipe-column'
                        ? 'swipe-column'
                        : 'classic',
                    mobileReviewButtonsSide: parsed.mobileReviewButtonsSide === 'right'
                        ? 'right'
                        : 'left'
                };
            }
        } catch {
        }
        return defaults;
    }

    private getDefaultPreferences(): UserPreferences {
        return {
            hideFieldLabels: false,
            showFrontSideAfterFlip: true,
            mobileReviewButtonsMode: 'swipe-column',
            mobileReviewButtonsSide: 'left'
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

    get mobileReviewButtonsMode(): 'classic' | 'swipe-column' {
        return this._preferencesSubject.value.mobileReviewButtonsMode;
    }

    setMobileReviewButtonsMode(mode: 'classic' | 'swipe-column'): void {
        const updated = { ...this._preferencesSubject.value, mobileReviewButtonsMode: mode };
        this._preferencesSubject.next(updated);
        this.savePreferences(updated);
    }

    get mobileReviewButtonsSide(): 'left' | 'right' {
        return this._preferencesSubject.value.mobileReviewButtonsSide;
    }

    setMobileReviewButtonsSide(side: 'left' | 'right'): void {
        const updated = { ...this._preferencesSubject.value, mobileReviewButtonsSide: side };
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
