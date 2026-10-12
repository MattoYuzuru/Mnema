package app.mnema.learning.update.apply;

import app.mnema.learning.platform.concurrency.VersionConflictException;
import app.mnema.learning.platform.idempotency.CommandReceiptService;
import app.mnema.learning.platform.jobs.PermanentJobException;
import app.mnema.learning.update.FinalWriter;
import app.mnema.learning.update.FinalWrites;
import app.mnema.learning.update.auto.AutoUpdateSettings;
import app.mnema.learning.update.apply.ApplyRepository.Pins;
import app.mnema.learning.update.apply.ApplyRows.Op;
import app.mnema.learning.update.apply.ApplyRows.Row;
import app.mnema.learning.update.plan.PlanVersion;
import app.mnema.learning.update.plan.UpdatePlanService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.UUID;

/**
 * The hook of the last transaction of a combined publication for the apply command (Updates/4, #443; {@link FinalWriter}). It runs inside the engine's transaction, before the
 * engine's own receipt, so the revision, the new base, the decisions and the answer of the command exist together or not at all (a refusal rolls the whole publication back). The
 * engine calls it in two situations, and the writer must be right in both: when the command adopted something, after the heads and the journal were written (the engine advanced
 * the deck with a compare-and-set, so the head is the new revision and the old head was held); and when the command adopted NOTHING (it only declines, leaves units pending or
 * records decisions), when there is no revision, no head moved and NO compare-and-set was made, so the old head is held by nothing but this writer. It writes, keyed by the copy:
 * <ol>
 *   <li>the lock of the copy's link to its source ({@code deck_fork}, FOR UPDATE): an apply of the same copy that commits first makes this one stale;</li>
 *   <li>the head of the copy's deck, under a share lock (nobody commits an edit of the deck until this transaction ends): it must be the revision the command made, or, when it
 *       made none, the one the plan was read at;</li>
 *   <li>the check of the pins the plan was built on, now under those locks: the base and the published revision are the planned ones, and the plan version recomputed from the
 *       copy's head, the published revision, the base and the recorded decisions (the same query the plan reads them with) is the one the command carries. Any difference is
 *       {@link VersionConflictException}: in a request that is {@code 409 UPDATE_PLAN_STALE}, in the job a permanent failure, and nothing is written;</li>
 *   <li>the base of the copy moved to the published revision (the migration V61 lets it move forward only);</li>
 *   <li>the decisions: every PENDING row of the copy is dropped, the rows of the units taken are dropped, the rows of the declined and of the still pending units are written;</li>
 *   <li>the receipt that holds the answer of the command, under the id derived from the client's ({@link ApplyReceipt}), and the log of the apply ({@code fork_apply_log}); when
 *       the command made a revision, also its entry of «Что нового» ({@code fork_update_log}, V65) with the mode, AUTO or MANUAL, and the objective heads it moved.</li>
 * </ol>
 * An AUTOMATIC command (mode {@code AUTO}) is refused first of all while {@code learning.updates.auto.enabled} is off ({@code PermanentJobException("auto_disabled")}, nothing written). It handles the type {@value ApplyWrites#TYPE} and no other, so other kinds of command can have a writer of their own next to it.
 */
@Component
class ApplyFinalWriter implements FinalWriter {
    private final ApplyRepository repository;
    private final UpdatePlanService plans;
    private final CommandReceiptService receipts;
    private final AutoUpdateSettings auto;

    ApplyFinalWriter(ApplyRepository repository, UpdatePlanService plans, CommandReceiptService receipts, AutoUpdateSettings auto) {
        this.repository = repository;
        this.plans = plans;
        this.receipts = receipts;
        this.auto = auto;
    }

    @Override
    public boolean handles(String type) { return ApplyWrites.TYPE.equals(type); }

    @Override
    public void write(FinalWrites writes, Context context) {
        ApplyWrites apply = ApplyWrites.of(writes);
        // the kill switch of the automatic mode (§14) also stops an automatic apply that was queued as the combined job before it went off: it ends FAILED with this code and writes
        // nothing (no revision, no decision, no log). The owner's own command is not affected.
        if (ApplyWrites.AUTO.equals(apply.mode()) && !auto.enabled()) throw new PermanentJobException("auto_disabled");
        UUID deck = context.deckId();
        Pins pins = repository.lock(deck).orElseThrow(VersionConflictException::new);
        UUID head = repository.lockHead(deck).orElseThrow(VersionConflictException::new);
        if (!head.equals(context.changed() ? context.resultRevisionId() : context.baseRevisionId())) throw new VersionConflictException();
        UUID published = repository.published(pins.upstreamDeckId()).orElseThrow(VersionConflictException::new);
        if (!pins.baseRevisionId().equals(apply.base()) || !published.equals(apply.published())) throw new VersionConflictException();
        String version = PlanVersion.of(context.baseRevisionId(), published, pins.baseRevisionId(), plans.decisions(deck));
        if (!version.equals(apply.planVersion())) throw new VersionConflictException();

        if (!repository.moveBase(deck, pins.baseRevisionId(), published)) throw new VersionConflictException();
        repository.deletePending(deck);
        repository.delete(deck, rows(apply, Op.REMOVE));
        repository.upsert(deck, rows(apply, Op.KEEP_MINE, Op.DECLINE_NEW, Op.KEEP_DELETED, Op.PENDING));

        ObjectNode answer = ApplyAnswer.applied(apply.command(), context.changed() ? context.resultRevisionId() : null, apply.applied(), apply.declined(), apply.pending(),
                apply.alsoAccepted(), published);
        receipts.execute(ApplyReceipt.identity(context.actorId(), apply.command()), apply.request(), () -> answer);
        repository.logApply(deck);
        // «Что нового»: an apply that made a revision of the copy is noted with it (the units come from the journals of that revision)
        if (context.changed()) {
            repository.logUpdate(deck, context.resultRevisionId(), published, apply.mode(), apply.applied(), apply.pending());
            repository.logObjectives(deck, context.resultRevisionId(), apply.objectives());
        }
    }

    private static List<Row> rows(ApplyWrites apply, Op... ops) {
        List<Op> wanted = List.of(ops);
        return apply.rows().stream().filter(row -> wanted.contains(row.op())).toList();
    }
}
