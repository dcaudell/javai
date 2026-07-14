package dev.xtrafe.javai.annotations;

import java.lang.annotation.*;

/**
 * Natural-language statement of purpose, loosely checkable by an LLM oracle where a hard
 * {@link Requires}/{@link Ensures} contract isn't practical. Treat as the real spec when
 * it's more specific than the surrounding code. See doc/ai-guidance/JavAI_Codegen_Guidance.md.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Intent {

    String value();
}
