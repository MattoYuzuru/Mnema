package app.mnema.learning.update.history;

import app.mnema.learning.platform.concurrency.VersionConflictException;
import app.mnema.learning.platform.idempotency.CommandIdentity;
import app.mnema.learning.platform.idempotency.CommandReceiptService;
import app.mnema.learning.update.FinalWriter;
import app.mnema.learning.update.FinalWrites;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * The hook of the last transaction of the combined publication that returns units of an apply (Updates/6 part B, #445; {@link FinalWriter}, type {@value RevertWrites#TYPE}). It
 * runs inside the engine's transaction, after the heads and the journal and before the engine's own receipt: it locks the copy's link (an apply or another return of the same copy
 * waits and is then stale), checks that the head is the revision this command made, writes the decision rows, and stores the answer as a receipt under an id derived from the client's
 * (the engine's own receipt lives under the client's id). A refusal ({@link VersionConflictException}) rolls the whole publication back: no revision, no head, no decision.
 */
@Component
class RevertFinalWriter implements FinalWriter {
    static final String SCOPE = "deck.update";
    static final String TYPE = "revert";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RevertRepository repository;
    private final CommandReceiptService receipts;

    RevertFinalWriter(RevertRepository repository, CommandReceiptService receipts) {
        this.repository = repository;
        this.receipts = receipts;
    }

    @Override
    public boolean handles(String type) { return RevertWrites.TYPE.equals(type); }

    @Override
    public void write(FinalWrites writes, Context context) {
        RevertWrites revert = RevertWrites.of(writes);
        if (!repository.lockCopy(context.deckId())) throw new VersionConflictException();
        UUID head = repository.lockHead(context.deckId()).orElseThrow(VersionConflictException::new);
        // a return always changes the deck: no revision means the command described a state the copy is not in
        if (!context.changed() || !head.equals(context.resultRevisionId())) throw new VersionConflictException();
        repository.decide(context.deckId(), revert.rows());
        ObjectNode answer = JSON.createObjectNode().put("commandId", revert.command().toString()).put("state", "REVERTED").put("deckRevisionId", context.resultRevisionId().toString());
        ArrayNode units = answer.putArray("units");
        revert.units().forEach(units::add);
        receipts.execute(identity(context.actorId(), revert.command()), revert.request(), () -> answer);
    }

    static CommandIdentity identity(UUID owner, UUID command) {
        return new CommandIdentity(derive(command), owner, SCOPE, TYPE);
    }

    /** The receipt id of the answer: a version 4 UUID computed with SHA-256 from the client's id (the engine's receipt uses the client's id itself). */
    static UUID derive(UUID command) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(("mnema.deck.update.revert:" + command).getBytes(StandardCharsets.UTF_8));
            ByteBuffer bytes = ByteBuffer.wrap(digest);
            long high = (bytes.getLong() & ~0xF000L) | 0x4000L;
            long low = (bytes.getLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
            return new UUID(high, low);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Required SHA-256 digest is unavailable", failure);
        }
    }
}
