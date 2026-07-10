package dev.xtrafe.javai.supervision;

import dev.xtrafe.javai.supervision.fixtures.SupervisedConstructorWidget;
import dev.xtrafe.javai.supervision.fixtures.SupervisedConstructorWithExceptionWidget;
import dev.xtrafe.javai.supervision.fixtures.SupervisedWidget;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.dynamic.loading.ByteArrayClassLoader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Proves {@link SupervisionWeaver} + {@link JavAISupervisionRuntime} against the full contract described
 * in doc/spec/agentic-supervision.md: PRE argument rewrite, POST return-value rewrite, PRE veto by
 * throwing, EXCEPTION-to-normal-return conversion, EXCEPTION firing for a propagated (not just literal
 * {@code throw}) exception, sync-before-async ordering, async running off-thread without blocking or
 * mutating the call, {@code supportedClass()} scoping, and constructor PRE argument rewrite. Uses each
 * fixture's own fresh, isolated child-first classloader (same technique as {@code javai-substrate}'s
 * {@code VectorizationWeavingTest}) so weaving is genuinely load-time.
 */
class SupervisionWeavingTest {

    private static ResettableClassFileTransformer transformer;

    private final List<SyncSupervisionListener> registeredSync = new ArrayList<>();
    private final List<AsyncSupervisionListener> registeredAsync = new ArrayList<>();

    @BeforeAll
    static void installWeaver() {
        Instrumentation instrumentation = ByteBuddyAgent.install();
        transformer = SupervisionWeaver.install(instrumentation);
    }

    @AfterAll
    static void uninstallWeaver() {
        transformer.reset(ByteBuddyAgent.getInstrumentation(), AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
    }

    @AfterEach
    void unregisterListeners() {
        registeredSync.forEach(JavAISupervisionRuntime::unregisterSyncListener);
        registeredAsync.forEach(JavAISupervisionRuntime::unregisterAsyncListener);
        registeredSync.clear();
        registeredAsync.clear();
    }

    @Test
    void preRewritesArgumentsAndPostRewritesReturnValue() throws Exception {
        Class<?> widgetClass = loadWidget();
        Object widget = widgetClass.getDeclaredConstructor().newInstance();

        sync(new SyncSupervisionListener() {
            @Override
            public void onPre(SupervisionEvent event) {
                event.setArguments(new Object[] {"rewritten"});
            }
        });
        sync(new SyncSupervisionListener() {
            @Override
            public void onPost(SupervisionEvent event) {
                event.setReturnValue(event.returnValue() + "!");
            }
        });

        String result = (String) widgetClass.getMethod("greet", String.class).invoke(widget, "original");
        assertEquals("Hello, rewritten!", result);
    }

    @Test
    void multipleSyncListenersRunInRegistrationOrderSeeingPriorMutations() throws Exception {
        Class<?> widgetClass = loadWidget();
        Object widget = widgetClass.getDeclaredConstructor().newInstance();

        sync(new SyncSupervisionListener() {
            @Override
            public void onPre(SupervisionEvent event) {
                event.setArguments(new Object[] {event.arguments()[0] + "-A"});
            }
        });
        sync(new SyncSupervisionListener() {
            @Override
            public void onPre(SupervisionEvent event) {
                event.setArguments(new Object[] {event.arguments()[0] + "-B"});
            }
        });

        String result = (String) widgetClass.getMethod("greet", String.class).invoke(widget, "x");
        assertEquals("Hello, x-A-B", result);
    }

    @Test
    void preListenerCanVetoByThrowing() throws Exception {
        Class<?> widgetClass = loadWidget();
        Object widget = widgetClass.getDeclaredConstructor().newInstance();

        sync(new SyncSupervisionListener() {
            @Override
            public void onPre(SupervisionEvent event) {
                throw new IllegalArgumentException("blocked");
            }
        });

        Method greet = widgetClass.getMethod("greet", String.class);
        InvocationTargetException wrapped =
                assertThrows(InvocationTargetException.class, () -> greet.invoke(widget, "x"));
        assertEquals(IllegalArgumentException.class, wrapped.getCause().getClass());
        assertEquals("blocked", wrapped.getCause().getMessage());
    }

    @Test
    void exceptionListenerConvertsThrowIntoNormalReturn() throws Exception {
        Class<?> widgetClass = loadWidget();
        Object widget = widgetClass.getDeclaredConstructor().newInstance();

        sync(new SyncSupervisionListener() {
            @Override
            public void onException(SupervisionEvent event) {
                event.setThrown(null);
                event.setReturnValue("recovered");
            }
        });

        String result = (String) widgetClass.getMethod("mayThrow", boolean.class).invoke(widget, true);
        assertEquals("recovered", result);
    }

    @Test
    void exceptionPointcutFiresForExceptionPropagatedFromAHelperCall() throws Exception {
        Class<?> widgetClass = loadWidget();
        Object widget = widgetClass.getDeclaredConstructor().newInstance();

        AtomicReference<String> observedMessage = new AtomicReference<>();
        sync(new SyncSupervisionListener() {
            @Override
            public void onException(SupervisionEvent event) {
                observedMessage.set(event.thrown().getMessage());
            }
        });

        Method delegatesAndMayThrow = widgetClass.getMethod("delegatesAndMayThrow", boolean.class);
        InvocationTargetException wrapped =
                assertThrows(InvocationTargetException.class, () -> delegatesAndMayThrow.invoke(widget, true));
        assertEquals("boom from helper", wrapped.getCause().getMessage(),
                "the original propagated exception must still surface unless a listener replaces it");
        assertEquals("boom from helper", observedMessage.get(),
                "EXCEPTION must fire even though the throw is textually inside a called helper, not this method");
    }

    @Test
    void asyncListenerRunsOffThreadAndCannotAffectTheReturnValue() throws Exception {
        Class<?> widgetClass = loadWidget();
        Object widget = widgetClass.getDeclaredConstructor().newInstance();

        Thread callingThread = Thread.currentThread();
        AtomicReference<Thread> listenerThread = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        async(new AsyncSupervisionListener() {
            @Override
            public void onPost(SupervisionEvent event) {
                listenerThread.set(Thread.currentThread());
                event.setReturnValue("mutated-by-async-should-be-discarded");
                latch.countDown();
            }
        });

        String result = (String) widgetClass.getMethod("observedOnly", String.class).invoke(widget, "value");
        assertEquals("VALUE", result, "an async-only listener has no rewrite rights over the call's own result");

        assertTrue(latch.await(5, TimeUnit.SECONDS), "async listener must eventually run");
        assertNotEquals(callingThread, listenerThread.get(), "async dispatch must run on a different thread");
    }

    @Test
    void syncTierCommitsBeforeAsyncTierObserves() throws Exception {
        Class<?> widgetClass = loadWidget();
        Object widget = widgetClass.getDeclaredConstructor().newInstance();

        sync(new SyncSupervisionListener() {
            @Override
            public void onPost(SupervisionEvent event) {
                event.setReturnValue(((Integer) event.returnValue()) + 100);
            }
        });
        AtomicReference<Object> asyncObservedValue = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        async(new AsyncSupervisionListener() {
            @Override
            public void onPost(SupervisionEvent event) {
                asyncObservedValue.set(event.returnValue());
                latch.countDown();
            }
        });

        int result = (int) widgetClass.getMethod("mixedTier", int.class).invoke(widget, 5);
        assertEquals(110, result, "sync tier's rewrite (5*2 + 100) must be what the caller actually gets back");

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(110, asyncObservedValue.get(),
                "async tier must observe the sync tier's already-committed value, not the pre-sync one");
    }

    @Test
    void listenerScopedToADifferentClassNeverFires() throws Exception {
        Class<?> widgetClass = loadWidget();
        Object widget = widgetClass.getDeclaredConstructor().newInstance();

        sync(new SyncSupervisionListener() {
            @Override
            public void onPre(SupervisionEvent event) {
                fail("a listener scoped to an unrelated class must never be invoked");
            }

            @Override
            public Class<?> supportedClass() {
                return String.class;
            }
        });

        String result = (String) widgetClass.getMethod("greet", String.class).invoke(widget, "x");
        assertEquals("Hello, x", result);
    }

    @Test
    void unsupervisedMethodIsUnaffectedByWeavingTheSameClass() throws Exception {
        Class<?> widgetClass = loadWidget();
        Object widget = widgetClass.getDeclaredConstructor().newInstance();

        String result = (String) widgetClass.getMethod("unsupervised", String.class).invoke(widget, "x");
        assertEquals("plain:x", result);
    }

    @Test
    void constructorPreListenerRewritesArgumentUsedInBody() throws Exception {
        ClassLoader loader = loadFixtures(SupervisedConstructorWidget.class);
        Class<?> widgetClass = loader.loadClass(SupervisedConstructorWidget.class.getName());

        sync(new SyncSupervisionListener() {
            @Override
            public void onPre(SupervisionEvent event) {
                event.setArguments(new Object[] {"rewritten-label"});
            }
        });

        Constructor<?> constructor = widgetClass.getDeclaredConstructor(String.class);
        Object widget = constructor.newInstance("original-label");
        assertEquals("rewritten-label", widgetClass.getField("label").get(widget));
    }

    @Test
    void exceptionOnAConstructorLeavesThatTypeEntirelyUnwoven() throws Exception {
        ClassLoader loader = loadFixtures(SupervisedConstructorWithExceptionWidget.class);
        Class<?> widgetClass = loader.loadClass(SupervisedConstructorWithExceptionWidget.class.getName());

        sync(new SyncSupervisionListener() {
            @Override
            public void onPre(SupervisionEvent event) {
                fail("SupervisionWeaver must reject EXCEPTION on a constructor at weave time, "
                        + "leaving the whole type unwoven -- this listener must never fire");
            }
        });

        // Must not throw: class loading itself always succeeds (Byte Buddy's AgentBuilder falls back to
        // the original, unwoven bytecode on a transform failure rather than failing the class load).
        Object widget = widgetClass.getDeclaredConstructor().newInstance();
        assertEquals(SupervisedConstructorWithExceptionWidget.class.getName(), widget.getClass().getName());
    }

    private void sync(SyncSupervisionListener listener) {
        registeredSync.add(listener);
        JavAISupervisionRuntime.registerSyncListener(listener);
    }

    private void async(AsyncSupervisionListener listener) {
        registeredAsync.add(listener);
        JavAISupervisionRuntime.registerAsyncListener(listener);
    }

    private static Class<?> loadWidget() throws Exception {
        ClassLoader loader = loadFixtures(SupervisedWidget.class);
        return loader.loadClass(SupervisedWidget.class.getName());
    }

    private static ClassLoader loadFixtures(Class<?>... fixtureClasses) throws IOException {
        Map<String, byte[]> typeDefinitions = new HashMap<>();
        for (Class<?> fixtureClass : fixtureClasses) {
            typeDefinitions.put(fixtureClass.getName(), readClassBytes(fixtureClass));
        }
        return new ByteArrayClassLoader.ChildFirst(SupervisionWeavingTest.class.getClassLoader(),
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
