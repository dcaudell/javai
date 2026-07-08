package dev.xtrafe.javai.agent;

import dev.xtrafe.javai.agent.fixtures.CyclicWidget;
import dev.xtrafe.javai.agent.fixtures.GraphChild;
import dev.xtrafe.javai.agent.fixtures.GraphContainer;
import dev.xtrafe.javai.agent.fixtures.VectorizableWidget;
import dev.xtrafe.javai.runtime.EmbeddingVector;
import dev.xtrafe.javai.runtime.JavAIRuntime;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.dynamic.loading.ByteArrayClassLoader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves {@link JavAIWeaver} against the full contract, superseding the earlier single-field spike: real
 * multi-field {@code vector()}/per-field accessors, and -- the part the spike explicitly didn't cover --
 * {@code summaryVector()} propagating through both a single {@code @Summary} reference and a
 * {@code @Summary} collection, plus cycle safety. Uses {@link FakeEmbeddingProvider}, not Docker; each
 * test loads a fresh copy of its fixtures through an isolated, child-first classloader (after the
 * transformer installs) so the weaving under test is genuinely load-time and cross-references between
 * fixtures resolve to woven versions, not an unwoven copy from an earlier test.
 */
class VectorizationWeavingTest {

    private static ResettableClassFileTransformer transformer;

    @BeforeAll
    static void installWeaverAndFakeProvider() {
        Instrumentation instrumentation = ByteBuddyAgent.install();
        transformer = JavAIWeaver.install(instrumentation);
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
    }

    @AfterAll
    static void uninstallWeaver() {
        transformer.reset(ByteBuddyAgent.getInstrumentation(), AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
    }

    @Test
    void multipleFieldVectorsAreDistinctAndVectorCombinesThem() throws Exception {
        ClassLoader loader = loadFixtures(VectorizableWidget.class);
        Class<?> widgetClass = loader.loadClass(VectorizableWidget.class.getName());
        Object widget = widgetClass.getDeclaredConstructor().newInstance();

        widgetClass.getMethod("setTitle", String.class).invoke(widget, "a short title");
        widgetClass.getMethod("setDescription", String.class).invoke(widget, "a much longer description");

        EmbeddingVector title = (EmbeddingVector) widgetClass.getMethod("titleVector").invoke(widget);
        EmbeddingVector description = (EmbeddingVector) widgetClass.getMethod("descriptionVector").invoke(widget);
        EmbeddingVector combined = (EmbeddingVector) widgetClass.getMethod("vector").invoke(widget);

        assertNotEquals(title, description, "different field text must embed to different vectors");
        assertNotEquals(combined, title, "the combined vector must differ from either field alone");
        assertNotEquals(combined, description);
    }

    @Test
    void mutationMarksDirtyAndRecomputationIsLazy() throws Exception {
        ClassLoader loader = loadFixtures(VectorizableWidget.class);
        Class<?> widgetClass = loader.loadClass(VectorizableWidget.class.getName());
        Object widget = widgetClass.getDeclaredConstructor().newInstance();

        Method setDescription = widgetClass.getMethod("setDescription", String.class);
        Method vector = widgetClass.getMethod("vector");
        Method isFieldDirty = widgetClass.getMethod("isFieldDirty");

        setDescription.invoke(widget, "first description");
        assertTrue((boolean) isFieldDirty.invoke(widget), "mutating a setter must mark FieldDirty");

        EmbeddingVector first = (EmbeddingVector) vector.invoke(widget);
        assertFalse((boolean) isFieldDirty.invoke(widget), "reading vector() must clear FieldDirty");

        EmbeddingVector reread = (EmbeddingVector) vector.invoke(widget);
        assertEquals(first, reread, "a repeat clean read must return the cached vector, not recompute");

        setDescription.invoke(widget, "second, different description");
        assertTrue((boolean) isFieldDirty.invoke(widget));

        EmbeddingVector second = (EmbeddingVector) vector.invoke(widget);
        assertNotEquals(first, second, "a changed field must produce a different vector");
    }

    @Test
    void summaryVectorPropagatesThroughReferenceAndCollection() throws Exception {
        ClassLoader loader = loadFixtures(GraphContainer.class, GraphChild.class);
        Class<?> containerClass = loader.loadClass(GraphContainer.class.getName());
        Class<?> childClass = loader.loadClass(GraphChild.class.getName());

        Object container = containerClass.getDeclaredConstructor().newInstance();
        containerClass.getMethod("setLabel", String.class).invoke(container, "container label");

        Constructor<?> childCtor = childClass.getDeclaredConstructor(String.class);
        Object featured = childCtor.newInstance("original featured text");
        containerClass.getMethod("setFeatured", childClass).invoke(container, featured);

        Object itemA = childCtor.newInstance("item a");
        Object itemB = childCtor.newInstance("item b");
        Object items = containerClass.getMethod("getItems").invoke(container);
        Method add = items.getClass().getMethod("add", Object.class);
        add.invoke(items, itemA);
        add.invoke(items, itemB);

        Method summaryVector = containerClass.getMethod("summaryVector");
        Method isSummaryDirty = containerClass.getMethod("isSummaryDirty");
        Method setText = childClass.getMethod("setText", String.class);

        EmbeddingVector beforeAnyMutation = (EmbeddingVector) summaryVector.invoke(container);
        assertFalse((boolean) isSummaryDirty.invoke(container));

        setText.invoke(featured, "mutated featured text");
        assertTrue((boolean) isSummaryDirty.invoke(container),
                "mutating the single @Summary reference must propagate to the container");
        EmbeddingVector afterReferenceMutation = (EmbeddingVector) summaryVector.invoke(container);
        assertNotEquals(beforeAnyMutation, afterReferenceMutation);

        setText.invoke(itemB, "item b, mutated");
        assertTrue((boolean) isSummaryDirty.invoke(container),
                "mutating an element inside the @Summary collection must also propagate to the container");
        EmbeddingVector afterCollectionMutation = (EmbeddingVector) summaryVector.invoke(container);
        assertNotEquals(afterReferenceMutation, afterCollectionMutation);
    }

    @Test
    void cycleSafetyDoesNotHang() throws Exception {
        ClassLoader loader = loadFixtures(CyclicWidget.class);
        Class<?> cyclicClass = loader.loadClass(CyclicWidget.class.getName());

        Constructor<?> ctor = cyclicClass.getDeclaredConstructor(String.class);
        Object a = ctor.newInstance("a");
        Object b = ctor.newInstance("b");

        Method setNext = cyclicClass.getMethod("setNext", cyclicClass);
        setNext.invoke(a, b);
        setNext.invoke(b, a);

        Method summaryVector = cyclicClass.getMethod("summaryVector");
        Method isSummaryDirty = cyclicClass.getMethod("isSummaryDirty");
        Method setLabel = cyclicClass.getMethod("setLabel", String.class);

        // Clean both first -- see JavAIRuntimeLifecycleTest (javai-runtime) for why: otherwise the
        // never-computed default dirty flag would trip the "already dirty" cycle check and prove nothing.
        summaryVector.invoke(a);
        summaryVector.invoke(b);

        setLabel.invoke(a, "a, mutated");

        // If propagateDirty() didn't stop at an already-dirty node, this call would already have hung.
        assertTrue((boolean) isSummaryDirty.invoke(a), "propagation must walk the cycle back around to a");
        assertTrue((boolean) isSummaryDirty.invoke(b), "propagation must reach b, a's direct dependent");
    }

    private static ClassLoader loadFixtures(Class<?>... fixtureClasses) throws IOException {
        Map<String, byte[]> typeDefinitions = new HashMap<>();
        for (Class<?> fixtureClass : fixtureClasses) {
            typeDefinitions.put(fixtureClass.getName(), readClassBytes(fixtureClass));
        }
        return new ByteArrayClassLoader.ChildFirst(VectorizationWeavingTest.class.getClassLoader(),
                typeDefinitions, ByteArrayClassLoader.PersistenceHandler.MANIFEST);
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
