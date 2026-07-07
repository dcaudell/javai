package dev.xtrafe.javai.agent;

import net.bytebuddy.asm.Advice;

/**
 * Inlined into the tail of a woven setter, exactly as shown in doc/spec/acceleration-substrate.md: the
 * original assignment runs untouched, then this fires. Advice code is copied into the target method
 * rather than replacing it, which is what keeps the write itself synchronous while the dirty flag it
 * sets is only ever consumed lazily, on the next {@code vector()} read.
 */
class MarkDirtyAdvice {

    @Advice.OnMethodExit
    static void onExit(@Advice.This Object self) {
        WeaverRuntimeSupport.markDirty(self);
    }

    private MarkDirtyAdvice() {
    }
}
