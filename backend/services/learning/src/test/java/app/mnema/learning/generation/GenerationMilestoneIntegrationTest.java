package app.mnema.learning.generation;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code WORKSHOP_APPROVED} of the «Путь Mnema» (OB-301): approving a generated artifact publishes it into the owner's deck, and in that transaction the milestone is written. A refused
 * approval writes nothing and a replay of an approved one writes nothing again. The approved material is a new material of a deck of the owner's own, so it is also the first
 * {@code OWN_MATERIAL}.
 */
class GenerationMilestoneIntegrationTest extends GenerationReviewSupport {
    private List<String> milestones(UUID owner) {
        return jdbc.sql("SELECT milestone_key || ':' || progress || ':' || (achieved_at IS NOT NULL) FROM app_learning.account_milestone WHERE account_id=:a ORDER BY milestone_key")
                .param("a", owner).query(String.class).list();
    }

    @Test
    void anApprovedArtifactIsTheWorkshopMilestoneOnceAndARefusedOrReplayedApprovalIsNot() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = proposal(owner, deck, spec("20 глаголов движения"));
        long version = deckVersion(deck);
        UUID revision = deckRevision(deck);
        List<String> beforeApproval = milestones(owner);
        assertThat(beforeApproval).containsExactly("FIRST_DECK:1:true");

        // a stale version is refused: nothing is published, so nothing is recorded
        problem(approve(owner, deck, proposal, approvalBody(UUID.randomUUID(), proposal, revision), "\"" + (version + 1) + "\""), 412, "VERSION_CONFLICT");
        assertThat(milestones(owner)).isEqualTo(beforeApproval);

        UUID command = UUID.randomUUID();
        MockHttpServletResponse response = approve(owner, deck, proposal, command);
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        assertThat(milestones(owner)).containsExactly("FIRST_DECK:1:true", "OWN_MATERIAL:1:true", "WORKSHOP_APPROVED:1:true");
        String achievedAt = jdbc.sql("SELECT achieved_at::text FROM app_learning.account_milestone WHERE account_id=:a AND milestone_key='WORKSHOP_APPROVED'")
                .param("a", owner).query(String.class).single();

        // an exact retry is the stored acknowledgement and writes nothing
        MockHttpServletResponse replay = send(owner, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post(base(deck, proposal.session()) + "/artifacts/" + proposal.artifact() + "/approval").contentType("application/json")
                .header("If-Match", "\"" + version + "\"").content(approvalBody(command, proposal, revision).toString()));
        assertThat(replay.getHeader("Idempotency-Replayed")).isEqualTo("true");
        assertThat(jdbc.sql("SELECT achieved_at::text FROM app_learning.account_milestone WHERE account_id=:a AND milestone_key='WORKSHOP_APPROVED'")
                .param("a", owner).query(String.class).single()).isEqualTo(achievedAt);
        assertThat(milestones(owner)).containsExactly("FIRST_DECK:1:true", "OWN_MATERIAL:1:true", "WORKSHOP_APPROVED:1:true");
    }

    @Test
    void aProposalThatIsOnlyReadOrRejectedIsNotApproved() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = proposal(owner, deck, spec("20 глаголов движения"));
        getSession(owner, deck, proposal.session());
        assertThat(milestones(owner)).containsExactly("FIRST_DECK:1:true");
    }
}
