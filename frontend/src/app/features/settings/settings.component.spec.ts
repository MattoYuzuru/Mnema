import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { of } from 'rxjs';

import { AuthService } from '../../auth.service';
import { UserApiService } from '../../user-api.service';
import { AiApiService } from '../../core/services/ai-api.service';
import { DeckApiService } from '../../core/services/deck-api.service';
import { SettingsComponent } from './settings.component';
import { I18nService } from '../../core/services/i18n.service';
import { PreferencesService } from '../../core/services/preferences.service';
import { ThemeService } from '../../core/services/theme.service';
import { ToastService } from '../../core/services/toast.service';

describe('SettingsComponent', () => {
    let component: SettingsComponent;
    let deckApi: jasmine.SpyObj<any>;
    let toast: jasmine.SpyObj<ToastService>;

    beforeEach(() => {
        deckApi = jasmine.createSpyObj('DeckApiService', ['patchDeck', 'getDeletedDecks', 'hardDeleteDeck']);
        toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error', 'info', 'warning', 'show', 'dismiss']);

        TestBed.configureTestingModule({
            providers: [
                { provide: ThemeService, useValue: {} },
                { provide: I18nService, useValue: new I18nService() },
                { provide: PreferencesService, useValue: {} },
                { provide: DeckApiService, useValue: deckApi },
                { provide: AiApiService, useValue: jasmine.createSpyObj('AiApiService', ['listProviders', 'createProvider', 'deleteProvider']) },
                { provide: UserApiService, useValue: jasmine.createSpyObj('UserApiService', ['getMe', 'deleteMe']) },
                { provide: AuthService, useValue: jasmine.createSpyObj('AuthService', ['logout']) },
                { provide: Router, useValue: jasmine.createSpyObj('Router', ['navigate']) },
                { provide: ToastService, useValue: toast }
            ]
        });
        component = TestBed.runInInjectionContext(() => new SettingsComponent());
    });

    it('shows a toast after restoring an archived deck', () => {
        component.archivedDecks = [{ userDeckId: 'deck-1' } as any];
        deckApi.patchDeck.and.returnValue(of({}));

        component.restoreDeck('deck-1');

        expect(component.archivedDecks).toEqual([]);
        expect(toast.success).toHaveBeenCalledWith('settings.archiveRestoreSuccess');
    });
});
