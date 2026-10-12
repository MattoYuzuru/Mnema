package app.mnema.learning.update.plan;

import app.mnema.learning.support.StudyFixtures.Material;
import app.mnema.learning.update.apply.ApplyService;
import app.mnema.learning.update.plan.AutoWorlds.JobRow;
import app.mnema.learning.update.plan.PlanWorlds.Authored;
import app.mnema.learning.update.plan.PlanWorlds.Copy;
import app.mnema.learning.update.plan.PlanWorlds.Source;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static app.mnema.learning.update.plan.PlanWorlds.json;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Updates/6 part B (#445), the automatic mode through the real application: a real source with its author, real copies made by {@code POST /decks/forks} and materialized by the
 * real job, the author's and the learners' edits through the editor's services, the publication command that enqueues the fan-out, and the two jobs run by hand around the real
 * handlers (no process executor: every claim happens in a known order). The clock the fan-out is paced by is a test clock; the pace test lowers the allowance to three copies a
 * minute. The context is {@link AutoUpdateContext}'s.
 */
class AutoUpdateIntegrationTest extends AutoUpdateContext {
    private static final JsonNode CONTRACT = PlanWorlds.updatesContract();

    private AutoApplyOutcome tryAuto(Copy copy) {
        return new TransactionTemplate(transactions).execute(status -> {
            ApplyService.AutoResult result = applies.applyAuto(copy.owner(), copy.deck());
            return new AutoApplyOutcome(result.outcome(), result.accepted(), result.pending(), result.unresolved());
        });
    }

    private record AutoApplyOutcome(ApplyService.AutoOutcome outcome, int accepted, int pending, int unresolved) { }

    private static String material(UUID member) { return UnitKind.MATERIAL.unitId(member); }

    private static JsonNode forks() {
        try { return PlanWorlds.JSON.readTree(java.nio.file.Files.readString(PlanWorlds.contract("forks.json"))); }
        catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }

    private static Set<String> names(JsonNode node) {
        Set<String> result = new TreeSet<>();
        node.propertyNames().forEach(result::add);
        return result;
    }

    /** The author edits, the learner edits, the author publishes: a SAFE material, a CONFLICT, a unit the author REMOVED and a NEW material. */
    private record Scene(Source source, Copy copy, UUID safe, UUID conflict, UUID removed, Material added, UUID authorSafe, UUID authorConflict, UUID learnerConflict,
                         UUID published) { }

    private Scene scene(boolean automatic) {
        Source source = worlds.source("LINK", 4, 1);
        List<Material> m = source.materials();
        Copy copy = automatic ? auto.autoCopy(source, hand) : apply.copyByHand(source, hand);
        UUID author = source.author();
        UUID deck = source.deck();
        UUID authorSafe = worlds.editText(author, deck, m.get(0).member(), "author zero");
        UUID authorConflict = worlds.editText(author, deck, m.get(1).member(), "author one");
        UUID learnerConflict = worlds.editText(copy.owner(), copy.deck(), m.get(1).member(), "learner one");
        worlds.deleteMaterial(author, deck, m.get(2).member());
        Material added = worlds.study.addMaterial(author, deck, "новое слово", "перевод");
        worlds.publish(author, deck, "LINK");
        return new Scene(source, copy, m.get(0).member(), m.get(1).member(), m.get(2).member(), added, authorSafe, authorConflict, learnerConflict, worlds.published(deck));
    }

    // ---- the publication command enqueues the fan-out ----

    @Test
    void publishingEnqueuesOneFanOutPerPublishedRevisionAndNothingForTheFirstPublicationOrAChangeThatMovesNoRevision() {
        Source source = worlds.source("LINK", 3, 0);
        UUID author = source.author();
        UUID deck = source.deck();
        List<Material> m = source.materials();
        assertThat(auto.jobs(AutoWorlds.FANOUT, deck)).as("nobody can have a copy of the first publication").isEmpty();

        worlds.editText(author, deck, m.get(0).member(), "second");
        worlds.publish(author, deck, "LINK");
        UUID second = worlds.published(deck);
        List<JobRow> afterSecond = auto.jobs(AutoWorlds.FANOUT, deck);
        assertThat(afterSecond).hasSize(1);
        JobRow job = afterSecond.getFirst();
        assertThat(job.key()).isEqualTo(deck + ":" + second);
        assertThat(job.account()).as("a system job").isNull();
        assertThat(job.payload().path("deckId").stringValue(null)).isEqualTo(deck.toString());
        assertThat(job.payload().path("revisionId").stringValue(null)).isEqualTo(second.toString());
        assertThat(names(job.payload())).containsExactly("deckId", "revisionId");
        assertThat(job.state()).isEqualTo("QUEUED");

        // the same head published again moves nothing; a change of level only moves nothing
        worlds.publish(author, deck, "LINK");
        worlds.level(author, deck, "LINK");
        assertThat(auto.jobs(AutoWorlds.FANOUT, deck)).hasSize(1);
        // a call that repeats the key (the same publication) is the same job
        new TransactionTemplate(transactions).executeWithoutResult(status -> fanout.published(deck, second));
        assertThat(auto.jobs(AutoWorlds.FANOUT, deck)).hasSize(1);

        worlds.editText(author, deck, m.get(1).member(), "third");
        worlds.publish(author, deck, "LINK");
        assertThat(auto.jobs(AutoWorlds.FANOUT, deck)).hasSize(2);
        assertThat(auto.jobs(AutoWorlds.FANOUT, deck).get(1).key()).isEqualTo(deck + ":" + worlds.published(deck));
    }

    // ---- the fan-out ----

    @Test
    void theFanOutHandsOverExactlyTheLiveReadyAttachedAutomaticActiveCopiesOfTheSource() {
        Source source = worlds.source("LINK", 3, 0);
        UUID deck = source.deck();
        Copy fresh = auto.autoCopy(source, hand);
        Copy studied = auto.autoCopy(source, hand);
        Copy idle = auto.autoCopy(source, hand);
        Copy manual = apply.copyByHand(source, hand);
        Copy detached = auto.autoCopy(source, hand);
        Copy deleted = auto.autoCopy(source, hand);
        Source otherSource = worlds.source("LINK", 2, 0);
        Copy ofAnother = auto.autoCopy(otherSource, hand);
        // a copy of the SAME source that follows the author already: made after the last publication, it stands on it
        auto.age(studied.deck(), 40);
        auto.study(studied);
        auto.age(idle.deck(), 40);
        assertThat(worlds.send("POST", "/decks/" + detached.deck() + "/fork:detach", detached.owner(), null, null).statusCode()).isEqualTo(200);
        assertThat(worlds.send("DELETE", "/decks/" + deleted.deck(), deleted.owner(), null, "\"0\"").statusCode()).isEqualTo(204);

        worlds.editText(source.author(), deck, source.materials().get(0).member(), "second");
        worlds.editText(otherSource.author(), otherSource.deck(), otherSource.materials().get(0).member(), "other second");
        worlds.publish(source.author(), deck, "LINK");
        worlds.publish(otherSource.author(), otherSource.deck(), "LINK");
        UUID published = worlds.published(deck);
        Copy upToDate = auto.autoCopy(source, hand);
        // still preparing: the job of the copy was never run
        UUID preparing = worlds.forked(UUID.randomUUID(), source.code());
        auto.setAutoDirectly(preparing, true);

        assertThat(hand.runOne(AutoWorlds.FANOUT)).isTrue();
        assertThat(hand.runOne(AutoWorlds.FANOUT)).as("the other source's own fan-out").isTrue();
        List<JobRow> handed = auto.autoJobs(published, worlds.published(otherSource.deck()));
        assertThat(handed).extracting(JobRow::key).containsExactlyInAnyOrder(fresh.deck() + ":" + published, studied.deck() + ":" + published,
                ofAnother.deck() + ":" + worlds.published(otherSource.deck()));
        assertThat(handed).extracting(JobRow::account).containsExactlyInAnyOrder(fresh.owner(), studied.owner(), ofAnother.owner());
        for (JobRow row : handed) {
            assertThat(row.state()).isEqualTo("QUEUED");
            assertThat(names(row.payload())).containsExactly("deckId", "revisionId");
        }
        List<JobRow> fanouts = auto.jobs(AutoWorlds.FANOUT, deck);
        assertThat(fanouts).hasSize(1);
        assertThat(fanouts.getFirst().state()).isEqualTo("SUCCEEDED");
        assertThat(fanouts.getFirst().progressCount()).isEqualTo(2);
        assertThat(handed).extracting(JobRow::key).doesNotContain(idle.deck() + ":" + published, manual.deck() + ":" + published, detached.deck() + ":" + published,
                deleted.deck() + ":" + published, upToDate.deck() + ":" + published, preparing + ":" + published);
    }

    @Test
    void thePaceIsAtMostThreeCopiesInAnyRollingMinuteAndAPausedFanOutResumesWhereItStopped() {
        Source source = worlds.source("LINK", 3, 0);
        UUID deck = source.deck();
        List<Copy> copies = new ArrayList<>();
        for (int index = 0; index < 5; index++) copies.add(auto.autoCopy(source, hand));
        ALLOWANCE.set(3);
        worlds.editText(source.author(), deck, source.materials().get(0).member(), "second");
        worlds.publish(source.author(), deck, "LINK");
        UUID published = worlds.published(deck);

        // a minute that allows three: three are handed over, then the allowance is spent and the job waits for the minute, with its cursor
        assertThat(hand.runOne(AutoWorlds.FANOUT)).isTrue();
        assertThat(auto.autoJobs(published)).hasSize(3);
        JobRow paused = auto.jobs(AutoWorlds.FANOUT, deck).getFirst();
        assertThat(paused.state()).as("given back to the queue, not failed and not running").isEqualTo("QUEUED");
        assertThat(paused.waiting()).as("not due until some of the minute is free").isTrue();
        assertThat(paused.progressCount()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT attempts FROM app_learning.learning_job WHERE job_id=:id").param("id", paused.id()).query(Integer.class).single()).as("no attempt is spent").isZero();
        assertThat(hand.runOne(AutoWorlds.FANOUT)).as("nothing is due while the minute is full").isFalse();

        // half a minute later the first grants are still in the window; the clock of the pace and the clock of the database are not the same, so the job is made due by hand
        clock.advance(Duration.ofSeconds(30));
        hand.makeDue(paused.id());
        assertThat(hand.runOne(AutoWorlds.FANOUT)).isTrue();
        assertThat(auto.autoJobs(published)).as("still the allowance of one rolling minute").hasSize(3);
        assertThat(auto.jobs(AutoWorlds.FANOUT, deck).getFirst().state()).isEqualTo("QUEUED");

        clock.advance(Duration.ofSeconds(31));
        hand.makeDue(paused.id());
        assertThat(hand.runOne(AutoWorlds.FANOUT)).isTrue();
        assertThat(auto.autoJobs(published)).hasSize(5);
        JobRow done = auto.jobs(AutoWorlds.FANOUT, deck).getFirst();
        assertThat(done.state()).isEqualTo("SUCCEEDED");
        assertThat(done.progressCount()).isEqualTo(5);
        assertThat(auto.autoJobs(published)).extracting(JobRow::account).containsExactlyInAnyOrderElementsOf(copies.stream().map(Copy::owner).toList());
    }

    // ---- the automatic apply ----

    @Test
    void theAutomaticApplyTakesOnlyWhatIsSafeLeavesTheRestToTheOwnerAndTellsHimWhatCame() {
        Scene s = scene(true);
        Copy copy = s.copy();
        UUID mine = copy.deck();
        List<Material> m = s.source().materials();
        UUID learner = copy.owner();
        UUID baseBefore = worlds.base(mine);
        long revisionsBefore = apply.revisions(mine);
        assertThat(ApplyWorlds.classes(apply.plan(copy))).containsEntry(material(s.safe()), "SAFE").containsEntry(material(s.conflict()), "CONFLICT")
                .containsEntry(material(s.removed()), "DELETED").containsEntry(material(s.added().member()), "NEW").hasSize(4);
        String sourceBefore = apply.snapshot(s.source().deck());

        assertThat(hand.runOne(AutoWorlds.FANOUT)).isTrue();
        assertThat(hand.runOne(AutoWorlds.AUTO)).isTrue();
        JobRow job = auto.jobs(AutoWorlds.AUTO, mine).getFirst();
        assertThat(job.state()).isEqualTo("SUCCEEDED");

        // exactly the safe units were taken
        assertThat(apply.revisions(mine)).as("one revision for the whole apply").isEqualTo(revisionsBefore + 1);
        assertThat(worlds.itemHead(mine, s.safe())).isEqualTo(s.authorSafe());
        assertThat(worlds.itemHead(mine, s.added().member())).isEqualTo(s.added().itemRevision());
        assertThat(worlds.itemHead(mine, s.conflict())).as("what the learner changed stays his").isEqualTo(s.learnerConflict()).isNotEqualTo(s.authorConflict());
        assertThat(worlds.itemHead(mine, s.removed())).as("what the author removed stays until the owner decides").isEqualTo(m.get(2).itemRevision());
        assertThat(worlds.base(mine)).as("the base moves").isEqualTo(s.published()).isNotEqualTo(baseBefore);
        assertThat(apply.decisions(mine)).as("the rest is pending, each with its own old base").isEqualTo(Map.of(
                "MATERIAL:" + s.conflict(), "PENDING " + s.authorConflict() + " " + m.get(1).itemRevision(),
                "MATERIAL:" + s.removed(), "PENDING - " + m.get(2).itemRevision()));
        assertThat(apply.snapshot(s.source().deck())).as("the source is not touched").isEqualTo(sourceBefore);
        assertThat(ApplyWorlds.classes(apply.plan(copy))).isEqualTo(Map.of(material(s.conflict()), "CONFLICT", material(s.removed()), "DELETED"));

        // the badge stays (the owner still has two decisions) and the notice says what came
        JsonNode fork = auto.deck(copy).path("fork");
        assertThat(fork.path("updateAvailable").booleanValue(false)).isTrue();
        assertThat(fork.path("autoUpdate").booleanValue(false)).isTrue();
        JsonNode last = fork.path("lastUpdate");
        UUID head = worlds.headRevisionOf(mine);
        assertThat(last.path("deckRevisionId").stringValue(null)).isEqualTo(head.toString());
        assertThat(last.path("mode").stringValue(null)).isEqualTo("AUTO");
        assertThat(last.path("seen").booleanValue(true)).isFalse();
        assertThat(last.path("counts").path("applied").intValue()).isEqualTo(2);
        assertThat(last.path("counts").path("pending").intValue()).isEqualTo(2);
        assertThat(Instant.parse(last.path("appliedAt").stringValue(null))).isAfter(Instant.now().minusSeconds(120));
        assertThat(names(fork)).as("the fork member of forks.json").isEqualTo(names(forks().path("fork").path("updatedAutomatically")));
        assertThat(names(last)).isEqualTo(names(forks().path("fork").path("updatedAutomatically").path("lastUpdate")));
        assertThat(names(last)).isEqualTo(names(CONTRACT.path("history").path("body").path("entries").get(0)).stream().filter(n -> !Set.of("upstreamRevisionId", "units", "unitsTruncated").contains(n))
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new)));
        JsonNode listed = json(worlds.get("/decks", learner)).path("items").get(0).path("fork");
        assertThat(listed.path("lastUpdate").path("deckRevisionId").stringValue(null)).as("the list carries the same notice").isEqualTo(head.toString());
        assertThat(auto.log(mine)).hasSize(1);
        assertThat(auto.log(mine).getFirst().path("mode").stringValue(null)).isEqualTo("AUTO");
        assertThat(auto.log(mine).getFirst().path("upstream_revision_id").stringValue(null)).isEqualTo(s.published().toString());

        // doing it again changes nothing: the plan offers nothing safe any more
        String settled = auto.full(mine);
        assertThat(tryAuto(copy).outcome()).isEqualTo(ApplyService.AutoOutcome.NOTHING_SAFE);
        assertThat(auto.full(mine)).isEqualTo(settled);
        // the notice goes when the owner has seen it
        assertThat(worlds.send("POST", "/decks/" + mine + "/update-history/" + head + ":seen", learner, null, null).statusCode()).isEqualTo(200);
        assertThat(auto.deck(copy).path("fork").has("lastUpdate")).isFalse();
    }

    @Test
    void theReadPathsNeverApplyAnythingAndNeverWrite() {
        Scene s = scene(true);
        Copy copy = s.copy();
        UUID mine = copy.deck();
        String before = auto.full(mine);
        long jobsBefore = jdbc.sql("SELECT count(*) FROM app_learning.learning_job").query(Long.class).single();
        // a copy that follows the author automatically, with an update waiting, read every way there is
        for (String path : new String[] {"/decks/" + mine, "/decks", "/decks/" + mine + "/update-plan", "/decks/" + mine + "/update-plan?limit=1", "/decks/" + mine + "/update-history",
                "/decks/" + mine + "/items", "/decks/" + mine + "/exercises", "/decks/" + mine + "/insights"}) {
            assertThat(worlds.get(path, copy.owner()).statusCode()).as(path).isEqualTo(200);
        }
        assertThat(auto.full(mine)).isEqualTo(before);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.learning_job").query(Long.class).single()).as("no job was enqueued by a read").isEqualTo(jobsBefore);
        assertThat(worlds.base(mine)).isNotEqualTo(s.published());
        assertThat(auto.deck(copy).path("fork").path("updateAvailable").booleanValue(false)).isTrue();
    }

    @Test
    void aCopyThatIsUpToDateClosedBusyOrHasNothingSafeIsLeftExactlyAsItIs() {
        // up to date: the owner applied everything himself
        Scene s = scene(true);
        String version = apply.version(s.copy());
        assertThat(apply.apply(s.copy(), UUID.randomUUID(), version, true).statusCode()).isEqualTo(200);
        assertThat(apply.apply(s.copy(), true, ApplyWorlds.Pick.material(s.conflict(), "ACCEPT"), ApplyWorlds.Pick.material(s.removed(), "DELETE")).statusCode()).isEqualTo(200);
        assertThat(apply.plan(s.copy()).state()).isEqualTo(UpdatePlan.State.UP_TO_DATE);
        String settled = auto.full(s.copy().deck());
        assertThat(tryAuto(s.copy()).outcome()).isEqualTo(ApplyService.AutoOutcome.UP_TO_DATE);
        assertThat(auto.full(s.copy().deck())).isEqualTo(settled);
        assertThat(auto.log(s.copy().deck())).as("the owner's own applies are in «Что нового» too").extracting(entry -> entry.path("mode").stringValue(null)).containsExactly("MANUAL", "MANUAL");

        // detached and deleted: nothing to do
        Scene d = scene(true);
        assertThat(worlds.send("POST", "/decks/" + d.copy().deck() + "/fork:detach", d.copy().owner(), null, null).statusCode()).isEqualTo(200);
        String detached = auto.full(d.copy().deck());
        assertThat(tryAuto(d.copy()).outcome()).isEqualTo(ApplyService.AutoOutcome.CLOSED);
        assertThat(auto.full(d.copy().deck())).isEqualTo(detached);
        assertThat(tryAuto(new Copy(UUID.randomUUID(), d.copy().deck())).outcome()).as("not his copy").isEqualTo(ApplyService.AutoOutcome.CLOSED);

        // a source that went private cannot be read: nothing to do
        Scene p = scene(true);
        long revisions = apply.revisions(p.copy().deck());
        worlds.level(p.source().author(), p.source().deck(), "PRIVATE");
        String hidden = auto.full(p.copy().deck());
        assertThat(tryAuto(p.copy()).outcome()).isEqualTo(ApplyService.AutoOutcome.CLOSED);
        assertThat(auto.full(p.copy().deck())).isEqualTo(hidden);
        assertThat(apply.revisions(p.copy().deck())).isEqualTo(revisions);
    }

    @Test
    void anApplyAboveTheSynchronousLimitIsTheCombinedJobAndIsLoggedAsAutomaticAndAManualJobInTheWayMakesTheCopyBusy() {
        Source source = worlds.source("LINK", 2, 0);
        List<UUID> bulk = worlds.bulk(source.author(), source.deck(), 30, "материал");
        worlds.publish(source.author(), source.deck(), "LINK");
        Copy copy = auto.autoCopy(source, hand);
        List<UUID> revisions = apply.saveAll(source.author(), source.deck(), bulk, 2, "bulk edit");
        worlds.publish(source.author(), source.deck(), "LINK");
        UUID published = worlds.published(source.deck());
        UUID mine = copy.deck();

        // the owner's own apply is a job that has not run yet: the automatic one steps aside and writes nothing
        String version = apply.version(copy);
        assertThat(apply.apply(copy, UUID.randomUUID(), version, true).statusCode()).isEqualTo(202);
        String waiting = auto.full(mine);
        assertThat(tryAuto(copy).outcome()).isEqualTo(ApplyService.AutoOutcome.BUSY);
        assertThat(auto.full(mine)).isEqualTo(waiting);
        assertThat(hand.runOne("deck.combined.publish")).isTrue();
        assertThat(auto.log(mine)).extracting(entry -> entry.path("mode").stringValue(null)).containsExactly("MANUAL");

        // a second round of edits, too big for the request: the automatic apply queues the combined job and the final writer logs it as automatic
        List<UUID> second = apply.saveAll(source.author(), source.deck(), bulk, 2, "second bulk edit");
        worlds.publish(source.author(), source.deck(), "LINK");
        assertThat(revisions).hasSameSizeAs(second);
        assertThat(tryAuto(copy).outcome()).isEqualTo(ApplyService.AutoOutcome.QUEUED);
        assertThat(worlds.base(mine)).as("nothing yet: the job has not run").isEqualTo(published);
        assertThat(hand.runOne("deck.combined.publish")).isTrue();
        assertThat(worlds.base(mine)).isEqualTo(worlds.published(source.deck()));
        for (int index = 0; index < bulk.size(); index++) assertThat(worlds.itemHead(mine, bulk.get(index))).isEqualTo(second.get(index));
        List<JsonNode> log = auto.log(mine);
        assertThat(log).extracting(entry -> entry.path("mode").stringValue(null)).containsExactly("MANUAL", "AUTO");
        assertThat(log.get(1).path("applied_units").intValue()).isEqualTo(30);
        assertThat(log.get(1).path("pending_units").intValue()).isZero();
        assertThat(apply.plan(copy).state()).isEqualTo(UpdatePlan.State.UP_TO_DATE);
    }

    @Test
    void anAutomaticApplyOfAnExerciseAndItsObjectiveIsLoggedWithTheObjective() {
        Source source = worlds.source("LINK", 3, 2);
        Copy copy = auto.autoCopy(source, hand);
        Authored revised = worlds.revise(source.author(), source.deck(), source.materials().get(1), source.exercises().get(1), "Вопрос автора", "Цель автора");
        worlds.publish(source.author(), source.deck(), "LINK");
        assertThat(hand.runOne(AutoWorlds.FANOUT)).isTrue();
        assertThat(hand.runOne(AutoWorlds.AUTO)).isTrue();
        assertThat(apply.exerciseHead(copy.deck(), revised.exerciseId())).isEqualTo(revised.revisionId());
        assertThat(apply.objectiveHead(copy.deck(), revised.objectiveId())).isEqualTo(revised.objectiveRevisionId());
        UUID revision = worlds.headRevisionOf(copy.deck());
        assertThat(jdbc.sql("SELECT previous_revision_id,revision_id FROM app_learning.fork_update_log_objective WHERE deck_id=:d AND deck_revision_id=:r AND objective_id=:o")
                .param("d", copy.deck()).param("r", revision).param("o", revised.objectiveId())
                .query((row, ignored) -> row.getObject(1, UUID.class) + " " + row.getObject(2, UUID.class)).list())
                .containsExactly(source.exercises().get(1).objectiveRevisionId() + " " + revised.objectiveRevisionId());
        assertThat(auto.log(copy.deck()).getFirst().path("applied_units").intValue()).isEqualTo(2);
    }

    @Test
    void theLogAndItsObjectiveRowsGoWithTheCopyAndTheSourcesOtherCopiesAreNotTouched() {
        Source source = worlds.source("LINK", 3, 2);
        Copy copy = auto.autoCopy(source, hand);
        Copy neighbour = auto.autoCopy(source, hand);
        Authored revised = worlds.revise(source.author(), source.deck(), source.materials().get(1), source.exercises().get(1), "Вопрос автора", "Цель автора");
        worlds.publish(source.author(), source.deck(), "LINK");
        assertThat(hand.runOne(AutoWorlds.FANOUT)).isTrue();
        assertThat(hand.runOne(AutoWorlds.AUTO)).isTrue();
        assertThat(hand.runOne(AutoWorlds.AUTO)).isTrue();
        assertThat(auto.log(copy.deck())).hasSize(1);
        assertThat(auto.log(neighbour.deck())).hasSize(1);
        long objectives = jdbc.sql("SELECT count(*) FROM app_learning.fork_update_log_objective WHERE objective_id=:o").param("o", revised.objectiveId()).query(Long.class).single();
        assertThat(objectives).isEqualTo(2);
        // the account purge deletes the link of the copy to its source before the copy: its log and the objective rows of it go by the foreign keys
        assertThat(jdbc.sql("DELETE FROM app_learning.deck_fork WHERE deck_id=:d").param("d", copy.deck()).update()).isOne();
        assertThat(auto.log(copy.deck())).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.fork_update_log_objective WHERE deck_id=:d").param("d", copy.deck()).query(Long.class).single()).isZero();
        assertThat(auto.log(neighbour.deck())).hasSize(1);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.fork_update_log_objective WHERE deck_id=:d").param("d", neighbour.deck()).query(Long.class).single()).isOne();
    }

    @Test
    void aCopyWhoseOwnerSwitchedTheSettingOffAfterTheJobWasQueuedIsLeftAlone() {
        Scene s = scene(true);
        assertThat(hand.runOne(AutoWorlds.FANOUT)).isTrue();
        assertThat(auto.jobs(AutoWorlds.AUTO, s.copy().deck())).hasSize(1);
        assertThat(auto.autoUpdate(s.copy().owner(), s.copy().deck(), false).statusCode()).isEqualTo(200);
        String before = auto.full(s.copy().deck());
        assertThat(hand.runOne(AutoWorlds.AUTO)).isTrue();
        assertThat(auto.jobs(AutoWorlds.AUTO, s.copy().deck()).getFirst().state()).isEqualTo("SUCCEEDED");
        assertThat(auto.full(s.copy().deck())).as("the owner's setting wins over a job that was queued while it was on").isEqualTo(before);
    }

    @Test
    void theKillSwitchAlsoStopsAnAutomaticApplyThatWasAlreadyQueuedAsTheCombinedJob() {
        Source source = worlds.source("LINK", 2, 0);
        List<UUID> bulk = worlds.bulk(source.author(), source.deck(), 30, "материал");
        worlds.publish(source.author(), source.deck(), "LINK");
        Copy copy = auto.autoCopy(source, hand);
        apply.saveAll(source.author(), source.deck(), bulk, 2, "bulk edit");
        worlds.publish(source.author(), source.deck(), "LINK");
        UUID mine = copy.deck();
        assertThat(tryAuto(copy).outcome()).isEqualTo(ApplyService.AutoOutcome.QUEUED);
        long revisions = apply.revisions(mine);
        UUID base = worlds.base(mine);
        String items = apply.snapshot(mine);

        // the operator switches the automatic mode off while the combined job waits
        org.mockito.Mockito.doReturn(false).when(settings).enabled();
        assertThat(hand.runOne("deck.combined.publish")).isTrue();
        JobRow job = jdbc.sql("SELECT job_id,dedupe_key,account_id,state,payload::text AS payload,progress_count,FALSE AS waiting FROM app_learning.learning_job "
                        + "WHERE queue='deck.combined.publish' AND payload->>'deckId'=:d ORDER BY created_at DESC LIMIT 1").param("d", mine.toString())
                .query((row, ignored) -> new JobRow(row.getObject(1, UUID.class), row.getString(2), row.getObject(3, UUID.class), row.getString(4), null, row.getLong(6), false)).single();
        assertThat(job.state()).isEqualTo("FAILED");
        assertThat(jdbc.sql("SELECT last_error FROM app_learning.learning_job WHERE job_id=:j").param("j", job.id()).query(String.class).single()).isEqualTo("auto_disabled");
        assertThat(apply.revisions(mine)).as("no revision").isEqualTo(revisions);
        assertThat(worlds.base(mine)).as("the base did not move").isEqualTo(base);
        assertThat(apply.decisions(mine)).as("no decision").isEmpty();
        assertThat(auto.log(mine)).as("no entry of «Что нового»").isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.combined_publication WHERE deck_id=:d").param("d", mine).query(Long.class).single()).as("the stored command went with the job").isZero();
        assertThat(apply.snapshot(mine).replaceAll("\"(jobs|commands)\": \\d+", "")).isEqualTo(items.replaceAll("\"(jobs|commands)\": \\d+", ""));
        // the owner's own apply is not affected by the switch of the automatic mode
        assertThat(apply.apply(copy, true).statusCode()).isIn(200, 202);
    }

    @Test
    void aFanOutThatWasPausedWhileTheAuthorPublishedAgainEndsAndTheNewerOneCoversEveryCopy() {
        Source source = worlds.source("LINK", 3, 0);
        UUID deck = source.deck();
        List<Copy> copies = new ArrayList<>();
        for (int index = 0; index < 5; index++) copies.add(auto.autoCopy(source, hand));
        ALLOWANCE.set(3);
        worlds.editText(source.author(), deck, source.materials().get(0).member(), "second");
        worlds.publish(source.author(), deck, "LINK");
        UUID second = worlds.published(deck);
        assertThat(hand.runOne(AutoWorlds.FANOUT)).isTrue();
        assertThat(auto.autoJobs(second)).hasSize(3);
        JobRow paused = auto.jobs(AutoWorlds.FANOUT, deck).getFirst();
        assertThat(paused.waiting()).isTrue();

        // the author publishes again during the pause: the second fan-out is queued behind the first
        worlds.editText(source.author(), deck, source.materials().get(1).member(), "third");
        worlds.publish(source.author(), deck, "LINK");
        UUID third = worlds.published(deck);
        assertThat(auto.jobs(AutoWorlds.FANOUT, deck)).hasSize(2);
        for (int round = 0; round < 8 && auto.jobs(AutoWorlds.FANOUT, deck).stream().anyMatch(job -> job.state().equals("QUEUED")); round++) {
            clock.advance(Duration.ofSeconds(61));
            auto.jobs(AutoWorlds.FANOUT, deck).stream().filter(job -> job.state().equals("QUEUED")).forEach(job -> hand.makeDue(job.id()));
            hand.runOne(AutoWorlds.FANOUT);
        }
        List<JobRow> fanouts = auto.jobs(AutoWorlds.FANOUT, deck);
        assertThat(fanouts).extracting(JobRow::state).containsOnly("SUCCEEDED");
        assertThat(fanouts.getFirst().progressCount()).as("the older one gave up where it stood").isEqualTo(3);
        assertThat(auto.autoJobs(second)).as("nothing more was handed out under the old revision").hasSize(3);
        assertThat(auto.autoJobs(third)).as("the newer one covers every copy").hasSize(5);
        assertThat(auto.autoJobs(third)).extracting(JobRow::account).containsExactlyInAnyOrderElementsOf(copies.stream().map(Copy::owner).toList());
    }
}
