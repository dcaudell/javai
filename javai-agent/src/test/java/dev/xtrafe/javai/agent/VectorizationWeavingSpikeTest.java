package dev.xtrafe.javai.agent;

import dev.xtrafe.javai.agent.fixtures.VectorizableWidget;
import dev.xtrafe.javai.runtime.EmbeddingVector;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.dynamic.loading.ByteArrayClassLoader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the object lifecycle state machine from doc/spec/vector-core.md's "Object lifecycle state
 * machine" section -- Clean -&gt; FieldDirty -&gt; EmbeddingRecomputing -&gt; Clean -- against a fixture
 * ({@link VectorizableWidget}) that is woven by {@link JavAIWeaver} at load time, not hand-written.
 *
 * <p>EmbeddingRecomputing/SummaryRecomputing are explicitly transient, in-process states per the spec
 * ("no global generation counter... each object's pair of flags is the entire durable state"), so there
 * is nothing durable to assert *during* recomputation. Instead this test proves the transition happened
 * exactly once per dirty cycle via a recompute counter, which is the observable consequence of laziness:
 * a mutation alone must never trigger it, only the next {@code vector()} read does.
 *
 * <p>Each test loads a fresh copy of the fixture's class bytes through an isolated {@link ByteArrayClassLoader}
 * <em>after</em> the transformer is installed, so the weaving under test is genuinely load-time -- the
 * compiled {@code VectorizableWidget.class} on disk has none of {@code isDirty()}/{@code vector()}/
 * {@code recomputeCount()}, they exist only on the copy loaded through this test.
 */
class VectorizationWeavingSpikeTest {

    private static ResettableClassFileTransformer transformer;

    @BeforeAll
    static void installWeaver() {
        Instrumentation instrumentation = ByteBuddyAgent.install();
        transformer = JavAIWeaver.install(instrumentation);
    }

    @AfterAll
    static void uninstallWeaver() {
        transformer.reset(ByteBuddyAgent.getInstrumentation(),
                net.bytebuddy.agent.builder.AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
    }

    @Test
    void mutationMarksDirtyAndRecomputationIsLazy() throws Exception {
        Class<?> widgetClass = loadFreshWidgetClass();
        Object widget = widgetClass.getDeclaredConstructor().newInstance();

        Method setDescription = widgetClass.getMethod("setDescription", String.class);
        Method vector = widgetClass.getMethod("vector");
        Method isDirty = widgetClass.getMethod("isDirty");
        Method recomputeCount = widgetClass.getMethod("recomputeCount");

        setDescription.invoke(widget, "first description");

        // Clean --setter mutates a @Vectorize field--> FieldDirty
        assertTrue((boolean) isDirty.invoke(widget), "mutating setter must mark the object dirty");
        assertEquals(0, (int) recomputeCount.invoke(widget),
                "the mutation itself must never trigger recomputation -- that would be eager, not lazy");

        // FieldDirty --next read of vector()--> EmbeddingRecomputing --embedding computed--> Clean
        EmbeddingVector firstVector = (EmbeddingVector) vector.invoke(widget);
        assertFalse((boolean) isDirty.invoke(widget), "reading vector() must clear FieldDirty");
        assertEquals(1, (int) recomputeCount.invoke(widget), "exactly one recomputation for one dirty cycle");

        // Clean --another read, nothing mutated--> still Clean, no further recomputation
        EmbeddingVector rereadVector = (EmbeddingVector) vector.invoke(widget);
        assertEquals(1, (int) recomputeCount.invoke(widget),
                "a read while Clean must return the cached vector, not recompute");
        assertEquals(firstVector, rereadVector);

        // Clean --setter mutates again--> FieldDirty --next read--> EmbeddingRecomputing --> Clean
        setDescription.invoke(widget, "second, different description");
        assertTrue((boolean) isDirty.invoke(widget));

        EmbeddingVector secondVector = (EmbeddingVector) vector.invoke(widget);
        assertFalse((boolean) isDirty.invoke(widget));
        assertEquals(2, (int) recomputeCount.invoke(widget));
        assertNotEquals(firstVector, secondVector, "a changed field must produce a different embedding");
    }

    private static Class<?> loadFreshWidgetClass() throws IOException, ClassNotFoundException {
        String className = VectorizableWidget.class.getName();
        byte[] classBytes = readClassBytes(VectorizableWidget.class);
        ByteArrayClassLoader loader = new ByteArrayClassLoader(
                VectorizableWidget.class.getClassLoader(), Map.of(className, classBytes));
        return loader.loadClass(className);
    }

    private static byte[] readClassBytes(Class<?> type) throws IOException {
        String resource = type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Could not locate class file resource: " + resource);
            }
            return in.readAllBytes();
        }
    }
}
