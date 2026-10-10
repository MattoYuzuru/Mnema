#!/usr/bin/env python3
"""Run the real bot bridge against disposable synthetic SQLite, without Telegram.

The browser harness supplies --source pointing at its explicit bot checkout,
--database inside its private temporary directory, --port and --account-id.
MNEMA_BROWSER_SUPPORT_SECRET supplies the random machine credential. No .env is read.
"""

import argparse
import json
import os
from pathlib import Path
import re
import sys
import time
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--database', type=Path, required=True)
    parser.add_argument('--port', type=int, required=True)
    parser.add_argument('--account-id', required=True)
    args = parser.parse_args()
    source = args.source.resolve()
    if not (source/'mnema_bot/admin_server.py').is_file():
        raise ValueError('Explicit bot source must contain the administrative bridge')
    if args.database.exists():
        raise ValueError('Fixture database must be a new disposable path')
    actor = str(uuid.UUID(args.account_id))
    if actor != args.account_id or not 1 <= args.port <= 65535:
        raise ValueError('Fixture account/port is invalid')
    secret = os.environ.get('MNEMA_BROWSER_SUPPORT_SECRET', '')
    if not re.fullmatch(r'[A-Za-z0-9_-]{32,256}', secret):
        raise ValueError('Fixture needs its own random MNEMA_BROWSER_SUPPORT_SECRET')
    sys.path.insert(0, str(source))
    from mnema_bot.store import Store
    from mnema_bot.admin_store import AdminInbox
    from mnema_bot.admin_server import AdminServer, BridgeConfig

    store = Store(args.database)
    now = int(time.time())
    try:
        store.user({'id': 123, 'first_name': 'Support fixture', 'username': 'support_fixture'}, 123)
        store.execute('UPDATE users SET linked_subject=? WHERE id=123', (actor,))
        tickets = []
        for category, status in (('bug', 'open'), ('question', 'waiting'), ('idea', 'closed'), ('other', 'working')):
            ticket = store.execute('''INSERT INTO tickets(user_id,category,status,created,submitted,updated)
                VALUES(123,?,?,?,?,?)''', (category, status, now-60, now-59, now-59)).lastrowid
            store.execute("INSERT INTO messages(ticket_id,direction,text,created) VALUES(?,'in',?,?)",
                          (ticket, 'Synthetic '+category+' ticket for owner-console verification', now-58))
            tickets.append(ticket)
        inbox = AdminInbox(store)
        inbox.command(tickets[0], {'commandId': str(uuid.uuid4()), 'expectedVersion': inbox.ticket(tickets[0])['row_version'],
                                  'actorAccountId': actor, 'type': 'note', 'text': 'Private fixture note; never sent to Telegram'})
        with store.transaction():
            outbox = store.queue_reply(tickets[1], 'Synthetic uncertain answer')
            store.execute("UPDATE outbox SET state='uncertain' WHERE id=?", (outbox,))
        store.execute('''INSERT INTO messages(ticket_id,direction,text,attachment,created)
            VALUES(?,'in','Synthetic attachment',?,?)''', (tickets[0], json.dumps({'kind': 'document',
                'file_name': 'fixture.txt', 'file_size': 12, 'mime_type': 'text/plain',
                'file_id': 'fake-private-file-reference', 'file_unique_id': 'fake-unique-reference'}), now-57))
    finally:
        store.close()
    with AdminServer(args.database, BridgeConfig(secret, args.port)) as server:
        print(json.dumps({'ready': True, 'port': args.port, 'tickets': [str(ticket) for ticket in tickets]}), flush=True)
        server.serve_forever(poll_interval=0.1)


if __name__ == '__main__':
    main()
