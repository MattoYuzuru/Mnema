---
artifact:
  id: public-updates-and-feedback
  type: architecture
  title: "Public events, editorial access and Telegram feedback"
  status: current
  created_at: "2026-10-07"
  updated_at: "2026-10-07"
  owners: ["project-owner"]
---

# Public updates and feedback

The owner requested public release notes/ideas, a private editor and a compact Telegram
inbox. This describes the implementation in this checkout and the separate bot repository.
Mnema production rollout follows the [protected delivery process](../operations/production-delivery.md);
editing this checkout does not make new routes live.

## Events and editorial access

Learning owns events in its existing PostgreSQL database. Angular provides `/events` and
the owner editor `/manage/events`. Source Caddy redirects `admin.mnema.app` to that editor
on `mnema.app`, retaining the existing OAuth callback and Identity CORS boundary.
No new service, database pool, CMS or frontend dependency is required.

The [events contract](../../contracts/events/README.md) owns HTTP, validation, keyset
pagination, publication, version preconditions and command retries. Dates are explicit
calendar dates and may describe plans; there is no scheduled publication. Pages replace
their 50 entries on navigation, bounding DOM/memory. The editor defaults to a draft.
Unsaved edits require explicit discard before switching entries or leaving the route.
An unknown save/delete result retains the exact command and precondition for retry;
that editor cannot be left while the result is uncertain.

Preview/public rendering share a bounded Markdown subset: paragraphs, simple headings,
lists, quotes, bold/italic, inline/fenced code and HTTP(S)/same-origin links. Raw HTML is
text; images/embeds are not rendered. This is not full CommonMark support. There is no
`innerHTML` or sanitizer bypass, following [Angular security guidance](https://angular.dev/best-practices/security).

Static checked-in notes would suffice for releases alone, but the owner also wants ideas,
experiment announcements and publication independent of a frontend deploy. One table and
the existing authenticated API fit that workload; a separate CMS adds another auth and
operations boundary without a current benefit.

`MNEMA_EVENTS_OWNER_ACCOUNT_ID` is an immutable Identity UUID, not an email, username or
delegable admin claim. Empty configuration denies every editor. Learning scopes/token and
live Identity verification apply on every admin request. The browser check controls
presentation; backend authorization is decisive.

During an authorized rollout, resolve the owner's confirmed login to `accountId`, set it
in administrator-owned runtime configuration and verify owner CRUD plus a different
authenticated account's 403. Until the owner identifies that account, leave it empty.
A subdomain confers no permission. V43 adds only `product_event` and indexes. Take the normal
backup/restore rehearsal before deployment. An earlier application can ignore this table
during compatible code rollback; rollback never drops it or erases editorial records.

## Feedback bot

The private [Mnema-Telegram-Bot](https://github.com/MattoYuzuru/Mnema-Telegram-Bot) repository
owns the bot, SQLite, deployment and operational evidence. Local checkout:
`/Users/yuzuru/Projects/Mnema-Telegram-Bot`; public contact:
[@Mnema_Support_Bot](https://t.me/Mnema_Support_Bot).

The bot uses Python standard-library HTTPS, SQLite WAL, long polling and an isolated
systemd service on `keykomi`, opening no inbound port. Inspection before deployment found
Python 3.14.4, roughly 1.3 GB available RAM and 33 GB free disk. These are observations,
not a workload forecast; the initial live process used about 15–16 MB. A separate service
user, root-owned code/secrets, private state directory and daily integrity-checked snapshots
bound its access. The bot's own runbook owns exact paths, rollback and stop conditions.

One inline panel is edited during navigation. Categories: bug, idea, question, other.
Actual correspondence remains messages. SQLite owns tickets, message/file references,
update deduplication/offset and a durable outbox. Attachments are Telegram references,
not automatically downloaded or executed. Per-user limits bound requests, messages and
attachments. A verified numeric Telegram ID authorizes the administrator; `@Keyko_Mi`
only corroborated bootstrap.

Telegram `sendMessage` has no application idempotency key. Ambiguous delivery is marked
uncertain for an explicit operator decision, not blindly repeated. Edited inbox panels
may not create push notifications; `/inbox` remains available. CLI offers bounded JSON
reads and an owner-reviewed reply. Feedback is not automatically passed to an AI provider.
Agents can draft from explicitly selected tickets; sending requires the owner's instruction.

Same-host snapshots cover mistakes, not host/disk loss. Off-host copying and deletion/
retention arrangements remain owner-controlled operations. Do not copy private ticket
bodies or `.env` into public Mnema evidence or Issues. Compact menus/file support follow
official [Telegram features](https://core.telegram.org/bots/features#inline-keyboards) and
[Bot API](https://core.telegram.org/bots/api). A Telegram account is not legal identity
verification or proof of a Mnema account.

## Optional account proof: researched, not active

The initial bot accepts feedback without Mnema login. Its optional hook is disabled until
a separately implemented Identity endpoint exists. An email or username is not sufficient
to mark a Mnema account verified.

Recommended flow: an explicit action in the signed-in profile creates a random 32-byte
base64url challenge. Identity stores only its hash with account UUID, current security
generation, purpose and a short expiry (for example 10 minutes). Open
`https://t.me/Mnema_Support_Bot?start=<challenge>`; the 43-character payload fits Telegram's
64-character limit. The bot calls a fixed HTTPS Identity endpoint with a distinct bot
credential and authenticated Telegram sender ID. Identity atomically checks the current
active account/generation, expiry and purpose, and consumes the challenge. Bind replay
to that same challenge and Telegram ID. Return only the opaque account ID, not email/name.

A forwarded link can propose a different Telegram account, so production integration also
needs visible confirmation in Mnema, cancel/unlink, rate limits and account-deletion
handling. Linking grants no login/admin/learning permissions. Inbox wording describes an
account association, never legal identity. Signed Telegram Login/Mini App auth is an
alternative for a web-facing Telegram experience, unnecessary for this feedback flow.
The recommendation follows the official [deep-linking contract](https://core.telegram.org/bots/features#deep-linking).

## Public disclosure

The footer publishes operator details supplied directly by the owner, existing privacy/
terms pages and the Telegram contact. It does not replace the legal launch checklist or
legal document review. Applicable duties depend on the offered service; the official
[Rospotrebnadzor explanation](https://zpp.rospotrebnadzor.ru/news/federal/513724) informed
making operator information accessible, not a claim of complete compliance.

`/ai` uses provider-neutral copy while preserving foreign-processing, speech-consent and
limited-redaction disclosures. The filter masks detected email, common phone forms and
card numbers; it does not promise removal of names, addresses or all sensitive data.
Checking sources can help understand the material without guaranteeing learning or truth.
