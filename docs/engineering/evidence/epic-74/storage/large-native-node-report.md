# R74-S addendum: large semantic nodes with bounded physical fragments

Status: successful bounded feasibility proof, pending independent review. This
supersedes the earlier implication that a 16 KiB physical-object limit must reject
a native paragraph/text node larger than 16 KiB. It introduces no product limit,
shared AST change, dependency, production migration or accepted schema.

One paragraph contains a **24,562-byte UTF-8 text scalar**. Its surrounding native
document is 25,169 bytes, including stable document/paragraph/text IDs, Russian
language metadata, Arabic RTL text, Japanese ruby and multibyte/emoji content.
Thus the scalar is above 16 KiB and below the editor proposal's 32 KiB scalar
envelope; the complete document is below its proposed 1 MiB envelope.

The physical encoding separates an immutable native skeleton/header from the
long scalar's immutable text fragments. The skeleton retains every semantic node
ID and value except the selected text slot. Internal metadata maps that slot to
the original text-node ID. A manifest orders the header and fragment objects using
scoped UUID foreign keys. Physical IDs never become semantic node IDs or appear
as replacement paragraphs in the reconstructed native document.

Initial text fragmentation targets 4,096 UTF-8 bytes and splits only between
Unicode scalar values. The fixture uses six fragments. Editing locates the
touched fragment, changes it, and publishes a new manifest; all other fragment
IDs and the header ID are reused. Small insertions do not rechunk the suffix.
If a touched fragment crosses an 8 KiB high-water mark, only it is re-split; every
physical object's JSONB representation still has a 16 KiB hard bound. The tested
largest physical object was **4,115 bytes** after the short insertion.

The experiment demonstrates one text slot and one bounded manifest page, sufficient
for this proposed scalar envelope. It is not a finished chunker for every native
node or an entire large document. Production integrates this mechanism within the
already-tested semantic block/page tree and generalizes slot traversal/validation;
it must retain the same exact reconstruction and identity invariants.

## Executed assertions

- Save and reassemble the oversized native node; compare the entire semantic
  JSON tree, including order, attributes, ruby, RTL and every stable ID.
- Replace one Unicode scalar in the long text. Only one of six fragments changes;
  the other five and the header retain the same physical IDs.
- Insert ` Ω🌿 ` inside that text. Again, exactly one fragment changes and five
  remain shared. The paragraph and text node IDs do not change.
- Read the initial, replacement and insertion histories ten times each and compare
  complete native JSON to independently constructed expected documents.
- Verify all physical objects remain below 16 KiB and physical UUIDs are distinct
  from the five semantic IDs.
- Attempt direct deletion of a referenced fragment: PostgreSQL returns FK error
  `23503`. Run the bounded candidate collector while all three histories are pinned;
  every historical document remains readable.
- Release only the first two synthetic history pins. Collection reclaims their
  obsolete manifests and changed fragments, retains shared fragments/header, and
  reconstructs the current document unchanged.

The fragment collector uses the same normalized incoming-edge/root checks as the
larger proof, selecting at most eight candidates and at most 32 outgoing edges
per object. This addendum verifies the new fragment graph seam; staging grace,
lease expiry and concurrency/restart were tested in the main experiment and are
not redefined here.

## Measurements

Successful writer run: `proof-02`. Full output is in
[large-node-proof-02-evidence.txt](./large-node-proof-02-evidence.txt), with batches
in [large-node-proof-02-summary.csv](./large-node-proof-02-summary.csv).

| Operation | Row mutations | Heap / TOAST / index allocation | WAL | Elapsed |
|---|---:|---:|---:|---:|
| Initial fragmented save | 23 | 24,576 / 24,576 / 40,960 B | 36,952 B | 33.11 ms |
| Tiny scalar replacement | 11 | 0 / 8,192 / 0 B | 6,872 B | 6.60 ms |
| Short multibyte insertion | 11 | 0 / 0 / 0 B | 6,872 B | 5.30 ms |

Each full historical read uses one payload query returning the ordered header and
six fragments. Initial/replaced histories transfer 25,398 UTF-8 payload bytes per
read; inserted history transfers 25,406. No previous revision is replayed. Ten-read
p50 values were 2.00, 1.29 and 1.19 ms respectively. These are warm-cache local
measurements; ten samples do not establish a production tail-latency SLO.

Writes count inserted object/candidate/edge rows. Relation differences cover each
complete named batch, including indexes and TOAST indexes. Zero allocation can
reuse an already allocated page and does not imply zero WAL. Revision-pin inserts
are common publication bookkeeping outside the measured physical mutation. No
candidate-specific backup/retained-size claim is made.

## Reproduce

From the repository root, choose a fresh suffix and unused loopback port:

```sh
set -o pipefail
R74_LARGE_PORT=15477 bash docs/engineering/evidence/epic-74/storage/run-large-node.sh proof-02 2>&1 | tee /tmp/mnema-r74-large-proof-02.log
```

The runner refuses existing containers; use another suffix to rerun. It uses the
already cached PostgreSQL 18.4 image, JDBC 42.7.13, Jackson 2.21.4/annotations 2.21,
Java 21.0.11 with a 1 GiB heap, and a separate `mnema-r74-large-*` container limited
to two CPUs/2 GiB. Only one client is used. Autovacuum is off for measurement;
durability settings retain PostgreSQL defaults. Statement/lock timeouts are
30/5 seconds. The runner stops and retains its synthetic database after execution.
No dependency installation or shared application configuration is needed.

Final Java SHA-256:
`1da6d1867a8b0e4790777165668c7ed47584f5e67fe397c60febbda000155ce1`.
The full repository quality gate remains with the lead. This addendum proves the
physical-fragment capability and exact semantics, not production rollout.
