package app.mnema.learning.onboarding;

import app.mnema.learning.profile.LearningGoal;
import app.mnema.learning.profile.LearningProfileService;
import app.mnema.learning.support.HttpIdentityFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code milestones} and {@code path} members of the onboarding view over the real servlet stack: in registry order, only the visible milestones, a state per record, the whole
 * view in the strong ETag, and the same members in the answer of a command. This context has the tour and speech to text on (9 of 13 milestones visible).
 */
class MilestoneViewIntegrationTest extends SampleDeckIntegrationTest {
    @Autowired private LearningProfileService profile;

    private static List<String> keys(JsonNode milestones) {
        List<String> keys = new ArrayList<>();
        milestones.forEach(milestone -> keys.add(milestone.path("key").stringValue()));
        return keys;
    }

    @Test
    void theViewListsTheVisibleMilestonesInOrderWithTheirStateAndCountsOnlyThose() {
        UUID account = UUID.randomUUID();
        JsonNode fresh = json(onboarding(account, NONE));
        assertThat(keys(fresh.path("milestones"))).containsExactly("GOAL_TOLD", "FIRST_DECK", "FIRST_SESSION", "OWN_MATERIAL", "MEDIA_IN_MATERIAL", "CAPTURE_NOTE", "OWN_EXERCISES",
                "VOICE_ANSWER", "TWO_DAYS");
        fresh.path("milestones").forEach(milestone -> {
            assertThat(milestone.path("state").stringValue()).isEqualTo("TODO");
            assertThat(milestone.path("progress").intValue()).isZero();
            assertThat(milestone.path("achievedAt").isNull()).isTrue();
        });
        assertThat(fresh.path("path").path("done").intValue()).isZero();
        assertThat(fresh.path("path").path("total").intValue()).isEqualTo(9);
        assertThat(fresh.path("path").path("hidden")).extracting(JsonNode::stringValue).containsExactly("AI_EXPLANATION", "WORKSHOP_APPROVED", "RHYTHM_WEEK", "COMMUNITY_COPY");
        // every target is the registry's
        for (JsonNode milestone : fresh.path("milestones")) {
            assertThat(milestone.path("target").intValue()).isEqualTo(MilestoneKey.valueOf(milestone.path("key").stringValue()).target());
        }

        profile.answer(account, LearningGoal.SELF);
        UUID deck = worlds.shared.deck(account, "Колода");
        worlds.study.addMaterial(account, deck, "слово", "перевод");
        var material = worlds.study.addMaterial(account, deck, "ещё", "слово");
        worlds.study.publish(material, worlds.study.freeResponse(material, app.mnema.learning.support.StudyFixtures.blocks(app.mnema.learning.support.StudyFixtures.text("?")),
                app.mnema.learning.support.StudyFixtures.blocks(app.mnema.learning.support.StudyFixtures.text("!")), "ответ"), "Цель");

        JsonNode view = json(onboarding(account, NONE));
        Map<String, JsonNode> byKey = new java.util.LinkedHashMap<>();
        view.path("milestones").forEach(milestone -> byKey.put(milestone.path("key").stringValue(), milestone));
        assertThat(byKey.get("GOAL_TOLD").path("state").stringValue()).isEqualTo("DONE");
        assertThat(byKey.get("GOAL_TOLD").path("progress").intValue()).isOne();
        // the time is cut to whole seconds, like every other time of the contract
        assertThat(Instant.parse(byKey.get("GOAL_TOLD").path("achievedAt").stringValue()).getNano()).isZero();
        assertThat(byKey.get("FIRST_DECK").path("state").stringValue()).isEqualTo("DONE");
        assertThat(byKey.get("OWN_MATERIAL").path("state").stringValue()).isEqualTo("DONE");
        assertThat(byKey.get("OWN_EXERCISES").path("state").stringValue()).isEqualTo("PARTIAL");
        assertThat(byKey.get("OWN_EXERCISES").path("progress").intValue()).isOne();
        assertThat(byKey.get("OWN_EXERCISES").path("achievedAt").isNull()).isTrue();
        assertThat(byKey.get("FIRST_SESSION").path("state").stringValue()).isEqualTo("TODO");
        assertThat(view.path("path").path("done").intValue()).isEqualTo(3);
        assertThat(view.path("path").path("total").intValue()).isEqualTo(9);
    }

    @Test
    void theEtagHashesTheWholeViewSoAMilestoneChangesItAndAnUnchangedViewIsA304() {
        UUID account = UUID.randomUUID();
        HttpResponse<String> first = onboarding(account, NONE);
        String etag = first.headers().firstValue("ETag").orElseThrow();
        assertThat(onboarding(account, Map.of("If-None-Match", etag)).statusCode()).isEqualTo(304);

        profile.answer(account, null);
        HttpResponse<String> after = onboarding(account, Map.of("If-None-Match", etag));
        assertThat(after.statusCode()).as("the goal milestone changed the view").isEqualTo(200);
        assertThat(after.headers().firstValue("ETag").orElseThrow()).isNotEqualTo(etag);
        assertThat(onboarding(account, Map.of("If-None-Match", after.headers().firstValue("ETag").orElseThrow())).statusCode()).isEqualTo(304);
    }

    @Test
    void theAnswerOfACommandCarriesTheSameMembers() {
        UUID account = UUID.randomUUID();
        profile.answer(account, LearningGoal.WORK);
        String replay = "{\"commandId\":\"" + UUID.randomUUID() + "\",\"rowVersion\":\"0\",\"action\":\"REPLAY\",\"stepKey\":\"choose\"}";
        HttpResponse<String> answer = HttpIdentityFixture.send(port, "PUT", "/onboarding/journey", HttpIdentityFixture.reader(account), replay, NONE);
        assertThat(answer.statusCode()).as(answer.body()).isEqualTo(200);
        JsonNode view = json(answer);
        assertThat(keys(view.path("milestones"))).hasSize(9);
        assertThat(view.path("milestones").get(0).path("key").stringValue()).isEqualTo("GOAL_TOLD");
        assertThat(view.path("milestones").get(0).path("state").stringValue()).isEqualTo("DONE");
        assertThat(view.path("path").path("done").intValue()).isOne();

        // the sample deck command's answer is read after its write in the same transaction: the first deck is already there
        UUID learner = UUID.randomUUID();
        template(3, 1);
        HttpResponse<String> sample = post(learner);
        assertThat(sample.statusCode()).as(sample.body()).isEqualTo(201);
        JsonNode sampleView = json(sample);
        assertThat(sampleView.path("milestones").get(1).path("key").stringValue()).isEqualTo("FIRST_DECK");
        assertThat(sampleView.path("milestones").get(1).path("state").stringValue()).isEqualTo("DONE");
        assertThat(sampleView.path("path").path("done").intValue()).isOne();
    }
}
