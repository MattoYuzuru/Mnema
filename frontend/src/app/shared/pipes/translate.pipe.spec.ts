import { ChangeDetectorRef } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { I18nService } from '../../core/services/i18n.service';
import { TranslatePipe } from './translate.pipe';

describe('TranslatePipe', () => {
    let i18n: I18nService;
    let cdr: jasmine.SpyObj<ChangeDetectorRef>;
    let pipe: TranslatePipe;

    beforeEach(() => {
        localStorage.removeItem('mnema_language');
        i18n = new I18nService();
        cdr = jasmine.createSpyObj<ChangeDetectorRef>('ChangeDetectorRef', ['markForCheck']);
        TestBed.configureTestingModule({
            providers: [
                { provide: I18nService, useValue: i18n },
                { provide: ChangeDetectorRef, useValue: cdr }
            ]
        });
        pipe = TestBed.runInInjectionContext(() => new TranslatePipe());
    });

    it('interpolates params and keeps them after language changes', () => {
        expect(pipe.transform('deckProfile.aiJobsRetryFailed', { count: 2 })).toBe('Retry failed only (2)');

        i18n.setLanguage('ru');

        expect(pipe.transform('deckProfile.aiJobsRetryFailed', { count: 2 })).toBe('Повторить только неудачные (2)');
    });
});
