package dev.xtrafe.javai.supervision.fixtures;

import dev.xtrafe.javai.annotations.AsyncSupervision;
import dev.xtrafe.javai.annotations.SupervisionPointcut;
import dev.xtrafe.javai.annotations.SyncSupervision;

/** Woven fixture exercising method-level PRE/POST/EXCEPTION, mixed sync/async tiers, and a propagated
 *  (non-literal-throw) exception, per {@code SupervisionWeavingTest}. */
public class SupervisedWidget {

    @SyncSupervision({SupervisionPointcut.PRE, SupervisionPointcut.POST})
    public String greet(String name) {
        return "Hello, " + name;
    }

    @SyncSupervision(SupervisionPointcut.EXCEPTION)
    public String mayThrow(boolean shouldThrow) {
        if (shouldThrow) {
            throw new IllegalStateException("boom");
        }
        return "no exception";
    }

    /** Throws from a called helper, not a literal {@code throw} in this method's own body -- proves
     *  EXCEPTION fires for a propagated exception too, unlike the ASM/{@code ATHROW}-based predecessor. */
    @SyncSupervision(SupervisionPointcut.EXCEPTION)
    public String delegatesAndMayThrow(boolean shouldThrow) {
        return helper(shouldThrow);
    }

    private String helper(boolean shouldThrow) {
        if (shouldThrow) {
            throw new IllegalStateException("boom from helper");
        }
        return "no exception";
    }

    @AsyncSupervision({SupervisionPointcut.PRE, SupervisionPointcut.POST})
    public String observedOnly(String value) {
        return value.toUpperCase();
    }

    @SyncSupervision(SupervisionPointcut.POST)
    @AsyncSupervision(SupervisionPointcut.POST)
    public int mixedTier(int value) {
        return value * 2;
    }

    /** Both tiers at both PRE and POST -- unlike {@link #mixedTier}, this exercises the full round trip:
     *  a sync PRE argument rewrite feeding the method body, then a sync POST return-value rewrite, with an
     *  async listener registered for both pointcuts alongside a separate sync listener, per
     *  {@code SupervisionWeavingTest}. */
    @SyncSupervision({SupervisionPointcut.PRE, SupervisionPointcut.POST})
    @AsyncSupervision({SupervisionPointcut.PRE, SupervisionPointcut.POST})
    public String fullyMixedTier(String value) {
        return "handled:" + value;
    }

    public String unsupervised(String value) {
        return "plain:" + value;
    }
}
