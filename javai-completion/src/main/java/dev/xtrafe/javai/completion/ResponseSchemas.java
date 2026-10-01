package dev.xtrafe.javai.completion;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.annotations.SerializedName;

import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The JSON schema a typed completion request sends, and the check a typed result is held to -- both derived
 * from the destination class by one walk over the fields Gson itself would read (OMI-68). A primitive field
 * is required and non-null; every other field is required but may be {@code null}; extra keys are ignored.
 *
 * <p>Supported: primitives and their boxes, {@code String}, {@code char}, {@code BigDecimal},
 * {@code BigInteger}, {@code UUID}, enums, arrays and {@code List}/{@code Set}/{@code Collection} of a
 * supported type, and records or concrete classes made of those. Anything else -- a {@code Map}, an
 * interface, a type that contains itself -- is refused when the request or call names it, not on the reply.
 */
final class ResponseSchemas {

    /** The envelope key holding the typed value; see {@link #envelope}. */
    static final String RESPONSE = "response";

    /** The envelope key holding the prose part, present when the request asked for it. */
    static final String COMPLETION = "completion";

    /** What the request asked for: one object, one object or {@code null}, or a collection of them. */
    enum Kind {
        SINGLE, OPTIONAL, LIST, SET
    }

    private ResponseSchemas() {
    }

    /**
     * The schema a schema-capable {@link Cortex} sends: always an object, since OpenAI and Anthropic both
     * require one at the root, carrying the typed value under {@value #RESPONSE} and, when asked for, the
     * prose part under {@value #COMPLETION} -- listed first, so a model writes its reasoning before its answer.
     */
    static String envelope(Kind kind, Class<?> type, boolean withCompletion) {
        requireObjectType(type);
        JsonObject value = schemaOf(type, new LinkedHashSet<>());
        JsonObject response = switch (kind) {
            case SINGLE -> value;
            case OPTIONAL -> nullable(value);
            case LIST, SET -> arrayOf(value);
        };
        JsonObject properties = new JsonObject();
        JsonArray required = new JsonArray();
        if (withCompletion) {
            properties.add(COMPLETION, typed("string"));
            required.add(COMPLETION);
        }
        properties.add(RESPONSE, response);
        required.add(RESPONSE);
        return objectSchema(properties, required).toString();
    }

    /** Refuses a destination that is not an object -- {@code as(String.class)}, a {@code Map}, an enum. */
    static void requireObjectType(Class<?> type) {
        if (scalarSchema(type) != null || type.isEnum() || type.isArray()
                || Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type)) {
            throw new IllegalArgumentException(type.getName() + " is not an object type -- a typed completion "
                    + "response is a record or class; for several of them use responseListOf/responseSetOf");
        }
        schemaOf(type, new LinkedHashSet<>()); // refuses unsupported field types now rather than on the reply
    }

    /** Whether {@code type} has a field Gson names {@code name} -- which decides whether an object with a
     *  {@value #RESPONSE} key is an envelope or the value itself. */
    static boolean declares(Class<?> type, String name) {
        for (Field field : fieldsOf(type)) {
            if (names(field).contains(name)) {
                return true;
            }
        }
        return false;
    }

    /** Throws a {@link CompletionException} naming the first place {@code element} departs from {@code type}. */
    static void validate(JsonElement element, Type type, String path) {
        Class<?> raw = rawClass(type);
        String scalar = scalarSchema(raw);
        if (scalar != null) {
            validateScalar(element, raw, scalar, path);
        } else if (raw.isEnum()) {
            if (!isString(element) || !enumNames(raw).contains(element.getAsString())) {
                throw violation(path, "one of " + enumNames(raw), element);
            }
        } else if (isCollection(raw)) {
            if (!element.isJsonArray()) {
                throw violation(path, "an array", element);
            }
            Type elementType = elementType(type);
            JsonArray array = element.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                JsonElement item = array.get(i);
                if (item.isJsonNull()) {
                    throw violation(path + "[" + i + "]", "a value", item);
                }
                validate(item, elementType, path + "[" + i + "]");
            }
        } else {
            if (!element.isJsonObject()) {
                throw violation(path, "an object", element);
            }
            JsonObject object = element.getAsJsonObject();
            for (Field field : fieldsOf(raw)) {
                String fieldPath = path + "." + field.getName();
                JsonElement value = valueOf(object, field);
                if (value == null) {
                    throw new CompletionException("The response is missing field " + fieldPath);
                }
                if (value.isJsonNull()) {
                    if (field.getType().isPrimitive()) {
                        throw violation(fieldPath, "a value (it is a primitive)", value);
                    }
                    continue;
                }
                validate(value, field.getGenericType(), fieldPath);
            }
        }
    }

    private static JsonObject schemaOf(Type type, Set<Class<?>> enclosing) {
        Class<?> raw = rawClass(type);
        String scalar = scalarSchema(raw);
        if (scalar != null) {
            return typed(scalar);
        }
        if (raw.isEnum()) {
            JsonObject schema = typed("string");
            JsonArray values = new JsonArray();
            enumNames(raw).forEach(values::add);
            schema.add("enum", values);
            return schema;
        }
        if (isCollection(raw)) {
            return arrayOf(schemaOf(elementType(type), enclosing));
        }
        if (Map.class.isAssignableFrom(raw) || raw.isInterface() || Modifier.isAbstract(raw.getModifiers())
                || raw == Object.class) {
            throw new IllegalArgumentException(raw.getName() + " has no fixed shape to derive a schema from -- "
                    + "use a record or concrete class with named fields");
        }
        if (!enclosing.add(raw)) {
            throw new IllegalArgumentException(raw.getName() + " contains itself; a recursive type has no "
                    + "finite schema here");
        }
        JsonObject properties = new JsonObject();
        JsonArray required = new JsonArray();
        for (Field field : fieldsOf(raw)) {
            JsonObject fieldSchema = schemaOf(field.getGenericType(), enclosing);
            String name = names(field).get(0);
            properties.add(name, field.getType().isPrimitive() ? fieldSchema : nullable(fieldSchema));
            required.add(name);
        }
        enclosing.remove(raw);
        return objectSchema(properties, required);
    }

    /** The fields Gson's default configuration reads: declared, non-static, non-transient, superclasses too. */
    private static List<Field> fieldsOf(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class && c != Record.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (!Modifier.isStatic(modifiers) && !Modifier.isTransient(modifiers) && !field.isSynthetic()) {
                    fields.add(field);
                }
            }
        }
        return fields;
    }

    /** The JSON key Gson writes for {@code field} first, then the alternates it also accepts on read. */
    private static List<String> names(Field field) {
        SerializedName serialized = field.getAnnotation(SerializedName.class);
        if (serialized == null) {
            return List.of(field.getName());
        }
        List<String> names = new ArrayList<>();
        names.add(serialized.value());
        names.addAll(List.of(serialized.alternate()));
        return names;
    }

    private static JsonElement valueOf(JsonObject object, Field field) {
        for (String name : names(field)) {
            if (object.has(name)) {
                return object.get(name);
            }
        }
        return null;
    }

    private static List<String> enumNames(Class<?> enumType) {
        List<String> names = new ArrayList<>();
        for (Field field : enumType.getDeclaredFields()) {
            if (field.isEnumConstant()) {
                SerializedName serialized = field.getAnnotation(SerializedName.class);
                names.add(serialized == null ? field.getName() : serialized.value());
            }
        }
        return names;
    }

    /** The JSON schema type of a type with no fields of its own, or {@code null} for anything else. */
    private static String scalarSchema(Class<?> type) {
        if (type == boolean.class || type == Boolean.class) {
            return "boolean";
        }
        if (type == int.class || type == Integer.class || type == long.class || type == Long.class
                || type == short.class || type == Short.class || type == byte.class || type == Byte.class
                || type == BigInteger.class) {
            return "integer";
        }
        if (type == double.class || type == Double.class || type == float.class || type == Float.class
                || type == BigDecimal.class) {
            return "number";
        }
        if (type == String.class || type == char.class || type == Character.class || type == UUID.class) {
            return "string";
        }
        return null;
    }

    private static void validateScalar(JsonElement element, Class<?> type, String schemaType, String path) {
        boolean valid = switch (schemaType) {
            case "boolean" -> element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean();
            case "integer" -> isNumber(element) && fitsInteger(element.getAsBigDecimal(), type);
            case "number" -> isNumber(element);
            default -> isString(element) && fitsString(element.getAsString(), type);
        };
        if (!valid) {
            throw violation(path, "a " + type.getSimpleName(), element);
        }
    }

    private static boolean fitsInteger(BigDecimal value, Class<?> type) {
        BigInteger integer;
        try {
            integer = value.toBigIntegerExact();
        } catch (ArithmeticException fractional) {
            return false;
        }
        long[] range = type == int.class || type == Integer.class ? new long[] {Integer.MIN_VALUE, Integer.MAX_VALUE}
                : type == short.class || type == Short.class ? new long[] {Short.MIN_VALUE, Short.MAX_VALUE}
                : type == byte.class || type == Byte.class ? new long[] {Byte.MIN_VALUE, Byte.MAX_VALUE}
                : type == BigInteger.class ? null
                : new long[] {Long.MIN_VALUE, Long.MAX_VALUE};
        return range == null || (integer.compareTo(BigInteger.valueOf(range[0])) >= 0
                && integer.compareTo(BigInteger.valueOf(range[1])) <= 0);
    }

    private static boolean fitsString(String value, Class<?> type) {
        if (type == char.class || type == Character.class) {
            return value.length() == 1;
        }
        if (type == UUID.class) {
            try {
                UUID.fromString(value);
            } catch (IllegalArgumentException notAUuid) {
                return false;
            }
        }
        return true;
    }

    private static boolean isNumber(JsonElement element) {
        return element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber();
    }

    private static boolean isString(JsonElement element) {
        return element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
    }

    private static boolean isCollection(Class<?> raw) {
        return raw.isArray() || List.class == raw || Set.class == raw || Collection.class == raw;
    }

    private static Type elementType(Type type) {
        if (type instanceof Class<?> c && c.isArray()) {
            return c.getComponentType();
        }
        if (type instanceof GenericArrayType generic) {
            return generic.getGenericComponentType();
        }
        if (type instanceof ParameterizedType parameterized) {
            return parameterized.getActualTypeArguments()[0];
        }
        throw new IllegalArgumentException("A raw " + type.getTypeName() + " has no element type to derive a "
                + "schema from -- declare it as e.g. List<String>");
    }

    private static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> c) {
            return c;
        }
        if (type instanceof ParameterizedType parameterized) {
            return (Class<?>) parameterized.getRawType();
        }
        if (type instanceof GenericArrayType) {
            return Object[].class;
        }
        throw new IllegalArgumentException(type.getTypeName() + " has no fixed shape to derive a schema from");
    }

    private static JsonObject typed(String type) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", type);
        return schema;
    }

    private static JsonObject arrayOf(JsonObject items) {
        JsonObject schema = typed("array");
        schema.add("items", items);
        return schema;
    }

    private static JsonObject nullable(JsonObject schema) {
        JsonArray anyOf = new JsonArray();
        anyOf.add(schema);
        anyOf.add(typed("null"));
        JsonObject wrapped = new JsonObject();
        wrapped.add("anyOf", anyOf);
        return wrapped;
    }

    /** Every property required and no others allowed -- what OpenAI's strict mode demands of every object. */
    private static JsonObject objectSchema(JsonObject properties, JsonArray required) {
        JsonObject schema = typed("object");
        schema.add("properties", properties);
        schema.add("required", required);
        schema.add("additionalProperties", new JsonPrimitive(false));
        return schema;
    }

    private static CompletionException violation(String path, String expected, JsonElement actual) {
        return new CompletionException("The response's " + path + " should be " + expected + " but is " + actual);
    }
}
