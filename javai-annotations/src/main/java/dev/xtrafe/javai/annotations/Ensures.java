package dev.xtrafe.javai.annotations;

import java.lang.annotation.*;

/**
 * A compiler-checked postcondition -- must still hold after any change to the annotated
 * method. See doc/JavAI_Codegen_Guidance.md.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@Repeatable(Ensures.List.class)
public @interface Ensures {

    String value();

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.TYPE})
    @interface List {
        Ensures[] value();
    }
}
