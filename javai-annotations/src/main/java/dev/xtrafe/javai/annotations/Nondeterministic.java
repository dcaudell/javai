package dev.xtrafe.javai.annotations;

import java.lang.annotation.*;

/**
 * Marks a method whose result can vary between calls (typically a model/embedding call).
 * Never assume its result is safe to memoize as if it were pure. See
 * doc/ai-guidance/JavAI_Codegen_Guidance.md.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Nondeterministic {
}
