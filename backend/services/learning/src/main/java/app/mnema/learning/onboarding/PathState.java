package app.mnema.learning.onboarding;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The «Путь Mnema» of an account as the view carries it ({@code milestones} and {@code path} of {@code contracts/onboarding/journey.json}): the visible milestones in registry
 * order with their state, and the counts over them. A hidden milestone is only named in {@code hidden}, whatever its stored state, and counts in neither {@code done} nor
 * {@code total}.
 *
 * @param done the visible milestones that are achieved
 * @param total the visible milestones
 */
record PathState(List<Milestone> milestones, int done, int total, List<MilestoneKey> hidden) {
    /** {@code TODO} has no record, {@code PARTIAL} a record below its target, {@code DONE} an achieved one. */
    enum State { DONE, PARTIAL, TODO }

    /** One visible milestone; {@code achievedAt} is the stored time and null unless {@code state} is {@code DONE}. */
    record Milestone(MilestoneKey key, State state, int progress, java.time.Instant achievedAt) { }

    PathState {
        milestones = List.copyOf(milestones);
        hidden = List.copyOf(hidden);
    }

    /** @param records the stored records of the account, by key @param hidden the milestones the configuration hides */
    static PathState of(Map<MilestoneKey, MilestoneRepository.Row> records, Set<MilestoneKey> hidden) {
        List<Milestone> visible = new ArrayList<>();
        List<MilestoneKey> hiddenInOrder = new ArrayList<>();
        int done = 0;
        for (MilestoneKey key : MilestoneKey.values()) {
            if (hidden.contains(key)) {
                hiddenInOrder.add(key);
                continue;
            }
            MilestoneRepository.Row record = records.get(key);
            State state = record == null ? State.TODO : record.achievedAt() != null ? State.DONE : record.progress() > 0 && record.progress() < key.target() ? State.PARTIAL : State.TODO;
            if (state == State.DONE) done++;
            visible.add(new Milestone(key, state, record == null ? 0 : Math.min(record.progress(), key.target()), state == State.DONE ? record.achievedAt() : null));
        }
        return new PathState(visible, done, visible.size(), hiddenInOrder);
    }
}
