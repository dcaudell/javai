package dev.xtrafe.javai.annotations;

import java.lang.annotation.*;

/**
 * Marks a method that costs real time or money to call (typically a model/embedding call).
 * Any call site needs visible, deliberate error/retry handling and must not be placed
 * somewhere it runs at unbounded volume. See doc/ai-guidance/JavAI_Codegen_Guidance.md.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Costly {
}
