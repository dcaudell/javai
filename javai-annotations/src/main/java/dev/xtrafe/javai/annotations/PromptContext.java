package dev.xtrafe.javai.annotations;

import java.lang.annotation.*;

/**
 * Marks a field as included in {@code dev.xtrafe.javai.runtime.PromptContext.defaultMarshall(Object)}'s
 * GSON-based rendering -- an allowlist, not a blocklist: an unannotated field (e.g. a woven class's cached
 * embedding vector or dirty-tracking state) is excluded by default, not included and then filtered out.
 * See {@code javai-runtime}'s {@code PromptContext} javadoc for the full marshalling design.
 *
 * <p><b>Shares its simple name with {@code dev.xtrafe.javai.runtime.PromptContext}</b> (the record this
 * annotation feeds), deliberately -- different packages, so it compiles, but code that lives inside that
 * class can never {@code import} this annotation (its own simple name is already in scope there) and must
 * reference it by fully-qualified name instead. Not an oversight; documented on both types.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface PromptContext {
}
