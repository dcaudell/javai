package dev.xtrafe.javai.e2e.supervision;

import dev.xtrafe.javai.annotations.AsyncSupervision;
import dev.xtrafe.javai.annotations.SupervisionPointcut;
import dev.xtrafe.javai.annotations.SyncSupervision;

import java.util.List;

/**
 * Two methods, woven for opposite tiers, standing in for the two {@code AgenticSupervisionE2ETest} demos --
 * deliberately plain (no {@code @JavAIVectorizable}/{@code @Entity}): Agentic Supervision has no dependency
 * on vectorization or persistence, so nothing here should suggest otherwise.
 */
public class SupervisedTextOperations {

    private static final List<String> ACCOUNTS = List.of("alice", "bob", "carol");

    /**
     * Stands in for "submit some free-text feedback" -- the return value (identical to the input; there's
     * nothing to transform) is what {@link SentimentAggregationSupervisor} analyzes at POST.
     */
    @AsyncSupervision(SupervisionPointcut.POST)
    public String submitFeedback(String feedbackText) {
        return feedbackText;
    }

    /**
     * Stands in for "look up accounts by a caller-supplied filter" -- the argument is what {@link
     * SqlInjectionGuardSupervisor} inspects at PRE, before this body ever runs. A benign filter returns a
     * real (if canned) match list; a filter the guard flags never reaches this body at all, since the
     * guard vetoes by throwing.
     */
    @SyncSupervision(SupervisionPointcut.PRE)
    public List<String> searchAccounts(String filter) {
        String needle = filter.toLowerCase();
        return ACCOUNTS.stream().filter(name -> name.contains(needle)).toList();
    }
}
