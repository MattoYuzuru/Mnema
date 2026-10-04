package app.mnema.learning.generation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Notes as sources (#290): grouping, per-note overrides, the pinned text and the read-time status of a pinned note. */
class GenerationNotesIntegrationTest extends GenerationIntegrationTest {
    @Autowired private app.mnema.learning.usage.EstimateController estimates;

    private static ObjectNode withOverrides(ObjectNode note, String overrides) throws Exception {
        note.set("overrides", JSON.readTree(overrides));
        return note;
    }

    private List<JsonNode> sourceRefsOf(UUID session) {
        return jdbc.sql("SELECT source_refs::text FROM app_learning.generation_artifact WHERE session_id=:id ORDER BY ordinal")
                .param("id", session).query(String.class).list().stream().map(JSON::readTree).toList();
    }

    private JsonNode artifact(UUID owner, UUID deck, UUID session, UUID artifact) throws Exception {
        return json(send(owner, get("/decks/" + deck + "/generation-sessions/" + session + "/artifacts/" + artifact)));
    }

    private UUID artifactId(UUID session, int ordinal) {
        return jdbc.sql("SELECT artifact_id FROM app_learning.generation_artifact WHERE session_id=:id AND ordinal=:o")
                .param("id", session).param("o", ordinal).query(UUID.class).single();
    }

    @Test
    void fourNotesAreFourMaterialsEachPinningItsOwnNoteAndMergedNotesAreOneMaterialWithFourSources() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID[] notes = new UUID[4];
        ObjectNode[] sources = new ObjectNode[4];
        for (int i = 0; i < 4; i++) {
            notes[i] = note(owner, deck, "заметка " + i);
            sources[i] = noteSource(notes[i], 0);
        }
        UUID session = start(owner, deck, spec(null, sources));
        awaitState(session, "REVIEW");
        List<JsonNode> refs = sourceRefsOf(session);
        assertThat(refs).hasSize(4);
        for (int i = 0; i < 4; i++) {
            assertThat(refs.get(i)).hasSize(1);
            assertThat(refs.get(i).get(0).path("noteId").stringValue(null)).isEqualTo(notes[i].toString());
        }

        ObjectNode merge = spec(null, sources);
        ((ObjectNode) merge.path("settings")).put("notesMode", "MERGE_INTO_ONE");
        UUID merged = start(owner, deck, merge);
        awaitState(merged, "REVIEW");
        List<JsonNode> mergedRefs = sourceRefsOf(merged);
        assertThat(mergedRefs).hasSize(1);
        assertThat(mergedRefs.get(0)).hasSize(4);
    }

    @Test
    void aPerNoteOverrideChangesOnlyItsOwnNotesSpecStepAndContext() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID first = note(owner, deck, "ПЕРВАЯ-ЗАМЕТКА");
        UUID second = note(owner, deck, "ВТОРАЯ-ЗАМЕТКА");
        String overrides = "{\"effort\":\"DETAILED\",\"media\":{\"audio\":{\"enabled\":true,\"lang\":\"ko\",\"voice\":null}}}";
        ObjectNode spec = spec(null, withOverrides(noteSource(first, 0), overrides), noteSource(second, 0));
        ((ObjectNode) spec.path("settings")).put("effort", "SHORT");
        UUID session = start(owner, deck, spec);
        awaitState(session, "REVIEW");

        // the spec is echoed as sent
        JsonNode echoed = json(getSession(owner, deck, session)).path("spec").path("sources");
        assertThat(echoed.get(0).path("overrides")).isEqualTo(JSON.readTree(overrides));
        assertThat(echoed.get(1).has("overrides")).isFalse();
        // the step input of each artifact carries its effective effort and weight
        List<String> operations = jdbc.sql("SELECT s.input->>'operation' FROM app_learning.generation_step s JOIN "
                        + "app_learning.generation_artifact a ON a.artifact_id=s.artifact_id WHERE s.session_id=:id AND s.kind='TEXT_DRAFT' "
                        + "ORDER BY a.ordinal").param("id", session).query(String.class).list();
        assertThat(operations).containsExactly("MATERIAL_DETAILED", "MATERIAL_SHORT");
        // the context of each step has the directives of its own note only
        assertThat(provider.calls).anySatisfy(call -> assertThat(call.prompt()).contains("ПЕРВАЯ-ЗАМЕТКА")
                .contains("объём ≈900 слов").contains("аудио (::audio)"));
        assertThat(provider.calls).anySatisfy(call -> assertThat(call.prompt()).contains("ВТОРАЯ-ЗАМЕТКА")
                .contains("объём ≈150 слов").contains("медиа: нет"));
    }

    @Test
    void theEstimateAndTheCapabilityChecksUseTheEffectiveSettings() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID first = note(owner, deck, "а");
        UUID second = note(owner, deck, "б");
        String path = "/decks/" + deck + "/generation-estimates";
        ObjectNode plain = spec(null, noteSource(first, 0), noteSource(second, 0));
        ObjectNode overridden = spec(null, withOverrides(noteSource(first, 0), "{\"effort\":\"DETAILED\"}"), noteSource(second, 0));
        JsonNode base = json(estimate(owner, path, "{\"spec\":" + plain + "}"));
        JsonNode priced = json(estimate(owner, path, "{\"spec\":" + overridden + "}"));
        assertThat(priced.path("credits").path("p95").intValue()).isGreaterThan(base.path("credits").path("p95").intValue());
        assertThat(priced.path("breakdown").toString()).contains("MATERIAL_DETAILED").contains("MATERIAL_MEDIUM");

        // image search is available in this environment (the Stub): the session value is priced, unless the note overrides it away
        ObjectNode images = spec(null, noteSource(first, 0));
        ((ObjectNode) images.path("settings")).putObject("media").put("imageSearch", true);
        MockHttpServletResponse withImages = estimate(owner, path, "{\"spec\":" + images + "}");
        assertThat(withImages.getStatus()).isEqualTo(200);
        assertThat(json(withImages).path("breakdown").toString()).contains("IMAGE_SEARCH");
        ObjectNode overriddenAway = spec(null, withOverrides(noteSource(first, 0), "{\"media\":{\"imageSearch\":false}}"));
        ((ObjectNode) overriddenAway.path("settings")).putObject("media").put("imageSearch", true);
        MockHttpServletResponse away = estimate(owner, path, "{\"spec\":" + overriddenAway + "}");
        assertThat(away.getStatus()).isEqualTo(200);
        assertThat(json(away).path("breakdown").toString()).doesNotContain("IMAGE_SEARCH");
        // ... and an override can ask for what the session did not
        ObjectNode asksImages = spec(null, withOverrides(noteSource(first, 0), "{\"media\":{\"imageSearch\":true}}"));
        MockHttpServletResponse asks = estimate(owner, path, "{\"spec\":" + asksImages + "}");
        assertThat(asks.getStatus()).isEqualTo(200);
        assertThat(json(asks).path("breakdown").toString()).contains("IMAGE_SEARCH");
        // create answers like the estimate: strict parsing is a 400
        assertThat(create(owner, deck, spec(null, withOverrides(noteSource(first, 0), "{}")), UUID.randomUUID()).getStatus()).isEqualTo(400);
        ObjectNode merge = spec(null, withOverrides(noteSource(first, 0), "{\"effort\":\"SHORT\"}"));
        ((ObjectNode) merge.path("settings")).put("notesMode", "MERGE_INTO_ONE");
        assertThat(create(owner, deck, merge, UUID.randomUUID()).getStatus()).isEqualTo(400);
    }

    @Test
    void aNoteEditedWhileItsStepRunsDoesNotChangeThePinAndTheWorkshopSeesChanged() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID note = note(owner, deck, "ИСХОДНЫЙ-ТЕКСТ [[fake:block]]");
        UUID session = start(owner, deck, spec(null, noteSource(note, 0)));
        assertThat(provider.blockedEntered.await(10, TimeUnit.SECONDS)).isTrue();
        jdbc.sql("UPDATE app_learning.capture_note SET row_version=row_version+1,note_text='ПРАВКА' WHERE note_id=:id")
                .param("id", note).update();
        provider.release.countDown();
        awaitState(session, "REVIEW");

        assertThat(artifactStates(session)).containsExactly("PROPOSED");
        assertThat(provider.calls.getFirst().prompt()).contains("ИСХОДНЫЙ-ТЕКСТ").doesNotContain("ПРАВКА");
        JsonNode ref = artifact(owner, deck, session, artifactId(session, 0)).path("sourceRefs").get(0);
        assertThat(ref.path("noteRowVersion").stringValue(null)).isEqualTo("0");
        assertThat(ref.path("status").stringValue(null)).isEqualTo("CHANGED");
        // reading never changes the artifact
        assertThat(artifactStates(session)).containsExactly("PROPOSED");
    }

    @Test
    void thePinnedNoteStatusIsCurrentArchivedChangedOrDeleted() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID current = note(owner, deck, "один");
        UUID archived = note(owner, deck, "два");
        UUID changed = note(owner, deck, "три");
        UUID archivedAndChanged = note(owner, deck, "четыре");
        UUID deleted = note(owner, deck, "пять");
        UUID session = start(owner, deck, spec(null, noteSource(current, 0), noteSource(archived, 0), noteSource(changed, 0),
                noteSource(archivedAndChanged, 0), noteSource(deleted, 0)));
        awaitState(session, "REVIEW");
        jdbc.sql("UPDATE app_learning.capture_note SET row_version=row_version+1,archived=true WHERE note_id=:id").param("id", archived).update();
        jdbc.sql("UPDATE app_learning.capture_note SET row_version=row_version+1,note_text='иначе' WHERE note_id=:id").param("id", changed).update();
        jdbc.sql("UPDATE app_learning.capture_note SET row_version=row_version+1,archived=true,note_text='иначе' WHERE note_id=:id")
                .param("id", archivedAndChanged).update();
        jdbc.sql("DELETE FROM app_learning.capture_note WHERE note_id=:id").param("id", deleted).update();

        List<String> states = artifactStates(session);
        List<String> statuses = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            statuses.add(artifact(owner, deck, session, artifactId(session, i)).path("sourceRefs").get(0).path("status").stringValue(null));
        }
        assertThat(statuses).containsExactly("CURRENT", "ARCHIVED", "CHANGED", "CHANGED", "DELETED");
        assertThat(artifactStates(session)).isEqualTo(states);
    }

    @Test
    void aRetryAfterAnEditRepinsAndReadsTheNoteFromANewSnapshot() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID note = note(owner, deck, "СТАРЫЙ-ТЕКСТ");
        UUID session = start(owner, deck, spec(null, noteSource(note, 0)));
        awaitState(session, "REVIEW");
        assertThat(repository.pinnedNoteText(session, note, 0)).contains("СТАРЫЙ-ТЕКСТ");
        // snapshots are append-only
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql(
                "UPDATE app_learning.generation_note_snapshot SET note_text='x' WHERE session_id=:id").param("id", session).update())
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(repository.snapshotNote(session, owner, note, 0)).isTrue();
        // a note that moved is not snapshotted at the old version again, and an unknown pin has no text
        jdbc.sql("UPDATE app_learning.capture_note SET row_version=row_version+1,note_text='НОВЫЙ-ТЕКСТ' WHERE note_id=:id").param("id", note).update();
        assertThat(repository.snapshotNote(session, owner, note, 1)).isTrue();
        assertThat(repository.pinnedNoteText(session, note, 1)).contains("НОВЫЙ-ТЕКСТ");
        assertThat(repository.pinnedNoteText(session, note, 0)).contains("СТАРЫЙ-ТЕКСТ");
        assertThat(repository.snapshotNote(session, owner, note, 7)).isFalse();
        assertThat(Duration.ofSeconds(1)).isPositive();
    }

    @Autowired private GenerationRepository repository;

    private MockHttpServletResponse estimate(UUID owner, String path, String body) throws Exception {
        org.springframework.security.oauth2.jwt.Jwt jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("test")
                .header("alg", "RS256").subject(owner.toString()).build();
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(jwt));
        return org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(estimates)
                .setControllerAdvice(new app.mnema.learning.platform.api.ApiExceptionHandler())
                .setCustomArgumentResolvers(new org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver())
                .build().perform(post(path).contentType("application/json").content(body)).andReturn().getResponse();
    }
}
