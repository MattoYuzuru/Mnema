import { ChangeDetectionStrategy, Component, OnInit, inject } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';

import { deckFailureMessage } from './own-decks.store';
import { OwnDecksStore } from './own-decks.store';

@Component({
    selector: 'app-own-decks-list-page',
    imports: [DatePipe, RouterLink],
    providers: [OwnDecksStore],
    templateUrl: './own-decks-list-page.component.html',
    styleUrl: './own-decks-page.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class OwnDecksListPageComponent implements OnInit {
    readonly store = inject(OwnDecksStore);
    readonly failureMessage = deckFailureMessage;

    ngOnInit(): void {
        this.store.loadList();
    }
}
