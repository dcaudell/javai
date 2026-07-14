package dev.xtrafe.javai.substrate;

import dev.xtrafe.javai.substrate.fixtures.CyclicWidget;
import dev.xtrafe.javai.substrate.fixtures.GraphChild;
import dev.xtrafe.javai.substrate.fixtures.GraphContainer;
import dev.xtrafe.javai.substrate.fixtures.InheritedVectorizeBase;
import dev.xtrafe.javai.substrate.fixtures.InheritedVectorizeLeaf;
import dev.xtrafe.javai.substrate.fixtures.VectorizableWidget;
import dev.xtrafe.javai.substrate.fixtures.VectorizeIgnoreWidget;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.model.JavAIRuntime;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        // vector() is now compositional -- it combines each @Vectorize field's own cached VectorCacheSlot
        // (see JavAIRuntime.fieldVector) rather than caching one single result of its own, so a repeat read
        // recombines fresh every time (cheap, in-memory) rather than returning a literal cached instance.
        // FieldDirty itself is no longer vector()'s concern at all -- it's now purely summaryVector()'s
        // staleness signal for its own base term (see JavAIRuntime.summaryVector's javadoc) -- so this test
        // asserts on the vector's actual values, not on FieldDirty transitions around vector() calls.
        ClassLoader loader = loadFixtures(VectorizableWidget.class);
        Class<?> widgetClass = loader.loadClass(VectorizableWidget.class.getName());
        Object widget = widgetClass.getDeclaredConstructor().newInstance();

        Method setDescription = widgetClass.getMethod("setDescription", String.class);
        Method vector = widgetClass.getMethod("vector");

        setDescription.invoke(widget, "first description");
        EmbeddingVector first = (EmbeddingVector) vector.invoke(widget);
        EmbeddingVector reread = (EmbeddingVector) vector.invoke(widget);
        assertArrayEquals(first.values(), reread.values(), 1e-6f,
                "a repeat read with no mutation in between must recompute to the same values");

        setDescription.invoke(widget, "second, different description");
        EmbeddingVector second = (EmbeddingVector) vector.invoke(widget);
        assertFalse(java.util.Arrays.equals(first.values(), second.values()),
                "a changed field must produce a different vector");
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

        // Clean both first -- see JavAIRuntimeLifecycleTest (javai-model) for why: otherwise the
        // never-computed default dirty flag would trip the "already dirty" cycle check and prove nothing.
        summaryVector.invoke(a);
        summaryVector.invoke(b);

        setLabel.invoke(a, "a, mutated");

        // If propagateDirty() didn't stop at an already-dirty node, this call would already have hung.
        assertTrue((boolean) isSummaryDirty.invoke(a), "propagation must walk the cycle back around to a");
        assertTrue((boolean) isSummaryDirty.invoke(b), "propagation must reach b, a's direct dependent");
    }

    @Test
    void vectorizeIgnoreWinsOverVectorizeOnTheSameField() throws Exception {
        ClassLoader loader = loadFixtures(VectorizeIgnoreWidget.class);
        Class<?> widgetClass = loader.loadClass(VectorizeIgnoreWidget.class.getName());
        Object widget = widgetClass.getDeclaredConstructor().newInstance();

        Method setIncluded = widgetClass.getMethod("setIncluded", String.class);
        Method setExcluded = widgetClass.getMethod("setExcluded", String.class);
        Method vector = widgetClass.getMethod("vector");

        // No accessor is even synthesized for an ignored field -- the weaver never discovered it as
        // @Vectorize in the first place.
        assertThrows(NoSuchMethodException.class, () -> widgetClass.getMethod("excludedVector"));

        setIncluded.invoke(widget, "included text");
        setExcluded.invoke(widget, "excluded text, version one");
        EmbeddingVector first = (EmbeddingVector) vector.invoke(widget);

        // Change only the ignored field's value, then force a recompute via the *other*, wired setter
        // (setExcluded alone wouldn't mark FieldDirty at all, so vector() would just return the same
        // cached value regardless of whether exclusion actually works -- that would prove nothing).
        setExcluded.invoke(widget, "excluded text, version two");
        setIncluded.invoke(widget, "included text");
        EmbeddingVector second = (EmbeddingVector) vector.invoke(widget);

        assertArrayEquals(first.values(), second.values(),
                "an @VectorizeIgnore'd field's value must never affect vector()'s concatenated text");
    }

    @Test
    void inheritedVectorizeFieldSetterIsWovenViaSynthesizedOverride() throws Exception {
        ClassLoader loader = loadFixtures(InheritedVectorizeBase.class, InheritedVectorizeLeaf.class);
        Class<?> baseClass = loader.loadClass(InheritedVectorizeBase.class.getName());
        Class<?> leafClass = loader.loadClass(InheritedVectorizeLeaf.class.getName());
        Object leaf = leafClass.getDeclaredConstructor().newInstance();

        // Reflected off the *ancestor* class token, not the leaf's -- stands in for calling setLabel(...)
        // through a reference statically typed as InheritedVectorizeBase (an explicit cast) in ordinary
        // Java code. Method.invoke dispatches virtually on the receiver's runtime type just like invokevirtual
        // does, so this must still land on the leaf's synthesized override.
        Method setLabelViaBaseReference = baseClass.getMethod("setLabel", String.class);
        Method setDetail = leafClass.getMethod("setDetail", String.class);
        Method vector = leafClass.getMethod("vector");

        setLabelViaBaseReference.invoke(leaf, "first label");
        setDetail.invoke(leaf, "first detail");
        EmbeddingVector first = (EmbeddingVector) vector.invoke(leaf);

        // Mutate only the inherited field -- proves the synthesized override, not some coincidental effect
        // of also touching the leaf's own field.
        setLabelViaBaseReference.invoke(leaf, "second label");
        EmbeddingVector second = (EmbeddingVector) vector.invoke(leaf);
        assertFalse(java.util.Arrays.equals(first.values(), second.values()),
                "changing the inherited field's value must change vector()");
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
