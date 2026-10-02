import { Injectable, signal } from '@angular/core';

/**
 * A screen that must not be interrupted sets this while the learner has a task open (Study: answering) and clears it
 * at natural pauses (feedback, end of the session) and on leaving. While it is active the toast service holds new
 * notification toasts according to the learner's «Во время занятия» preference; the bell badge always updates.
 */
@Injectable({ providedIn: 'root' })
export class QuietZone {
    private readonly state = signal(false);
    readonly active = this.state.asReadonly();

    set(active: boolean): void { this.state.set(active); }
}
