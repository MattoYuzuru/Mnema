# Counted-page editing contract

Engineering selection for Epic #74, 2026-09-12, within the owner's accepted
PostgreSQL immutable blocks/pages design. Implementation is not yet delivered.
Extends the [revision storage boundary](revision-storage-and-runtime-boundaries.md)
without changing K1 objects or the K2 native wire format. The lead owns this contract.

## Wire and rank boundaries

Pages retain `{codec:1, role, treeHeight, counts}` with ordered normalized FK edges;
each count is the corresponding child's entry count, not its byte size.
Profiles select role, leaf rank, maximum total entries and empty-root permission.
Native uses `nodes`, rank4, max10,000, no empty root. Membership uses `members`,
rank10 and permits an empty root. Exercise membership uses `exercises`, rank10.
Do not make the native decoder accept deck roles.

Empty deck roots are two separate K1 PAGE objects (encodingVersion1, dagRank10,
edges[]), with exact payloads:

```json
{"codec":1,"role":"members","treeHeight":0,"counts":[]}
{"codec":1,"role":"exercises","treeHeight":0,"counts":[]}
```

Empty is legal only for a height0 root. Non-root pages have16–32 children;
internal roots have2–32. A one-child internal root collapses. Rank is leafRank
plus treeHeight. Content roots remain rank8; future member/exercise descriptors
are rank9 with physical targets at most rank8 and no more than32 edges. Exercise
bindings use semantic IDs, not physical edges to another rank9 descriptor.
For100,000 entries at occupancy16–32, membership height is at most3, rank13.

## Edit behavior

Insert at an ordinal, delete with expected entry key, and move one entry using
destination-after-removal semantics. Validate range, role, scope, counts, rank and
expected key. An overflowing33-child page splits16/17; underflow borrows or merges
and updates ancestor counts. Return only reachable new objects in postorder;
old roots/objects are unchanged. A move stages neither its intermediate delete
root nor unreachable intermediate pages. Fork-of-fork may select the same root;
this creates no shared logical item identity or authorization.

The native structural adapter checks explicit insert/delete/move-subtree intent
against a validated resulting document. Match records by UUID value, not ordinal;
preserve UUID spelling, opaque/optional content and unchanged fragments. Update
preorder entries and parent child counts. Reject root move, descendant-cycle move
and a resulting document inconsistent with the command.

Full-body native planning remains O(nodes + bytes); physical writes are
O(changed entries × height) plus changed records/fragments. A large subtree move
counts all its entries as changed: optimal range cut/join is not promised here.
Bounded ordinal lookup/first100 reads must not load the entire membership.

## Ownership and verification

K3 owns pure page algorithms, native structural adaptation and scoped tests.
The lead owns Deck API/V3, same-scope references, ACL, source preparation leases,
durable pins, revisions/head/CAS/receipts and atomic publication. K3 introduces no
endpoint, migration, scheduler, worker, dependency or deployment.

Required evidence: split/borrow/merge/collapse boundaries; seeded operations against
an independent list; counts/occupancy/order and old-root immutability; multilingual
native round-trip/reuse;10k/50k/100k membership reads/writes; actual PostgreSQL
shared-root branches and GC pin protection. Synthetic physical forks do not prove
domain ACL/concurrent publication; those remain lead-owned integration acceptance.
