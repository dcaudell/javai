package dev.xtrafe.javai.annotations;

import java.lang.annotation.*;

/**
 * Edit-scope permission: an agent must not modify the annotated element's body under any
 * circumstances. Propose changes in prose instead. See doc/ai-guidance/JavAI_Codegen_Guidance.md.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
public @interface Frozen {
}
