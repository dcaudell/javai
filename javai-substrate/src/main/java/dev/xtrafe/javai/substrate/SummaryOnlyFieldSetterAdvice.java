package dev.xtrafe.javai.substrate;

import dev.xtrafe.javai.model.JavAIRuntime;
import net.bytebuddy.asm.Advice;

/**
 * Same as {@link VectorizeFieldSetterAdvice}, for a field that carries {@code @Summary} but not
 * {@code @Vectorize} -- a graph edge (a reference or a collection), not something that contributes to this
 * object's own local text. Does <em>not</em> mark {@code FieldDirty}: this object's own {@code vector()}
 * doesn't depend on this field, only {@code summaryVector()} (via the dependency edge) does.
 */
class SummaryOnlyFieldSetterAdvice {

    @Advice.OnMethodExit
    static void onExit(@Advice.This Object self, @Advice.Argument(0) Object newValue) {
        JavAIRuntime.registerDependency(self, newValue);
        JavAIRuntime.propagateDirty(self);
    }

    private SummaryOnlyFieldSetterAdvice() {
    }
}
