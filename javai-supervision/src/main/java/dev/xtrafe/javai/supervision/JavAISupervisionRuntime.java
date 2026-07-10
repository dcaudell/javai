package dev.xtrafe.javai.supervision;

import dev.xtrafe.javai.annotations.AsyncSupervision;
import dev.xtrafe.javai.annotations.SupervisionPointcut;
import dev.xtrafe.javai.annotations.SyncSupervision;

import java.lang.System.Logger.Level;
import java.lang.reflect.Executable;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

/**
 * The static facade {@code SupervisionWeaver}'s Advice classes call into -- listener registration plus the
 * actual sync/async dispatch logic described in doc/spec/agentic-supervision.md. Mirrors {@code
 * JavAIRuntime}'s role in the {@code javai-substrate}/{@code javai-model} split: the weaver wires the join
 * point, this class does the algorithmic work.
 *
 * <p>Each dispatch method below first checks whether the given pointcut was actually requested (by
 * {@link SyncSupervision#value()}/{@link AsyncSupervision#value()} on the call's own {@link Executable}) --
 * a call woven for, say, only {@code @SyncSupervision(POST)} pays no cost at all for PRE/EXCEPTION beyond
 * this one annotation lookup. Whether anything expensive actually happens beyond that is entirely a
 * function of how many listeners are registered, same separation of concerns as the original AoP lineage
 * had between "this class is woven" and "an advisor is actually registered for it."
 */
public final class JavAISupervisionRuntime {

    private static final System.Logger LOG = System.getLogger(JavAISupervisionRuntime.class.getName());

    private static final List<SyncSupervisionListener> SYNC_LISTENERS = new CopyOnWriteArrayList<>();
    private static final List<AsyncSupervisionListener> ASYNC_LISTENERS = new CopyOnWriteArrayList<>();
    private static final ExecutorService ASYNC_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private JavAISupervisionRuntime() {
    }

    public static void registerSyncListener(SyncSupervisionListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("null listener");
        }
        SYNC_LISTENERS.add(listener);
    }

    public static void unregisterSyncListener(SyncSupervisionListener listener) {
        SYNC_LISTENERS.remove(listener);
    }

    public static void registerAsyncListener(AsyncSupervisionListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("null listener");
        }
        ASYNC_LISTENERS.add(listener);
    }

    public static void unregisterAsyncListener(AsyncSupervisionListener listener) {
        ASYNC_LISTENERS.remove(listener);
    }

    /** Called from woven entry advice. Returns the (possibly sync-rewritten) arguments to actually run with. */
    public static Object[] dispatchPre(Object instance, Executable executable, Object[] arguments) {
        boolean syncRequested = requestsSync(executable, SupervisionPointcut.PRE);
        boolean asyncRequested = requestsAsync(executable, SupervisionPointcut.PRE);
        if (!syncRequested && !asyncRequested) {
            return arguments;
        }
        SupervisionEvent event = new SupervisionEvent(SupervisionPointcut.PRE, instance, executable, arguments, null, null);
        if (syncRequested) {
            runSync(instance, event, SyncSupervisionListener::onPre);
        }
        if (asyncRequested) {
            runAsync(instance, event, AsyncSupervisionListener::onPre);
        }
        return event.arguments();
    }

    /** Called from woven exit advice on a normal return. Returns the (possibly sync-rewritten) return value. */
    public static Object dispatchPost(Object instance, Executable executable, Object returnValue) {
        boolean syncRequested = requestsSync(executable, SupervisionPointcut.POST);
        boolean asyncRequested = requestsAsync(executable, SupervisionPointcut.POST);
        if (!syncRequested && !asyncRequested) {
            return returnValue;
        }
        SupervisionEvent event = new SupervisionEvent(SupervisionPointcut.POST, instance, executable, null, returnValue, null);
        if (syncRequested) {
            runSync(instance, event, SyncSupervisionListener::onPost);
        }
        if (asyncRequested) {
            runAsync(instance, event, AsyncSupervisionListener::onPost);
        }
        return event.returnValue();
    }

    /**
     * Called from woven exit advice on a throw. Returns the event carrying the final outcome -- {@link
     * SupervisionEvent#thrown()} (possibly replaced, possibly {@code null} if a listener converted this
     * into a normal return) and {@link SupervisionEvent#returnValue()} (relevant only when {@code thrown()}
     * is {@code null}).
     */
    public static SupervisionEvent dispatchException(
            Object instance, Executable executable, Throwable thrown, Object returnValueSoFar) {
        SupervisionEvent event =
                new SupervisionEvent(SupervisionPointcut.EXCEPTION, instance, executable, null, returnValueSoFar, thrown);
        boolean syncRequested = requestsSync(executable, SupervisionPointcut.EXCEPTION);
        boolean asyncRequested = requestsAsync(executable, SupervisionPointcut.EXCEPTION);
        if (syncRequested) {
            runSync(instance, event, SyncSupervisionListener::onException);
        }
        if (asyncRequested) {
            runAsync(instance, event, AsyncSupervisionListener::onException);
        }
        return event;
    }

    private static void runSync(Object instance, SupervisionEvent event, BiConsumer<SyncSupervisionListener, SupervisionEvent> dispatch) {
        Class<?> subject = subjectClass(instance, event.executable());
        for (SyncSupervisionListener listener : SYNC_LISTENERS) {
            if (listener.supportedClass().isAssignableFrom(subject)) {
                // No try/catch here, deliberately -- a listener throwing IS the veto/rejection mechanism
                // (see SyncSupervisionListener's javadoc), so it must propagate immediately, aborting any
                // remaining sync listeners and skipping the async tier for this same dispatch.
                dispatch.accept(listener, event);
            }
        }
    }

    private static void runAsync(Object instance, SupervisionEvent event, BiConsumer<AsyncSupervisionListener, SupervisionEvent> dispatch) {
        Class<?> subject = subjectClass(instance, event.executable());
        for (AsyncSupervisionListener listener : ASYNC_LISTENERS) {
            if (listener.supportedClass().isAssignableFrom(subject)) {
                // Each listener gets its own snapshot -- multiple async listeners for the same call can run
                // concurrently on separate virtual threads, and SupervisionEvent's fields aren't
                // synchronized. Sharing one mutable instance across them would be a data race even though
                // the mutations are "discarded": discarded means never observed by the call that triggered
                // them, not safe to interleave across unrelated concurrent listeners.
                SupervisionEvent snapshot = copy(event);
                ASYNC_EXECUTOR.execute(() -> {
                    try {
                        dispatch.accept(listener, snapshot);
                    } catch (Throwable t) {
                        LOG.log(Level.WARNING, "Async supervision listener threw; swallowed.", t);
                    }
                });
            }
        }
    }

    private static SupervisionEvent copy(SupervisionEvent event) {
        Object[] args = event.arguments();
        return new SupervisionEvent(event.pointcut(), event.instance(), event.executable(),
                args == null ? null : args.clone(), event.returnValue(), event.thrown());
    }

    /** Falls back to the executable's declaring class when {@code instance} is null (a static method). */
    private static Class<?> subjectClass(Object instance, Executable executable) {
        return instance != null ? instance.getClass() : executable.getDeclaringClass();
    }

    private static boolean requestsSync(Executable executable, SupervisionPointcut pointcut) {
        SyncSupervision annotation = executable.getAnnotation(SyncSupervision.class);
        return annotation != null && contains(annotation.value(), pointcut);
    }

    private static boolean requestsAsync(Executable executable, SupervisionPointcut pointcut) {
        AsyncSupervision annotation = executable.getAnnotation(AsyncSupervision.class);
        return annotation != null && contains(annotation.value(), pointcut);
    }

    private static boolean contains(SupervisionPointcut[] pointcuts, SupervisionPointcut pointcut) {
        for (SupervisionPointcut candidate : pointcuts) {
            if (candidate == pointcut) {
                return true;
            }
        }
        return false;
    }
}
