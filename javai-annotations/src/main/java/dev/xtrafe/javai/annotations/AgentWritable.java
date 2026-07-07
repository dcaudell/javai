package dev.xtrafe.javai.annotations;

import java.lang.annotation.*;

/**
 * Edit-scope permission: an autonomous agent may generate or rewrite the annotated element.
 * Independent of Java/{@link SearchVisibility} visibility. See doc/JavAI_Codegen_Guidance.md --
 * absence of this annotation is not implicit permission.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
public @interface AgentWritable {
}
