package app.mnema.learning.onboarding;

import app.mnema.learning.admin.AdminReportRange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * The owner's holdout report (OB-105): aggregates per variant of {@code onboarding_v1} for the accounts that entered the cohort in a window, the window
 * and the definitions of {@code contracts/onboarding/journey.json} ({@code report}). Counts only: no account id, text or title leaves the statement. A group
 * under {@link #MINIMUM_GROUP} accounts makes the whole answer {@code smallSample}, a warning to the reader and never a refusal.
 */
@Service
public class OnboardingReport {
    static final int MINIMUM_GROUP = 100;
    static final int ACTIVATION_DAYS = 7;
    static final int RETURN_DAYS = 14;

    private final OnboardingRepository repository;

    @Autowired
    OnboardingReport(OnboardingRepository repository) {
        this.repository = repository;
    }

    /** @param range UTC dates, {@code to} exclusive, as validated by {@link AdminReportRange#parse} */
    @Transactional(readOnly = true, timeout = 10)
    public ObjectNode report(AdminReportRange range) {
        Map<OnboardingRepository.Cohort, OnboardingRepository.Group> found = repository.report(range.start(), range.end(), ACTIVATION_DAYS, RETURN_DAYS);
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("experiment", OnboardingService.EXPERIMENT).put("from", range.from().toString()).put("to", range.to().toString());
        result.put("minimumGroup", MINIMUM_GROUP);
        boolean small = false;
        var groups = JsonNodeFactory.instance.arrayNode();
        for (OnboardingRepository.Cohort cohort : new OnboardingRepository.Cohort[] {OnboardingRepository.Cohort.CONTROL, OnboardingRepository.Cohort.TOUR}) {
            OnboardingRepository.Group group = found.getOrDefault(cohort, new OnboardingRepository.Group(0, 0, 0));
            small |= group.accounts() < MINIMUM_GROUP;
            groups.addObject().put("variant", cohort.name().toLowerCase(java.util.Locale.ROOT)).put("accounts", group.accounts())
                    .put("activated", group.activated()).put("returned", group.returned());
        }
        result.put("smallSample", small);
        result.set("groups", groups);
        return result;
    }
}
