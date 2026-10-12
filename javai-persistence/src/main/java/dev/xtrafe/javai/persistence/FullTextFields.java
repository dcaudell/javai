package dev.xtrafe.javai.persistence;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import dev.xtrafe.javai.annotations.FullText;

/** The {@link FullText} fields of a type (OMI-642), validated once and in one place. */
final class FullTextFields {

    /** A text-search configuration name: what {@code ::regconfig} accepts, and nothing that could be SQL. */
    private static final Pattern LANGUAGE = Pattern.compile("[a-z_]{1,63}");

    /** One indexed field: its name, its language, and whether it is also trigram-indexed. */
    record Indexed(String name, String language, boolean fuzzy) {
    }

    private FullTextFields() {
    }

    static List<Indexed> of(Class<?> type) {
        List<Indexed> fields = new ArrayList<>();
        for (Field field : EntityReflection.allFields(type)) {
            FullText annotation = field.getAnnotation(FullText.class);
            if (annotation == null) {
                continue;
            }
            if (field.getType() != String.class) {
                throw new IllegalArgumentException("@FullText on " + type.getName() + "." + field.getName()
                        + " needs a String field, not " + field.getType().getName());
            }
            if (!LANGUAGE.matcher(annotation.language()).matches()) {
                throw new IllegalArgumentException("@FullText(language = \"" + annotation.language() + "\") on "
                        + type.getName() + "." + field.getName() + " is not a text-search configuration name");
            }
            fields.add(new Indexed(field.getName(), annotation.language(), annotation.fuzzy()));
        }
        return fields;
    }

    /** The one {@link FullText} field named {@code fieldName} on {@code type}, or a refusal naming the known ones. */
    static Indexed require(Class<?> type, String fieldName) {
        List<Indexed> fields = of(type);
        for (Indexed field : fields) {
            if (field.name().equals(fieldName)) {
                return field;
            }
        }
        throw new IllegalArgumentException("'" + fieldName + "' is not a @FullText field on " + type.getName()
                + " -- known: " + fields.stream().map(Indexed::name).toList());
    }
}
