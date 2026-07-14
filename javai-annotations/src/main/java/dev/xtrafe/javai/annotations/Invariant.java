package dev.xtrafe.javai.annotations;

import java.lang.annotation.*;

/**
 * A compiler-checked class invariant, applying across every method of the class -- including
 * ones not directly touched by a given change. See doc/ai-guidance/JavAI_Codegen_Guidance.md.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Repeatable(Invariant.List.class)
public @interface Invariant {

    String value();

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface List {
        Invariant[] value();
    }
}
