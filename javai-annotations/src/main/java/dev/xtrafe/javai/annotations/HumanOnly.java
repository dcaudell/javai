package dev.xtrafe.javai.annotations;

import java.lang.annotation.*;

/**
 * Edit-scope permission, stronger than {@link Frozen}: an agent should not even propose a
 * specific diff for the annotated element -- describe the problem, not the fix, as code.
 * See doc/ai-guidance/JavAI_Codegen_Guidance.md.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
public @interface HumanOnly {
}
