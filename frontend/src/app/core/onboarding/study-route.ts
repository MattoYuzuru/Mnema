import { NavigationEnd, Router } from '@angular/router';
import { Signal, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { filter, map } from 'rxjs';

const STUDY_PATH = /^\/decks\/[^/]+\/study\/?$/u;

/** Whether `url` is a study session (`/decks/:deckId/study`): where nothing of the onboarding is allowed to speak. Query and fragment are ignored. */
export function isStudyUrl(url: string): boolean {
    return STUDY_PATH.test(url.split(/[?#]/u)[0]);
}

/** The current address as a signal; call in an injection context. */
export function currentUrl(router: Router): Signal<string> {
    if (router.events === undefined) return signal(router.url ?? '').asReadonly();
    return toSignal(router.events.pipe(filter(event => event instanceof NavigationEnd), map(event => event.urlAfterRedirects)), { initialValue: router.url });
}
