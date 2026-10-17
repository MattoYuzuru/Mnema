#!/bin/sh
# Community/2 (#449): measures every statement of the catalogue routes on a synthetic catalogue and writes the EXPLAIN (ANALYZE, BUFFERS) output of each.
#
#   docs/engineering/evidence/community-decks/epic-3/catalog-scale.sh [decks]
#
# decks is the number of listed decks of the synthetic catalogue (default 100000; the gate of CI runs the same test at 10000). It needs JDK 25 and Docker for Testcontainers
# (see docs/engineering/agent-runbook.md). The test is backend/services/learning/src/test/java/app/mnema/learning/community/CatalogScaleIntegrationTest.java: it seeds public_deck and one
# rank generation with SQL, runs 5 warm-up and 30 timed runs of every statement (a page and its count, as the route runs them), EXPLAINs each one and asserts index usage and the
# budget of the architecture (p95 below 50 ms at 10^4, below 200 ms at 10^5). The report is copied next to this script as catalog-scale-<decks>.txt.
set -eu
decks="${1:-100000}"
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../../../../.." && pwd)"
cd "$root/backend"
# --rerun: the number of decks is an environment variable, not an input of the Gradle task
MNEMA_CATALOG_SCALE="$decks" ./gradlew :services:learning:test --rerun --tests '*CatalogScaleIntegrationTest'
cp "services/learning/build/community-catalog-scale-$decks.txt" "$here/catalog-scale-$decks.txt"
echo "wrote $here/catalog-scale-$decks.txt"
