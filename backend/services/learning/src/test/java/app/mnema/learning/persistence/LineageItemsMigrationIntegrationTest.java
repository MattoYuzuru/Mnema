package app.mnema.learning.persistence;

import app.mnema.learning.support.PostgresIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V51 (Share/4, #426) on data written at the V49 shape: every per-deck row gets its deck's scope, the media reference keeps
 * the asset owner, all constraints are validated and the immutability guards are enabled again. The second test fixes the exact
 * set of key constraints of the item tables, so the next change (Share/5 drops {@code item_revision_origin_key} together with
 * the bindings' deck keys; Updates/2 drops the per-deck sequence key) has to change it consciously.
 */
class LineageItemsMigrationIntegrationTest extends PostgresIntegrationTest {
    private final UUID owner1 = UUID.randomUUID();
    private final UUID owner2 = UUID.randomUUID();
    private final UUID scope1 = UUID.randomUUID();
    private final UUID scope2 = UUID.randomUUID();
    private final UUID deck1 = UUID.randomUUID();
    private final UUID deck2 = UUID.randomUUID();
    private final UUID member1 = UUID.randomUUID();
    private final UUID member2 = UUID.randomUUID();
    private final UUID first = UUID.randomUUID();
    private final UUID second = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final UUID asset = UUID.randomUUID();
    private final UUID deckRevision1 = UUID.randomUUID();
    private final UUID deckRevision1b = UUID.randomUUID();
    private final UUID deckRevision2 = UUID.randomUUID();
    private final UUID draft = UUID.randomUUID();
    private final UUID convertedNote = UUID.randomUUID();

    @Test
    void dataWrittenAtTheV49ShapeIsBackfilledByDeckAndEveryGuardIsEnabledAgain() throws Exception {
        String url = createDatabase("lineage_items_v49");
        flyway(url, "49").migrate();
        seedV49(url);

        Flyway migrating = flyway(url, "51");
        assertThat(migrating.migrate().success).isTrue();
        // whatever else lies between V49 and V51 (V50), V51 itself ran and is the last applied version
        assertThat(migrating.info().current().getVersion().getVersion()).isEqualTo("51");
        assertThat(migrating.info().current().getState().isApplied()).isTrue();
        JdbcClient jdbc = JdbcClient.create(new DriverManagerDataSource(url, username(), password()));

        // each per-deck row carries the scope of ITS deck
        for (String table : List.of("deck_head_item", "deck_item_change", "deck_item_exemplar", "editing_draft", "capture_note")) {
            assertThat(jdbc.sql("SELECT count(*) FROM app_learning." + table + " t JOIN app_learning.deck d ON d.deck_id=t.deck_id "
                    + "WHERE t.reuse_scope_id=d.reuse_scope_id").query(Long.class).single())
                    .as(table).isEqualTo(count(jdbc, table));
            assertThat(count(jdbc, table)).as(table).isPositive();
        }
        // delete / reorder rows (no revision_id), a converted note and a draft's media hold survive the backfill intact
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck_item_change WHERE deck_id=:deck AND reuse_scope_id=:scope "
                + "AND change_kind IN ('delete','reorder') AND revision_id IS NULL AND previous_revision_id=:previous")
                .param("deck", deck1).param("scope", scope1).param("previous", second).query(Long.class).single()).isEqualTo(2L);
        assertThat(jdbc.sql("SELECT reuse_scope_id FROM app_learning.capture_note WHERE note_id=:note").param("note", convertedNote)
                .query(UUID.class).single()).isEqualTo(scope1);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.capture_note n JOIN app_learning.item_revision r "
                + "ON r.reuse_scope_id=n.reuse_scope_id AND r.member_key=n.converted_member_key AND r.revision_id=n.converted_revision_id "
                + "WHERE n.note_id=:note").param("note", convertedNote).query(Long.class).single()).isOne();
        assertThat(count(jdbc, "draft_media_ref")).isOne();
        assertThat(jdbc.sql("SELECT reuse_scope_id FROM app_learning.deck_head_item WHERE deck_id=:deck").param("deck", deck2)
                .query(UUID.class).single()).isEqualTo(scope2);
        assertThat(jdbc.sql("SELECT reuse_scope_id FROM app_learning.capture_note WHERE deck_id=:deck").param("deck", deck2)
                .query(UUID.class).single()).isEqualTo(scope2);
        // projections: the preview has no deck any more, the media reference keeps the asset owner and the origin deck
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.item_preview WHERE reuse_scope_id=:scope").param("scope", scope1)
                .query(Long.class).single()).isEqualTo(2L);
        assertThat(columns(jdbc, "item_preview")).contains("reuse_scope_id").doesNotContain("deck_id");
        assertThat(columns(jdbc, "content_media_ref")).contains("asset_owner_id", "reuse_scope_id", "deck_id").doesNotContain("owner_id");
        var reference = jdbc.sql("SELECT reuse_scope_id,asset_owner_id,deck_id FROM app_learning.content_media_ref")
                .query((row, ignored) -> List.of(row.getObject(1, UUID.class), row.getObject(2, UUID.class), row.getObject(3, UUID.class)))
                .single();
        assertThat(reference).containsExactly(scope1, owner1, deck1);
        // lineage reads from the head reach every revision the old deck-keyed joins reached
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck_head_item h JOIN app_learning.item_revision r "
                + "ON r.reuse_scope_id=h.reuse_scope_id AND r.member_key=h.member_key AND r.revision_id=h.revision_id")
                .query(Long.class).single()).isEqualTo(2L);

        // every new column is NOT NULL, every constraint is validated, no leftovers of the deck-keyed design
        assertThat(jdbc.sql("SELECT count(*) FROM information_schema.columns WHERE table_schema='app_learning' "
                + "AND column_name='reuse_scope_id' AND is_nullable='YES' AND table_name IN ('deck_head_item','deck_item_change',"
                + "'deck_item_exemplar','editing_draft','capture_note','item_preview','content_media_ref')")
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM pg_constraint WHERE connamespace='app_learning'::regnamespace AND NOT convalidated")
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM pg_indexes WHERE schemaname='app_learning' AND indexname='item_revision_history'")
                .query(Long.class).single()).isZero();

        // all guards are enabled ('O' = fires in the origin role) and really refuse updates
        assertThat(jdbc.sql("""
                SELECT count(*) FROM pg_trigger t WHERE NOT t.tgisinternal AND t.tgenabled='O' AND t.tgname IN
                    ('deck_item_change_guard','content_media_ref_guard','capture_note_guard','editing_draft_guard',
                     'item_revision_guard','learning_item_guard')
                """).query(Long.class).single()).isEqualTo(6L);
        assertThat(jdbc.sql("SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid "
                + "WHERE NOT t.tgisinternal AND t.tgenabled<>'O' AND c.relnamespace='app_learning'::regnamespace")
                .query(Long.class).single()).isZero();
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.deck_item_change SET change_ordinal=change_ordinal").update())
                .hasMessageContaining("Immutable item change");
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.content_media_ref SET reuse_scope_id=reuse_scope_id").update())
                .hasMessageContaining("Immutable published media reference");
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.capture_note SET row_version=row_version").update())
                .hasMessageContaining("Invalid capture transition");
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.editing_draft SET base_revision_id=NULL,member_key=NULL").update())
                .hasMessageContaining("Immutable draft context");
        // a per-deck row can never carry a scope that is not its deck's
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO app_learning.deck_item_exemplar(deck_id,reuse_scope_id,member_key,marked_at) "
                + "VALUES (:deck,:scope,:member,now())").param("deck", deck1).param("scope", scope2).param("member", member2).update())
                .hasMessageContaining("deck_item_exemplar_deck_scope_fkey");
    }

    @Test
    void theKeyConstraintsOfTheItemTablesAreExactlyTheLineageSet() {
        String url = createDatabase("lineage_items_inventory");
        flyway(url, null).migrate();
        JdbcClient jdbc = JdbcClient.create(new DriverManagerDataSource(url, username(), password()));
        List<String> actual = jdbc.sql("""
                SELECT replace(conrelid::regclass::text,'app_learning.','') || ' ' || conname || ' '
                       || replace(pg_get_constraintdef(oid),'app_learning.','')
                  FROM pg_constraint
                 WHERE contype IN ('p','u','f')
                   AND (conrelid IN ('app_learning.item_revision'::regclass,'app_learning.learning_item'::regclass,
                        'app_learning.deck_head_item'::regclass,'app_learning.deck_item_change'::regclass,
                        'app_learning.deck_item_exemplar'::regclass,'app_learning.editing_draft'::regclass,
                        'app_learning.capture_note'::regclass,'app_learning.item_preview'::regclass,
                        'app_learning.content_media_ref'::regclass,'app_learning.deck'::regclass)
                        OR confrelid IN ('app_learning.item_revision'::regclass,'app_learning.learning_item'::regclass))
                """).query(String.class).list().stream().sorted().toList();
        assertThat(actual).containsExactlyElementsOf(INVENTORY.lines().sorted().toList());
    }

    private static final String INVENTORY = """
            capture_note capture_note_conversion_command_id_fkey FOREIGN KEY (conversion_command_id) REFERENCES command_receipt(command_id)
            capture_note capture_note_conversion_command_id_key UNIQUE (conversion_command_id)
            capture_note capture_note_converted_fkey FOREIGN KEY (reuse_scope_id, converted_member_key, converted_revision_id) REFERENCES item_revision(reuse_scope_id, member_key, revision_id) DEFERRABLE INITIALLY DEFERRED
            capture_note capture_note_deck_id_owner_id_fkey FOREIGN KEY (deck_id, owner_id) REFERENCES deck(deck_id, owner_id)
            capture_note capture_note_deck_scope_fkey FOREIGN KEY (deck_id, reuse_scope_id) REFERENCES deck(deck_id, reuse_scope_id)
            capture_note capture_note_pkey PRIMARY KEY (note_id)
            content_media_ref content_media_ref_asset_id_owner_id_fkey FOREIGN KEY (asset_id, asset_owner_id) REFERENCES media_asset(asset_id, owner_id)
            content_media_ref content_media_ref_pkey PRIMARY KEY (reuse_scope_id, member_key, revision_id, node_id)
            content_media_ref content_media_ref_revision_fkey FOREIGN KEY (reuse_scope_id, member_key, revision_id) REFERENCES item_revision(reuse_scope_id, member_key, revision_id)
            deck deck_deck_id_reuse_scope_id_owner_id_key UNIQUE (deck_id, reuse_scope_id, owner_id)
            deck deck_exact_head FOREIGN KEY (deck_id, head_revision_id, row_version) REFERENCES deck_revision(deck_id, revision_id, sequence) DEFERRABLE INITIALLY DEFERRED
            deck deck_owner_identity UNIQUE (deck_id, owner_id)
            deck deck_pkey PRIMARY KEY (deck_id)
            deck deck_scope_key UNIQUE (deck_id, reuse_scope_id)
            deck_head_item deck_head_item_deck_scope_fkey FOREIGN KEY (deck_id, reuse_scope_id) REFERENCES deck(deck_id, reuse_scope_id)
            deck_head_item deck_head_item_pkey PRIMARY KEY (deck_id, member_key)
            deck_head_item deck_head_item_revision_fkey FOREIGN KEY (reuse_scope_id, member_key, revision_id, item_sequence) REFERENCES item_revision(reuse_scope_id, member_key, revision_id, item_sequence) DEFERRABLE INITIALLY DEFERRED
            deck_item_change deck_item_change_deck_id_deck_revision_id_deck_sequence_fkey FOREIGN KEY (deck_id, deck_revision_id, deck_sequence) REFERENCES deck_revision(deck_id, revision_id, sequence) DEFERRABLE INITIALLY DEFERRED
            deck_item_change deck_item_change_deck_scope_fkey FOREIGN KEY (deck_id, reuse_scope_id) REFERENCES deck(deck_id, reuse_scope_id)
            deck_item_change deck_item_change_item_fkey FOREIGN KEY (reuse_scope_id, member_key) REFERENCES learning_item(reuse_scope_id, member_key)
            deck_item_change deck_item_change_pkey PRIMARY KEY (deck_id, deck_revision_id, change_ordinal)
            deck_item_change deck_item_change_previous_fkey FOREIGN KEY (reuse_scope_id, member_key, previous_revision_id) REFERENCES item_revision(reuse_scope_id, member_key, revision_id) DEFERRABLE INITIALLY DEFERRED
            deck_item_change deck_item_change_revision_fkey FOREIGN KEY (reuse_scope_id, member_key, revision_id) REFERENCES item_revision(reuse_scope_id, member_key, revision_id) DEFERRABLE INITIALLY DEFERRED
            deck_item_exemplar deck_item_exemplar_deck_scope_fkey FOREIGN KEY (deck_id, reuse_scope_id) REFERENCES deck(deck_id, reuse_scope_id)
            deck_item_exemplar deck_item_exemplar_item_fkey FOREIGN KEY (reuse_scope_id, member_key) REFERENCES learning_item(reuse_scope_id, member_key)
            deck_item_exemplar deck_item_exemplar_pkey PRIMARY KEY (deck_id, member_key)
            editing_draft editing_draft_base_fkey FOREIGN KEY (reuse_scope_id, member_key, base_revision_id) REFERENCES item_revision(reuse_scope_id, member_key, revision_id)
            editing_draft editing_draft_deck_id_owner_id_fkey FOREIGN KEY (deck_id, owner_id) REFERENCES deck(deck_id, owner_id)
            editing_draft editing_draft_deck_scope_fkey FOREIGN KEY (deck_id, reuse_scope_id) REFERENCES deck(deck_id, reuse_scope_id)
            editing_draft editing_draft_media_owner UNIQUE (draft_id, owner_id)
            editing_draft editing_draft_pkey PRIMARY KEY (draft_id)
            exercise_content_binding exercise_content_binding_item_revision_origin_fkey FOREIGN KEY (deck_id, member_key, item_revision_id) REFERENCES item_revision(deck_id, member_key, revision_id)
            item_preview item_preview_pkey PRIMARY KEY (reuse_scope_id, member_key, revision_id)
            item_preview item_preview_revision_fkey FOREIGN KEY (reuse_scope_id, member_key, revision_id) REFERENCES item_revision(reuse_scope_id, member_key, revision_id) ON DELETE CASCADE
            item_revision item_revision_deck_id_deck_revision_id_deck_sequence_fkey FOREIGN KEY (deck_id, deck_revision_id, deck_sequence) REFERENCES deck_revision(deck_id, revision_id, sequence) DEFERRABLE INITIALLY DEFERRED
            item_revision item_revision_deck_id_member_key_deck_revision_id_key UNIQUE (deck_id, member_key, deck_revision_id)
            item_revision item_revision_deck_id_member_key_item_sequence_key UNIQUE (deck_id, member_key, item_sequence)
            item_revision item_revision_item_fkey FOREIGN KEY (reuse_scope_id, member_key) REFERENCES learning_item(reuse_scope_id, member_key)
            item_revision item_revision_lineage_id_key UNIQUE (reuse_scope_id, revision_id)
            item_revision item_revision_lineage_sequence_key UNIQUE (reuse_scope_id, member_key, revision_id, item_sequence)
            item_revision item_revision_origin_deck_fkey FOREIGN KEY (deck_id, reuse_scope_id, owner_id) REFERENCES deck(deck_id, reuse_scope_id, owner_id)
            item_revision item_revision_origin_key UNIQUE (deck_id, member_key, revision_id)
            item_revision item_revision_parent_fkey FOREIGN KEY (reuse_scope_id, member_key, parent_revision_id, parent_item_sequence) REFERENCES item_revision(reuse_scope_id, member_key, revision_id, item_sequence)
            item_revision item_revision_pkey PRIMARY KEY (reuse_scope_id, member_key, revision_id)
            item_revision item_revision_reuse_scope_id_content_root_id_fkey FOREIGN KEY (reuse_scope_id, content_root_id) REFERENCES storage_object(reuse_scope_id, object_id)
            item_revision item_revision_reuse_scope_id_descriptor_root_id_fkey FOREIGN KEY (reuse_scope_id, descriptor_root_id) REFERENCES storage_object(reuse_scope_id, object_id)
            learning_item learning_item_deck_id_reuse_scope_id_owner_id_fkey FOREIGN KEY (deck_id, reuse_scope_id, owner_id) REFERENCES deck(deck_id, reuse_scope_id, owner_id)
            learning_item learning_item_lineage_key UNIQUE (reuse_scope_id, member_key)
            learning_item learning_item_pkey PRIMARY KEY (deck_id, member_key)
            memory_objective memory_objective_deck_id_member_key_fkey FOREIGN KEY (deck_id, member_key) REFERENCES learning_item(deck_id, member_key)
            """;

    private static long count(JdbcClient jdbc, String table) {
        return jdbc.sql("SELECT count(*) FROM app_learning." + table).query(Long.class).single();
    }

    private static List<String> columns(JdbcClient jdbc, String table) {
        return jdbc.sql("SELECT column_name FROM information_schema.columns WHERE table_schema='app_learning' AND table_name=:table")
                .param("table", table).query(String.class).list();
    }

    /**
     * Two decks in two scopes written exactly as V49 shapes them. Foreign keys and triggers are off for the seed
     * ({@code session_replication_role = replica}); V51 validates every new foreign key against these rows.
     */
    private void seedV49(String url) throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(url, username(), password());
             var statement = connection.createStatement()) {
            statement.execute("SET session_replication_role = replica");
            statement.execute("INSERT INTO app_learning.deck(deck_id,owner_id,reuse_scope_id,head_revision_id,row_version,created_at) VALUES "
                    + "('" + deck1 + "','" + owner1 + "','" + scope1 + "','" + deckRevision1b + "',2,now()),"
                    + "('" + deck2 + "','" + owner2 + "','" + scope2 + "','" + deckRevision2 + "',1,now())");
            statement.execute("INSERT INTO app_learning.learning_item(deck_id,member_key,owner_id,reuse_scope_id,created_at) VALUES "
                    + "('" + deck1 + "','" + member1 + "','" + owner1 + "','" + scope1 + "',now()),"
                    + "('" + deck2 + "','" + member2 + "','" + owner2 + "','" + scope2 + "',now())");
            String revision = "INSERT INTO app_learning.item_revision(deck_id,member_key,revision_id,reuse_scope_id,owner_id,item_sequence,"
                    + "parent_revision_id,parent_item_sequence,deck_revision_id,deck_sequence,command_id,format_version,content_root_id,"
                    + "descriptor_root_id,created_at) VALUES ";
            statement.execute(revision
                    + "('" + deck1 + "','" + member1 + "','" + first + "','" + scope1 + "','" + owner1 + "',0,NULL,NULL,'" + deckRevision1
                    + "',1,'" + UUID.randomUUID() + "',1,'" + UUID.randomUUID() + "','" + UUID.randomUUID() + "',now()),"
                    + "('" + deck1 + "','" + member1 + "','" + second + "','" + scope1 + "','" + owner1 + "',1,'" + first + "',0,'"
                    + deckRevision1b + "',2,'" + UUID.randomUUID() + "',1,'" + UUID.randomUUID() + "','" + UUID.randomUUID() + "',now()),"
                    + "('" + deck2 + "','" + member2 + "','" + other + "','" + scope2 + "','" + owner2 + "',0,NULL,NULL,'" + deckRevision2
                    + "',1,'" + UUID.randomUUID() + "',1,'" + UUID.randomUUID() + "','" + UUID.randomUUID() + "',now())");
            statement.execute("INSERT INTO app_learning.deck_head_item(deck_id,member_key,revision_id,item_sequence,updated_at) VALUES "
                    + "('" + deck1 + "','" + member1 + "','" + second + "',1,now()),('" + deck2 + "','" + member2 + "','" + other + "',0,now())");
            statement.execute("INSERT INTO app_learning.deck_item_change(deck_id,deck_revision_id,deck_sequence,change_ordinal,member_key,"
                    + "change_kind,previous_revision_id,revision_id,from_ordinal,to_ordinal) VALUES "
                    + "('" + deck1 + "','" + deckRevision1 + "',1,0,'" + member1 + "','create',NULL,'" + first + "',NULL,0),"
                    + "('" + deck1 + "','" + deckRevision1b + "',2,0,'" + member1 + "','save','" + first + "','" + second + "',0,0)");
            statement.execute("INSERT INTO app_learning.deck_item_change(deck_id,deck_revision_id,deck_sequence,change_ordinal,member_key,"
                    + "change_kind,previous_revision_id,revision_id,from_ordinal,to_ordinal) VALUES "
                    + "('" + deck1 + "','" + UUID.randomUUID() + "',3,0,'" + member1 + "','reorder','" + second + "',NULL,0,1),"
                    + "('" + deck1 + "','" + UUID.randomUUID() + "',4,0,'" + member1 + "','delete','" + second + "',NULL,1,NULL)");
            statement.execute("INSERT INTO app_learning.deck_item_exemplar(deck_id,member_key,marked_at) VALUES ('" + deck1 + "','" + member1 + "',now())");
            statement.execute("INSERT INTO app_learning.editing_draft(draft_id,owner_id,deck_id,member_key,base_revision_id,row_version,document,"
                    + "created_at,acknowledged_at,expires_at) VALUES ('" + draft + "','" + owner1 + "','" + deck1 + "','" + member1
                    + "','" + first + "',0,'{}'::jsonb,now(),now(),now()+interval '30 days')");
            statement.execute("INSERT INTO app_learning.draft_media_ref(draft_id,node_id,owner_id,asset_id) VALUES ('" + draft + "','"
                    + UUID.randomUUID() + "','" + owner1 + "','" + asset + "')");
            statement.execute("INSERT INTO app_learning.capture_note(note_id,owner_id,deck_id,row_version,source,note_text,created_at,"
                    + "updated_at,conversion_command_id,conversion_payload_hash,converted_member_key,converted_revision_id,converted_at,"
                    + "conversion_result) VALUES ('" + convertedNote + "','" + owner1 + "','" + deck1 + "',1,'s','converted',now(),now(),'"
                    + UUID.randomUUID() + "',decode(repeat('ab',32),'hex'),'" + member1 + "','" + second + "',now(),'{}'::jsonb)");
            statement.execute("INSERT INTO app_learning.capture_note(note_id,owner_id,deck_id,row_version,source,note_text,created_at,updated_at) VALUES "
                    + "('" + UUID.randomUUID() + "','" + owner1 + "','" + deck1 + "',0,'s','one',now(),now()),"
                    + "('" + UUID.randomUUID() + "','" + owner2 + "','" + deck2 + "',0,'s','two',now(),now())");
            statement.execute("INSERT INTO app_learning.item_preview(deck_id,member_key,revision_id,title) VALUES "
                    + "('" + deck1 + "','" + member1 + "','" + first + "','old'),('" + deck1 + "','" + member1 + "','" + second + "','new')");
            statement.execute("INSERT INTO app_learning.content_media_ref(deck_id,member_key,revision_id,node_id,owner_id,asset_id) VALUES "
                    + "('" + deck1 + "','" + member1 + "','" + second + "','" + UUID.randomUUID() + "','" + owner1 + "','" + asset + "')");
            statement.execute("INSERT INTO app_learning.exercise_content_binding(deck_id,exercise_id,exercise_revision_id,binding_id,"
                    + "binding_ordinal,role,member_key,item_revision_id,node_ids) VALUES ('" + deck1 + "','" + UUID.randomUUID() + "','"
                    + UUID.randomUUID() + "','" + UUID.randomUUID() + "',0,'CONTEXT','" + member1 + "','" + second + "','{}')");
        }
    }

    private static Flyway flyway(String url, String target) {
        var configuration = Flyway.configure().dataSource(url, username(), password())
                .locations("classpath:db/learning/migration").schemas("app_learning").defaultSchema("app_learning")
                .createSchemas(true).cleanDisabled(true);
        if (target != null) configuration.target(target);
        return configuration.load();
    }
}
