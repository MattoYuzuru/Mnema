import { DOCUMENT } from '@angular/common';
import { Injectable, inject } from '@angular/core';

import { isTrustedPaymentUrl } from './billing-api.service';

/** The one place the page leaves for the bank: a thin wrapper so specs can spy, and so no caller can skip the host check. */
@Injectable({ providedIn: 'root' })
export class PaymentRedirect {
    private readonly document = inject(DOCUMENT);

    /** Navigates to a trusted payment URL; returns false (and does nothing) for any other. */
    go(url: string): boolean {
        if (!isTrustedPaymentUrl(url)) return false;
        this.document.location.assign(url);
        return true;
    }
}
