import { Pipe, PipeTransform, ChangeDetectorRef, OnDestroy } from '@angular/core';
import { Subscription } from 'rxjs';
import { I18nService } from '../../core/services/i18n.service';

@Pipe({
    name: 'translate',
    standalone: true,
    pure: false
})
export class TranslatePipe implements PipeTransform, OnDestroy {
    private value = '';
    private lastKey = '';
    private subscription?: Subscription;

    constructor(
        private i18n: I18nService,
        private cdr: ChangeDetectorRef
    ) {
        this.subscription = this.i18n.currentLanguage$.subscribe(() => {
            this.updateValue(this.lastKey);
        });
    }

    transform(key: string): string {
        if (key !== this.lastKey) {
            this.lastKey = key;
            this.updateValue(key);
        }
        return this.value;
    }

    private updateValue(key: string): void {
        this.value = this.i18n.translate(key);
        this.cdr.markForCheck();
    }

    ngOnDestroy(): void {
        this.subscription?.unsubscribe();
    }
}
