package app.mnema.learning.study.restart;

import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.idempotency.CommandIdentity;
import app.mnema.learning.platform.idempotency.CommandReceiptService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class StudyRestartService {
    private final StudyRestartRepository repository;
    private final CommandReceiptService receipts;

    public StudyRestartService(StudyRestartRepository repository, CommandReceiptService receipts) {
        this.repository = repository;
        this.receipts = receipts;
    }

    @Transactional(timeout = 10)
    public Result restart(UUID actor, UUID deck, StudyRestartCommand command) {
        UuidPolicy.requireEntityId(actor, "actor");
        UuidPolicy.requireEntityId(deck, "deckId");
        if (!repository.ownsDeck(actor, deck)
                || repository.currentMemberCount(deck, command.memberKeys()) != command.memberKeys().size()) {
            throw new ResourceNotFoundException();
        }
        CommandIdentity identity = new CommandIdentity(command.commandId(), actor, "deck.study", "study.restart");
        ObjectNode envelope = command.envelope(deck);
        boolean[] applied = {false};
        JsonNode result = receipts.execute(identity, envelope, () -> {
            applied[0] = true;
            Instant now = repository.now();
            List<StudyRestartRepository.State> states = new ArrayList<>();
            for (UUID objective : repository.objectives(deck, command.memberKeys())) {
                repository.ensureState(actor, deck, objective, now);
                states.add(repository.lockState(actor, deck, objective));
            }
            states.forEach(state -> repository.restart(actor, deck, command.commandId(), state, now));
            ObjectNode response = JsonNodeFactory.instance.objectNode()
                    .put("commandId", command.commandId().toString()).put("restartedAt", now.toString())
                    .put("objectiveCount", states.size());
            var epochs = response.putArray("learningEpochs");
            states.forEach(state -> epochs.addObject().put("objectiveId", state.objectiveId().toString())
                    .put("learningEpoch", Long.toString(state.learningEpoch() + 1)));
            return response;
        });
        return new Result(result, !applied[0]);
    }

    public record Result(JsonNode acknowledgement, boolean replayed) {
        public Result { acknowledgement = acknowledgement.deepCopy(); }
        @Override public JsonNode acknowledgement() { return acknowledgement.deepCopy(); }
    }
}
