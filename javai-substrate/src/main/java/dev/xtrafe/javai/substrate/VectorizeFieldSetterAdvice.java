package dev.xtrafe.javai.substrate;

import dev.xtrafe.javai.model.JavAIRuntime;
import net.bytebuddy.asm.Advice;

/**
 * Inlined around a woven setter for a {@code @Vectorize} field: {@link #onEnter} captures the field's
 * value *before* the original assignment runs (needed for the no-op-reassignment check below -- by the
 * time an exit advice fires, the assignment has already happened, so the old value would be gone), then
 * the original assignment runs untouched, then {@link #onExit} fires -- exactly the shape shown in
 * doc/spec/acceleration-substrate.md, just with an enter half added. Delegates everything to
 * {@link JavAIRuntime#vectorizeFieldMutated}, which -- unless the new value {@code Objects.equals} the old
 * one, in which case it's a complete no-op -- marks {@code FieldDirty}, registers a dependency edge if the
 * new value happens to be graph-shaped, propagates {@code SummaryDirty} to whatever already depends on
 * this object, bumps this field's own {@code VectorCacheSlot} generation, and, under
 * {@code EmbeddingConsistencyMode.EVENTUAL_CONSISTENCY}/{@code COALESCED_CONSISTENCY}, dispatches this
 * field's eager background recompute.
 *
 * <p>This same {@code Advice} class is inlined into every {@code @Vectorize} field's setter on a woven
 * class, so it can't receive the field name as a baked-in constant the way {@code MethodCall}-based
 * synthesized methods do ({@code vector()}'s own {@code vectorizeFieldsCsv}, for instance) -- {@code Advice}
 * only sees what's reflectively available on the method it's inlined into, plus whatever
 * {@code Advice.withCustomMapping()} explicitly binds. {@link JavAIWeaver} binds {@link FieldName} to the
 * actual field name, per setter, at weave time -- deliberately not {@code @Advice.Origin}, which produced a
 * real, reproducible bug: with more than one {@code @JavAIVectorizable} class present, deriving the field
 * name from the setter's own method name at instrumentation time caused the advice to silently never fire on
 * a second class's setter (confirmed empirically, root cause not fully understood, not worth chasing further
 * given a strictly more robust alternative -- an explicit, per-site bound constant -- was available). The
 * same bound {@code fieldName} constant is what lets {@link #onEnter} read the field's pre-assignment value
 * reflectively ({@link JavAIRuntime#readField}) despite this being one shared Advice class instrumenting
 * many differently-named fields' setters.
 */
class VectorizeFieldSetterAdvice {

    @Advice.OnMethodEnter
    static Object onEnter(@Advice.This Object self, @FieldName String fieldName) {
        return JavAIRuntime.readField(self, fieldName);
    }

    @Advice.OnMethodExit
    static void onExit(@Advice.This Object self, @FieldName String fieldName,
            @Advice.Argument(0) Object newValue, @Advice.Enter Object oldValue) {
        JavAIRuntime.vectorizeFieldMutated(self, fieldName, oldValue, newValue);
    }

    private VectorizeFieldSetterAdvice() {
    }
}
