package dev.xtrafe.javai.annotations;

import java.lang.annotation.*;

/**
 * A compiler-checked precondition -- a hard pass/fail oracle for agent-written code, not
 * documentation. Treat as load-bearing. See doc/ai-guidance/JavAI_Codegen_Guidance.md before touching
 * a method that carries this.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@Repeatable(Requires.List.class)
public @interface Requires {

    String value();

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.TYPE})
    @interface List {
        Requires[] value();
    }
}
