package dev.xtrafe.javai.substrate;

import dev.xtrafe.javai.model.JavAIRuntime;
import net.bytebuddy.asm.Advice;

/**
 * Inlined into the tail of every constructor on a woven class: wires dependency edges for whichever
 * fields are already graph-shaped by the time construction finishes. Needed for the common idiom a
 * setter-based edge alone can't cover -- a {@code @Summary} collection field initialized inline
 * ({@code private final JavAIArrayList<Comment> comments = new JavAIArrayList<>();}) and never reassigned,
 * with elements added later through the collection itself rather than through a setter this weaver
 * instruments.
 */
class ConstructorExitAdvice {

    @Advice.OnMethodExit
    static void onExit(@Advice.This Object self) {
        JavAIRuntime.registerAllFieldDependencies(self);
    }

    private ConstructorExitAdvice() {
    }
}
