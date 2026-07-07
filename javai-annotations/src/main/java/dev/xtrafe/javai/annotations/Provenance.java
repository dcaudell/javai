package dev.xtrafe.javai.annotations;

import java.lang.annotation.*;

/**
 * Records which model generated a piece of code and when. Applied automatically by the
 * weaver/compiler to agent-written code -- never hand-write or hand-edit this annotation.
 * See doc/JavAI_Codegen_Guidance.md.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
public @interface Provenance {

    String generatedBy();

    long timestampEpochMillis();
}
