import { Pipe, PipeTransform, ChangeDetectorRef, OnDestroy } from '@angular/core';
import { Subscription } from 'rxjs';
import { I18nService, TranslationParams } from '../../core/services/i18n.service';

@Pipe({
    name: 'translate',
    standalone: true,
    pure: false
})
export class TranslatePipe implements PipeTransform, OnDestroy {
    private value = '';
    private lastKey = '';
    private lastParams?: TranslationParams;
    private subscription?: Subscription;

    constructor(
        private i18n: I18nService,
        private cdr: ChangeDetectorRef
    ) {
        this.subscription = this.i18n.currentLanguage$.subscribe(() => {
            this.updateValue(this.lastKey, this.lastParams);
        });
    }

    transform(key: string, params?: TranslationParams): string {
        const paramsChanged = !this.areParamsEqual(params, this.lastParams);
        if (key !== this.lastKey || paramsChanged) {
            this.lastKey = key;
            this.lastParams = params ? { ...params } : undefined;
            this.updateValue(key, params);
        }
        return this.value;
    }

    private updateValue(key: string, params?: TranslationParams): void {
        this.value = this.i18n.translate(key, params);
        this.cdr.markForCheck();
    }

    private areParamsEqual(left?: TranslationParams, right?: TranslationParams): boolean {
        const leftEntries = Object.entries(left ?? {});
        const rightEntries = Object.entries(right ?? {});
        if (leftEntries.length !== rightEntries.length) {
            return false;
        }
        return leftEntries.every(([key, value]) => right?.[key] === value);
    }

    ngOnDestroy(): void {
        this.subscription?.unsubscribe();
    }
}
