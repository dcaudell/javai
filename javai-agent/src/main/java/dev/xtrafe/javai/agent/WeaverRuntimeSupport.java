package dev.xtrafe.javai.agent;

import dev.xtrafe.javai.runtime.EmbeddingVector;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Arrays;

/**
 * Backing methods that {@link JavAIWeaver} wires each woven class's synthesized {@code markDirty()},
 * {@code isDirty()}, and {@code vector()} methods to. Kept generic over field name (rather than
 * generated per-class) by reaching the woven fields through reflection -- this is a spike proving the
 * mechanism, not the accelerated Phase 2 path, so simplicity wins over avoiding reflection overhead here.
 *
 * <p>Must stay {@code public} with {@code public static} methods: the woven bytecode calls these directly
 * from whatever package/classloader the target class happens to live in.
 */
public final class WeaverRuntimeSupport {

    static final String DIRTY_FIELD = "$javai$dirty";
    static final String VECTOR_FIELD = "$javai$vector";
    static final String RECOMPUTE_COUNT_FIELD = "$javai$recomputeCount";

    private WeaverRuntimeSupport() {
    }

    public static void markDirty(Object self) {
        setField(self, DIRTY_FIELD, true);
    }

    public static boolean isDirty(Object self) {
        return getField(self, DIRTY_FIELD, boolean.class);
    }

    public static int recomputeCount(Object self) {
        return getField(self, RECOMPUTE_COUNT_FIELD, int.class);
    }

    /**
     * Lazy recompute: only touches the toy embedding and clears the dirty flag when {@code isDirty()}
     * (or no vector has ever been computed). A clean read returns the cached value untouched -- this is
     * the behavior that distinguishes lazy recomputation from eager-on-write.
     */
    public static EmbeddingVector vector(Object self, String vectorizeFieldName) {
        EmbeddingVector cached = getField(self, VECTOR_FIELD, EmbeddingVector.class);
        if (cached == null || isDirty(self)) {
            Object fieldValue = readAnnotatedField(self, vectorizeFieldName);
            EmbeddingVector recomputed = toyEmbed(fieldValue);
            setField(self, VECTOR_FIELD, recomputed);
            setField(self, DIRTY_FIELD, false);
            setField(self, RECOMPUTE_COUNT_FIELD, recomputeCount(self) + 1);
            return recomputed;
        }
        return cached;
    }

    /** Deterministic stand-in for a real embedding model: not part of what this spike is proving. */
    private static EmbeddingVector toyEmbed(Object fieldValue) {
        String text = String.valueOf(fieldValue);
        float[] values = new float[4];
        int hash = text.hashCode();
        for (int i = 0; i < values.length; i++) {
            values[i] = ((hash >>> (i * 8)) & 0xFF) / 255f;
        }
        return new EmbeddingVector(values, "toy-spike-v1", values.length, Instant.now());
    }

    private static Object readAnnotatedField(Object self, String fieldName) {
        try {
            Field field = self.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(self);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Woven class is missing expected field " + fieldName, e);
        }
    }

    private static void setField(Object self, String fieldName, Object value) {
        try {
            Field field = self.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(self, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Weaver did not add expected field " + fieldName
                    + " to " + Arrays.toString(self.getClass().getDeclaredFields()), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T getField(Object self, String fieldName, Class<T> type) {
        try {
            Field field = self.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return (T) field.get(self);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Weaver did not add expected field " + fieldName, e);
        }
    }
}
