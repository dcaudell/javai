package dev.xtrafe.javai.agent;

import dev.xtrafe.javai.runtime.JavAIRuntime;
import net.bytebuddy.asm.Advice;

/**
 * Inlined into the tail of a woven setter for a {@code @Vectorize} field: the original assignment runs
 * untouched, then this fires -- exactly the shape shown in doc/spec/acceleration-substrate.md. Marks
 * {@code FieldDirty} (this object's own {@code vector()} is now stale), registers a dependency edge if the
 * new value happens to be graph-shaped (harmless no-op otherwise), and propagates {@code SummaryDirty} to
 * whatever already depends on this object.
 */
class VectorizeFieldSetterAdvice {

    @Advice.OnMethodExit
    static void onExit(@Advice.This Object self, @Advice.Argument(0) Object newValue) {
        JavAIRuntime.markFieldDirty(self);
        JavAIRuntime.registerDependency(self, newValue);
        JavAIRuntime.propagateDirty(self);
    }

    private VectorizeFieldSetterAdvice() {
    }
}
