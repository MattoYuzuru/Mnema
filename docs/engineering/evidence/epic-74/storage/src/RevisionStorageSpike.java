package app.mnema.learning.storage_spike;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Isolated research executable. No application schema or Spring configuration is used. */
public final class RevisionStorageSpike {
    private static final int FANOUT = 32;
    private static final String URL = "jdbc:postgresql://127.0.0.1:15474/mnema_r74_storage";
    private final Connection db;
    private long reads;
    private long writes;

    private RevisionStorageSpike(Connection db) { this.db = db; }

    public static void main(String[] args) throws Exception {
        try (Connection connection = connect()) {
            var spike = new RevisionStorageSpike(connection);
            spike.schema();
            System.out.println("ENV," + spike.string("SELECT version()"));
            System.out.println("CONFIG," + spike.string("SELECT string_agg(name || '=' || setting, ';' ORDER BY name) FROM pg_settings WHERE name IN ('shared_buffers','fsync','full_page_writes','synchronous_commit','default_toast_compression','max_connections','checkpoint_timeout')"));
            System.out.println("METRIC,label,operations,logical_writes,sql_reads,heap_bytes_delta,toast_bytes_delta,index_bytes_delta,wal_bytes,p50_ms,p95_ms,p99_ms");
            for (int size : new int[]{1_000, 10_000, 50_000, 100_000}) spike.members(size);
            spike.longDocument();
            spike.orderProperties();
            spike.publicationSafety();
            spike.reachabilitySafety();
            spike.inventory();
            System.out.println("PASS,all assertions");
        }
    }

    private static Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection(URL, "mnema", "synthetic-only");
        try (var statement = connection.createStatement()) {
            statement.execute("SET search_path TO r74_storage; SET statement_timeout='30s'; SET lock_timeout='5s'");
        }
        return connection;
    }

    private void schema() throws SQLException {
        // A fresh container/database is mandatory. Never drop an existing schema to rerun.
        execute("CREATE SCHEMA r74_storage");
        execute("""
                CREATE TABLE block(id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, payload jsonb NOT NULL);
                CREATE TABLE page(id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, leaf boolean NOT NULL,
                    refs bigint[] NOT NULL, counts bigint[] NOT NULL,
                    CHECK (cardinality(refs) BETWEEN 1 AND 32), CHECK(cardinality(refs)=cardinality(counts)));
                CREATE TABLE revision(id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, root bigint NOT NULL REFERENCES page,
                    metadata jsonb NOT NULL DEFAULT '{}', parent bigint REFERENCES revision);
                CREATE TABLE deck(id bigint PRIMARY KEY, head bigint NOT NULL REFERENCES revision, version bigint NOT NULL DEFAULT 0,
                    source_deck bigint, source_base bigint REFERENCES revision);
                CREATE TABLE receipt(deck bigint NOT NULL REFERENCES deck, command text NOT NULL, payload text NOT NULL,
                    result bigint NOT NULL REFERENCES revision, PRIMARY KEY(deck,command));
                CREATE TABLE pin(kind text NOT NULL, id bigint NOT NULL, root bigint NOT NULL REFERENCES page, PRIMARY KEY(kind,id));
                CREATE TABLE progress(deck bigint NOT NULL, member bigint NOT NULL, value integer NOT NULL, PRIMARY KEY(deck,member));
                CREATE TABLE delta_checkpoint(id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, members bigint[], document jsonb);
                CREATE TABLE delta(id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, checkpoint bigint NOT NULL REFERENCES delta_checkpoint,
                    parent bigint REFERENCES delta, depth int NOT NULL CHECK(depth BETWEEN 0 AND 32), position int, replacement bigint, text_patch text);
                CREATE UNIQUE INDEX delta_checkpoint_depth ON delta(checkpoint,depth);
                CREATE TABLE delta_head(id bigint PRIMARY KEY, document jsonb);
                CREATE TABLE delta_head_node(id bigint PRIMARY KEY, payload jsonb NOT NULL);
                CREATE TABLE eligible(snapshot bigint NOT NULL, ordinal bigint NOT NULL, member bigint NOT NULL, PRIMARY KEY(snapshot,ordinal));
                """);
    }

    private record Node(boolean leaf, List<Long> refs, List<Long> counts) {
        long size() { return leaf ? refs.size() : counts.stream().mapToLong(Long::longValue).sum(); }
    }

    private long save(Node node) throws SQLException {
        writes++;
        try (var statement = db.prepareStatement("INSERT INTO page(leaf,refs,counts) VALUES (?,?,?) RETURNING id")) {
            statement.setBoolean(1, node.leaf);
            statement.setArray(2, db.createArrayOf("bigint", node.refs.toArray()));
            statement.setArray(3, db.createArrayOf("bigint", node.counts.toArray()));
            try (var rows = statement.executeQuery()) { rows.next(); return rows.getLong(1); }
        }
    }

    private Node load(long id) throws SQLException {
        reads++;
        try (var statement = db.prepareStatement("SELECT leaf,refs,counts FROM page WHERE id=?")) {
            statement.setLong(1, id);
            try (var rows = statement.executeQuery()) {
                require(rows.next(), "missing page " + id);
                return new Node(rows.getBoolean(1), new ArrayList<>(Arrays.asList((Long[]) rows.getArray(2).getArray())),
                        new ArrayList<>(Arrays.asList((Long[]) rows.getArray(3).getArray())));
            }
        }
    }

    private long build(List<Long> keys, List<Long> values) throws SQLException {
        List<Long> level = new ArrayList<>();
        List<Long> counts = new ArrayList<>();
        for (int i = 0; i < keys.size(); i += FANOUT) {
            int end = Math.min(i + FANOUT, keys.size());
            level.add(save(new Node(true, keys.subList(i, end), values.subList(i, end))));
            counts.add((long) end - i);
        }
        while (level.size() > 1) {
            List<Long> next = new ArrayList<>();
            List<Long> sizes = new ArrayList<>();
            for (int i = 0; i < level.size(); i += FANOUT) {
                int end = Math.min(i + FANOUT, level.size());
                Node node = new Node(false, level.subList(i, end), counts.subList(i, end));
                next.add(save(node)); sizes.add(node.size());
            }
            level = next; counts = sizes;
        }
        return level.getFirst();
    }

    // Sequence B-tree: leaf refs are stable member/node keys, counts are selected content refs.
    // Internal refs are child pages, counts are subtree cardinalities. No history replay.
    private long mutate(long root, int position, long key, long value, String operation) throws SQLException {
        List<Long> replacement = change(root, position, key, value, operation);
        if (replacement.size() == 1) return replacement.getFirst();
        require(!replacement.isEmpty(), "empty roots require an explicit empty-manifest representation");
        return save(parent(replacement));
    }

    private Node parent(List<Long> children) throws SQLException {
        List<Long> counts = new ArrayList<>();
        for (long child : children) counts.add(load(child).size());
        return new Node(false, children, counts);
    }

    private List<Long> change(long id, int position, long key, long value, String operation) throws SQLException {
        Node node = load(id);
        if (node.leaf) {
            switch (operation) {
                case "insert" -> { node.refs.add(position, key); node.counts.add(position, value); }
                case "delete" -> { node.refs.remove(position); node.counts.remove(position); }
                case "edit" -> node.counts.set(position, value);
                default -> throw new IllegalArgumentException(operation);
            }
        } else {
            int child = 0;
            while (child < node.refs.size() - 1 && position >= node.counts.get(child)) {
                position -= node.counts.get(child++).intValue();
            }
            List<Long> replacement = change(node.refs.get(child), position, key, value, operation);
            node.refs.remove(child); node.counts.remove(child);
            for (int i = 0; i < replacement.size(); i++) {
                node.refs.add(child + i, replacement.get(i));
                node.counts.add(child + i, load(replacement.get(i)).size());
            }
        }
        if (node.refs.isEmpty()) return List.of();
        if (node.refs.size() <= FANOUT) return List.of(save(node));
        int half = node.refs.size() / 2;
        return List.of(save(new Node(node.leaf, node.refs.subList(0, half), node.counts.subList(0, half))),
                save(new Node(node.leaf, node.refs.subList(half, node.refs.size()), node.counts.subList(half, node.refs.size()))));
    }

    private List<Long> page(long root, int offset, int limit, boolean values) throws SQLException {
        List<Long> output = new ArrayList<>();
        slice(root, offset, limit, values, output);
        return output;
    }

    private void slice(long id, int offset, int limit, boolean values, List<Long> output) throws SQLException {
        Node node = load(id);
        if (node.leaf) {
            for (int i = offset; i < node.refs.size() && output.size() < limit; i++)
                output.add((values ? node.counts : node.refs).get(i));
        } else {
            for (int i = 0; i < node.refs.size() && output.size() < limit; i++) {
                long size = node.counts.get(i);
                if (offset >= size) offset -= (int) size;
                else { slice(node.refs.get(i), offset, limit, values, output); offset = 0; }
            }
        }
    }

    private void members(int size) throws Exception {
        List<Long> keys = new ArrayList<>();
        for (long i = 1; i <= size; i++) keys.add(i);
        final long[] root = {0};
        measure("tree_" + size + "_initial", 1, () -> root[0] = transaction(() -> build(keys, keys)));
        long original = root[0];
        measure("tree_" + size + "_metadata", 25, () -> revision(root[0]));
        measure("tree_" + size + "_single_edit", 25,
                () -> root[0] = transaction(() -> mutate(root[0], size / 2, 0, 900001, "edit")));
        measure("tree_" + size + "_delete", 1,
                () -> root[0] = transaction(() -> mutate(root[0], size / 2, 0, 0, "delete")));
        measure("tree_" + size + "_reorder", 1, () -> root[0] = transaction(() -> {
            long removed = mutate(root[0], 0, 0, 0, "delete");
            return mutate(removed, size - 2, 1, 1, "insert");
        }));
        measure("tree_" + size + "_bulk_100", 1, () -> root[0] = transaction(() -> {
            long current = root[0];
            for (int i = 0; i < 100; i++) current = mutate(current, i, 0, 900002, "edit");
            return current;
        }));
        measure("tree_" + size + "_first_page", 25, () -> require(page(root[0], 0, 100, false).size() == 100, "first page"));
        measure("tree_" + size + "_historical_page", 25, () -> require(page(original, size / 2, 100, false).size() == 100, "history page"));
        long checkpoint = insertArray(keys);
        final long[] deltaHead = {0};
        measure("delta_" + size + "_32_edits", 32, () -> deltaHead[0] = insertDelta(checkpoint, deltaHead[0], 1 + (int) scalar("SELECT coalesce(max(depth),0) FROM delta WHERE checkpoint=" + checkpoint), size / 2, 900001));
        measure("delta_" + size + "_checkpoint", 1, () -> insertArray(keys));
        measure("delta_" + size + "_history_read32", 25, () -> {
            reads += 2;
            Long[] materialized;
            try (var statement = db.createStatement(); var rows = statement.executeQuery("SELECT members FROM delta_checkpoint WHERE id=" + checkpoint)) {
                rows.next(); materialized = (Long[]) rows.getArray(1).getArray();
            }
            try (var statement = db.createStatement(); var rows = statement.executeQuery("SELECT position,replacement FROM delta WHERE checkpoint=" + checkpoint + " ORDER BY depth")) {
                int patches = 0;
                while (rows.next()) { materialized[rows.getInt(1)] = rows.getLong(2); patches++; }
                require(patches == 32, "bounded patch count");
            }
            require(materialized.length == size && materialized[size / 2] == 900001, "historical patch reconstruction");
        });
        if (size == 100_000) {
            long rev = revision(original);
            execute("INSERT INTO deck(id,head) VALUES (100," + rev + ")");
            final long[] forkId = {101};
            measure("tree_100000_fork_chain", 25, () -> {
                transaction(() -> {
                    long source = forkId[0] - 1;
                    long forkRevision = revision(original);
                    execute("INSERT INTO deck(id,head,source_deck,source_base) SELECT " + forkId[0]++ + "," + forkRevision + ",id,head FROM deck WHERE id=" + source);
                    writes++;
                    return forkRevision;
                });
                reads++;
                long selected = scalar("SELECT r.root FROM deck d JOIN revision r ON r.id=d.head WHERE d.id=" + (forkId[0] - 1));
                require(page(selected, 0, 100, false).size() == 100, "fork first page");
            });
            execute("INSERT INTO progress VALUES (100,1,7),(101,1,0)");
            require(scalar("SELECT value FROM progress WHERE deck=101 AND member=1") == 0, "independent logical progress");
            execute("DELETE FROM deck WHERE id=100");
            require(page(original, 0, 1, false).getFirst() == 1, "source removal preserves fork");
            measure("rare_pool_prepare_100000", 1, () -> {
                execute("INSERT INTO eligible SELECT " + rev + ", row_number() OVER (ORDER BY n)-1,n FROM generate_series(1,100000) n WHERE n%10000=0");
                writes += 10;
            });
            var visited = new java.util.HashSet<Long>();
            for (int cursor = 0; cursor < 10; cursor++) visited.add(Math.floorMod(7L * cursor + 19, 10L));
            require(visited.size() == 10, "coprime seeded permutation visits each eligible ordinal once");
            measure("rare_eligibility_10_of_100000", 25, () -> {
                reads++;
                long ordinal = Math.floorMod(7 * 3 + 19, 10);
                require(scalar("SELECT member FROM eligible WHERE snapshot=" + rev + " AND ordinal=" + ordinal) > 0, "indexed eligible ordinal");
            });
            System.out.println("PLAN," + string("EXPLAIN (FORMAT JSON) SELECT member FROM eligible WHERE snapshot=" + rev + " AND ordinal=0"));
            System.out.println("PASS,fork namespace/source deletion; filtered pool prepared outside read; affine coprime permutation v1");
        }
    }

    private long insertArray(List<Long> keys) throws SQLException {
        writes++;
        try (var statement = db.prepareStatement("INSERT INTO delta_checkpoint(members) VALUES (?) RETURNING id")) {
            statement.setArray(1, db.createArrayOf("bigint", keys.toArray()));
            try (var rows = statement.executeQuery()) { rows.next(); return rows.getLong(1); }
        }
    }

    private long insertDelta(long checkpoint, long parent, int depth, int position, long value) throws SQLException {
        writes++;
        return scalar("INSERT INTO delta(checkpoint,parent,depth,position,replacement) VALUES (" + checkpoint + "," + (parent == 0 ? "NULL" : parent) + "," + depth + "," + position + "," + value + ") RETURNING id");
    }

    private long revision(long root) throws SQLException {
        writes++;
        return scalar("INSERT INTO revision(root) VALUES (" + root + ") RETURNING id");
    }

    private String payload(int seed) {
        Random random = new Random(seed);
        StringBuilder text = new StringBuilder(2048);
        for (int i = 0; i < 2048; i++) text.append((char) ('a' + random.nextInt(26)));
        return text.toString();
    }

    private long block(int seed) throws SQLException {
        writes++;
        try (var statement = db.prepareStatement("INSERT INTO block(payload) VALUES (jsonb_build_object('id',?::text,'type','paragraph','version',1,'text',?::text)) RETURNING id")) {
            statement.setString(1, "node-" + Math.floorMod(seed, 128)); statement.setString(2, payload(seed));
            try (var rows = statement.executeQuery()) { rows.next(); return rows.getLong(1); }
        }
    }

    private void longDocument() throws Exception {
        List<Long> keys = new ArrayList<>(); List<Long> blocks = new ArrayList<>();
        for (int i = 0; i < 128; i++) { keys.add((long) i); blocks.add(block(i)); }
        final long[] root = {transaction(() -> build(keys, blocks))};
        List<Long> history = new ArrayList<>(); history.add(root[0]);
        final int[] edit = {0};
        measure("tree_long_1000_edits", 1000, () -> {
            root[0] = transaction(() -> mutate(root[0], 0, 0, block(128 * ++edit[0]), "edit"));
            history.add(root[0]);
        });
        for (int index : new int[]{0, 1, 31, 32, 999, 1000}) {
            measure("tree_long_read_revision_" + index, 25, () -> {
                List<Long> ids = page(history.get(index), 0, 128, true);
                reads++;
                try (var statement = db.prepareStatement("SELECT payload FROM block WHERE id=ANY(?)")) {
                    statement.setArray(1, db.createArrayOf("bigint", ids.toArray()));
                    try (var rows = statement.executeQuery()) { int found = 0; while (rows.next()) { rows.getString(1); found++; } require(found == 128, "full document"); }
                }
            });
        }
        execute("INSERT INTO delta_head SELECT 1,jsonb_agg(payload ORDER BY id) FROM block WHERE id<=128");
        final long[] checkpoint = {scalar("INSERT INTO delta_checkpoint(document) SELECT document FROM delta_head WHERE id=1 RETURNING id")};
        final int[] depth = {0};
        measure("delta_jsonb_head_long_1000_edits", 1000, () -> transaction(() -> {
            try (var statement = db.prepareStatement("UPDATE delta_head SET document=jsonb_set(document,'{0,text}',to_jsonb(?::text)) WHERE id=1")) {
                statement.setString(1, payload(++edit[0])); statement.executeUpdate(); writes++;
            }
            if (depth[0] == 32) {
                checkpoint[0] = scalar("INSERT INTO delta_checkpoint(document) SELECT document FROM delta_head WHERE id=1 RETURNING id");
                depth[0] = 0; writes++;
            } else {
                try (var statement = db.prepareStatement("INSERT INTO delta(checkpoint,depth,position,text_patch) VALUES (?,?,0,?)")) {
                    statement.setLong(1, checkpoint[0]); statement.setInt(2, ++depth[0]); statement.setString(3, payload(edit[0])); statement.executeUpdate(); writes++;
                }
            }
            return 0L;
        }));
        measure("delta_long_historical_read32", 25, () -> {
            // SQL reconstruction includes checkpoint payload and <=32 replacements; no full history scan.
            reads++;
            require(scalar("WITH RECURSIVE replay(depth,doc) AS (SELECT 0,document FROM delta_checkpoint WHERE id=(SELECT min(checkpoint) FROM delta WHERE text_patch IS NOT NULL) UNION ALL SELECT d.depth,jsonb_set(r.doc,'{0,text}',to_jsonb(d.text_patch)) FROM replay r JOIN delta d ON d.depth=r.depth+1 AND d.checkpoint=(SELECT min(checkpoint) FROM delta WHERE text_patch IS NOT NULL)) SELECT octet_length(doc::text) FROM replay ORDER BY depth DESC LIMIT 1") > 262144, "reconstructed document");
        });
        execute("INSERT INTO delta_head_node SELECT id,payload FROM block WHERE id<=128");
        checkpoint[0] = scalar("INSERT INTO delta_checkpoint(document) SELECT jsonb_agg(payload ORDER BY id) FROM delta_head_node RETURNING id");
        depth[0] = 0;
        measure("delta_node_head_long_1000_edits", 1000, () -> transaction(() -> {
            try (var statement = db.prepareStatement("UPDATE delta_head_node SET payload=jsonb_set(payload,'{text}',to_jsonb(?::text)) WHERE id=1")) {
                statement.setString(1, payload(++edit[0])); statement.executeUpdate(); writes++;
            }
            if (depth[0] == 32) {
                checkpoint[0] = scalar("INSERT INTO delta_checkpoint(document) SELECT jsonb_agg(payload ORDER BY id) FROM delta_head_node RETURNING id");
                depth[0] = 0; writes++;
            } else {
                try (var statement = db.prepareStatement("INSERT INTO delta(checkpoint,depth,position,text_patch) VALUES (?,?,0,?)")) {
                    statement.setLong(1, checkpoint[0]); statement.setInt(2, ++depth[0]); statement.setString(3, payload(edit[0])); statement.executeUpdate(); writes++;
                }
            }
            return 0L;
        }));
        System.out.println("PASS,long document block reuse; history-independent page reads; delta maximum depth 32");
    }

    private void orderProperties() throws Exception {
        List<Long> expected = new ArrayList<>(); for (long i = 0; i < 1050; i++) expected.add(i);
        long root = transaction(() -> build(expected, expected));
        Random random = new Random(74);
        for (int step = 0; step < 600; step++) {
            int position = random.nextInt(expected.size());
            if (step % 3 == 0) { long key = 1000000L + step; root = mutate(root, position, key, key, "insert"); expected.add(position, key); }
            else if (step % 3 == 1) { root = mutate(root, position, 0, 0, "delete"); expected.remove(position); }
            else {
                long key = expected.remove(position); root = mutate(root, position, 0, 0, "delete");
                int target = random.nextInt(expected.size()); root = mutate(root, target, key, key, "insert"); expected.add(target, key);
            }
            require(page(root, 0, expected.size(), false).equals(expected), "sequence property " + step);
        }
        System.out.println("PASS,600 seeded insert/delete/move operations across leaf/internal splits and empty-page removal");
    }

    private void publicationSafety() throws Exception {
        long root = scalar("SELECT min(id) FROM page");
        long rev = revision(root);
        execute("INSERT INTO deck(id,head) VALUES (999," + rev + ")");
        var ready = new CountDownLatch(2); var go = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                final int writer = i;
                results.add(executor.submit(() -> {
                    try (Connection connection = connect()) {
                        ready.countDown(); require(go.await(10, TimeUnit.SECONDS), "CAS start");
                        return new RevisionStorageSpike(connection).publish("writer-" + writer, "payload", 0, root) != 0;
                    }
                }));
            }
            require(ready.await(10, TimeUnit.SECONDS), "writers ready"); go.countDown();
            int winners = 0; for (var result : results) if (result.get(15, TimeUnit.SECONDS)) winners++;
            require(winners == 1, "exactly one CAS winner");
        }
        String winner = string("SELECT command FROM receipt WHERE deck=999");
        long published = scalar("SELECT head FROM deck WHERE id=999");
        require(publish(winner, "payload", 0, root) == published, "retry returns stored result despite stale expected head");
        try { publish(winner, "different", 1, root); throw new AssertionError("payload conflict missing"); }
        catch (IllegalArgumentException expected) { require(expected.getMessage().equals("idempotency conflict"), "payload conflict"); }
        // Simulate process loss: close its independent connection without commit after staging committed.
        long staged = save(load(root));
        try (Connection worker = connect()) {
            worker.setAutoCommit(false);
            new RevisionStorageSpike(worker).execute("UPDATE deck SET head=" + revision(staged) + ",version=version+1 WHERE id=999");
            require(scalar("SELECT head FROM deck WHERE id=999") == published, "reader sees published head before commit");
        }
        require(scalar("SELECT head FROM deck WHERE id=999") == published, "crashed worker leaves head unchanged");
        execute("INSERT INTO pin VALUES ('fork',1," + root + "),('draft',1," + staged + "),('attempt',1," + root + ")");
        require(scalar("SELECT count(*) FROM pin p JOIN page b ON b.id=p.root") == 3, "retention roots exist");
        try { execute("DELETE FROM page WHERE id=" + staged); throw new AssertionError("reachable delete allowed"); }
        catch (SQLException expected) { require("23503".equals(expected.getSQLState()), "FK protects direct root"); }
        System.out.println("PASS,CAS one winner; identical retry; conflicting retry; disconnected worker rollback; direct fork/draft/attempt root protection");
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            final int[] round = {0};
            measure("hot_head_4_writers_4_readers", 20, () -> {
                long expectedVersion = scalar("SELECT version FROM deck WHERE id=999");
                var start = new CountDownLatch(1);
                List<java.util.concurrent.Future<Long>> results = new ArrayList<>();
                int currentRound = round[0]++;
                for (int actor = 0; actor < 8; actor++) {
                    final int index = actor;
                    results.add(workers.submit(() -> {
                        try (Connection connection = connect()) {
                            var client = new RevisionStorageSpike(connection);
                            require(start.await(10, TimeUnit.SECONDS), "hot start");
                            if (index < 4) return client.publish("hot-" + currentRound + "-" + index, "payload", expectedVersion, root);
                            long observedRoot = client.scalar("SELECT r.root FROM deck d JOIN revision r ON r.id=d.head WHERE d.id=999");
                            require(client.page(observedRoot, 0, 10, false).size() == 10, "concurrent saved snapshot");
                            return -1L;
                        }
                    }));
                }
                start.countDown();
                int wins = 0;
                for (var result : results) if (result.get(15, TimeUnit.SECONDS) > 0) wins++;
                require(wins == 1, "one hot publication per expected version");
            });
        }
        System.out.println("PASS,20 rounds hot deck with four publishers and four saved-snapshot readers; zero scheduler tables used");
    }

    private void reachabilitySafety() throws Exception {
        long leaf = save(new Node(true, new ArrayList<>(List.of(777L)), new ArrayList<>(List.of(777L))));
        long parent = save(new Node(false, new ArrayList<>(List.of(leaf)), new ArrayList<>(List.of(1L))));
        long orphan = save(new Node(true, new ArrayList<>(List.of(888L)), new ArrayList<>(List.of(888L))));
        execute("INSERT INTO pin VALUES ('draft',2," + parent + ")");
        require(!sweepCandidate(leaf), "descendant of draft is reachable");
        require(sweepCandidate(orphan), "unreachable staged object collected");
        // Candidate discovered before new pin commits must be revalidated after taking the scope lock.
        long raced = save(new Node(true, new ArrayList<>(List.of(999L)), new ArrayList<>(List.of(999L))));
        try (Connection publisher = connect(); var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            publisher.setAutoCommit(false);
            new RevisionStorageSpike(publisher).execute("SELECT pg_advisory_xact_lock_shared(740074)");
            new RevisionStorageSpike(publisher).execute("INSERT INTO pin VALUES ('attempt',2," + raced + ")");
            var ready = new CountDownLatch(1);
            var result = workers.submit(() -> {
                try (Connection collector = connect()) {
                    ready.countDown();
                    return new RevisionStorageSpike(collector).sweepCandidate(raced);
                }
            });
            require(ready.await(10, TimeUnit.SECONDS), "collector started");
            publisher.commit();
            require(!result.get(15, TimeUnit.SECONDS), "new root survives stale candidate discovery");
        }
        // A crashed collector's deletion remains invisible and rolls back on disconnect.
        long crashOrphan = save(new Node(true, new ArrayList<>(List.of(123L)), new ArrayList<>(List.of(123L))));
        try (Connection collector = connect()) {
            collector.setAutoCommit(false);
            new RevisionStorageSpike(collector).execute("DELETE FROM page WHERE id=" + crashOrphan);
        }
        require(load(crashOrphan).refs.getFirst() == 123, "collector crash rollback");
        System.out.println("PASS,transitive draft reachability; orphan reclamation; new attempt pin versus stale GC candidate; collector disconnect rollback");
        System.out.println("LIMIT,GC revalidation scans one synthetic scope; production needs resumable bounded marking, grace epochs and lineage-scoped locks; no production GC or retention policy implemented");
    }

    private boolean sweepCandidate(long candidate) throws Exception {
        return transaction(() -> {
            execute("SELECT pg_advisory_xact_lock(740074)");
            // READ COMMITTED obtains this snapshot after the advisory lock, including newly committed roots.
            return scalar("""
                    WITH RECURSIVE reachable(id) AS (
                        SELECT root FROM pin UNION SELECT root FROM revision
                        UNION SELECT child FROM reachable r JOIN page p ON p.id=r.id
                            CROSS JOIN LATERAL unnest(p.refs) child WHERE NOT p.leaf
                    ), removed AS (
                        DELETE FROM page WHERE id=%d AND NOT EXISTS (SELECT 1 FROM reachable WHERE id=%d) RETURNING id
                    ) SELECT count(*) FROM removed
                    """.formatted(candidate, candidate));
        }) == 1;
    }

    private void inventory() throws SQLException {
        for (String table : List.of("block", "page", "revision", "deck", "receipt", "pin", "progress", "delta_checkpoint", "delta", "delta_head", "delta_head_node", "eligible")) {
            System.out.println("ROWS," + table + "," + scalar("SELECT count(*) FROM " + table));
        }
        Size total = size();
        System.out.println("FINAL_BYTES,heap=" + total.heap + ",toast=" + total.toastBytes + ",indexes=" + total.indexes);
    }

    private long publish(String command, String payload, long expected, long root) throws Exception {
        return transaction(() -> {
            try (var lock = db.createStatement(); var row = lock.executeQuery("SELECT version FROM deck WHERE id=999 FOR UPDATE")) { require(row.next(), "deck exists"); }
            try (var receipt = db.prepareStatement("SELECT payload,result FROM receipt WHERE deck=999 AND command=?")) {
                receipt.setString(1, command);
                try (var rows = receipt.executeQuery()) {
                    if (rows.next()) { if (!payload.equals(rows.getString(1))) throw new IllegalArgumentException("idempotency conflict"); return rows.getLong(2); }
                }
            }
            if (scalar("SELECT version FROM deck WHERE id=999") != expected) return 0L;
            long revision = revision(root);
            execute("UPDATE deck SET head=" + revision + ",version=version+1 WHERE id=999 AND version=" + expected);
            try (var receipt = db.prepareStatement("INSERT INTO receipt VALUES (999,?,?,?)")) {
                receipt.setString(1, command); receipt.setString(2, payload); receipt.setLong(3, revision); receipt.executeUpdate();
            }
            return revision;
        });
    }

    private record Size(long heap, long toastBytes, long indexes, long wal) { }

    private Size size() throws SQLException {
        try (var statement = db.createStatement(); var rows = statement.executeQuery("""
                SELECT sum(pg_relation_size(c.oid)),
                    sum(CASE WHEN c.reltoastrelid=0 THEN 0 ELSE pg_relation_size(c.reltoastrelid) END),
                    sum(pg_indexes_size(c.oid) + CASE WHEN c.reltoastrelid=0 THEN 0 ELSE pg_indexes_size(c.reltoastrelid) END),
                    pg_wal_lsn_diff(pg_current_wal_insert_lsn(),'0/0')::bigint
                FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname='r74_storage' AND c.relkind='r'
                """)) {
            rows.next(); return new Size(rows.getLong(1), rows.getLong(2), rows.getLong(3), rows.getLong(4));
        }
    }

    private void measure(String label, int count, Work action) throws Exception {
        Size before = size(); long initialReads = reads; long initialWrites = writes;
        double[] times = new double[count];
        for (int i = 0; i < count; i++) { long start = System.nanoTime(); action.run(); times[i] = (System.nanoTime() - start) / 1_000_000.0; }
        Size after = size(); Arrays.sort(times);
        System.out.printf(java.util.Locale.ROOT, "METRIC,%s,%d,%d,%d,%d,%d,%d,%d,%.3f,%.3f,%.3f%n", label, count,
                writes-initialWrites, reads-initialReads, after.heap-before.heap, after.toastBytes-before.toastBytes,
                after.indexes-before.indexes, after.wal-before.wal, percentile(times,.5),percentile(times,.95),percentile(times,.99));
    }

    private static double percentile(double[] sorted, double percentile) { return sorted[(int) Math.ceil(sorted.length * percentile)-1]; }
    @FunctionalInterface private interface Work { void run() throws Exception; }
    @FunctionalInterface private interface SqlWork { long run() throws Exception; }

    private long transaction(SqlWork action) throws Exception {
        db.setAutoCommit(false);
        try { long result = action.run(); db.commit(); return result; }
        catch (Exception | AssertionError failure) { db.rollback(); throw failure; }
        finally { db.setAutoCommit(true); }
    }

    private void execute(String sql) throws SQLException { try (var statement = db.createStatement()) { statement.execute(sql); } }
    private long scalar(String sql) throws SQLException { try (var statement = db.createStatement(); var rows = statement.executeQuery(sql)) { rows.next(); return rows.getLong(1); } }
    private String string(String sql) throws SQLException { try (var statement = db.createStatement(); var rows = statement.executeQuery(sql)) { rows.next(); return rows.getString(1); } }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
