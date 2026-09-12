import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { of } from 'rxjs';

import { AdminApiService } from '../../core/services/admin-api.service';
import { I18nService } from '../../core/services/i18n.service';
import { ReportApiService } from '../../core/services/report-api.service';
import { ToastService } from '../../core/services/toast.service';
import { AdminPanelComponent } from './admin-panel.component';

describe('AdminPanelComponent', () => {
    let component: AdminPanelComponent;
    let adminApi: jasmine.SpyObj<any>;
    let toast: jasmine.SpyObj<ToastService>;

    beforeEach(() => {
        adminApi = jasmine.createSpyObj('AdminApiService', ['grantAdmin']);
        toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error', 'info', 'warning', 'show', 'dismiss']);

        TestBed.configureTestingModule({
            providers: [
                { provide: AdminApiService, useValue: adminApi },
                { provide: ReportApiService, useValue: jasmine.createSpyObj('ReportApiService', ['closeReport']) },
                { provide: Router, useValue: jasmine.createSpyObj('Router', ['navigate']) },
                { provide: I18nService, useValue: new I18nService() },
                { provide: ToastService, useValue: toast }
            ]
        });
        component = TestBed.runInInjectionContext(() => new AdminPanelComponent());
    });

    it('shows a toast after granting admin rights', () => {
        adminApi.grantAdmin.and.returnValue(of({}));
        spyOn(component, 'reloadAll');

        component.grantAdmin({ id: 'user-1' } as any);

        expect(toast.success).toHaveBeenCalledWith('adminPanel.grantAdminSuccess');
        expect(component.reloadAll).toHaveBeenCalled();
    });
});
