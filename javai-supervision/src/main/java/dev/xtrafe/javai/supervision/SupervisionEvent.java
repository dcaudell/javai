package dev.xtrafe.javai.supervision;

import dev.xtrafe.javai.annotations.SupervisionPointcut;

import java.lang.reflect.Executable;

/**
 * The "object bucket" delivered to every {@link SupervisionListener} at a supervised
 * {@link SupervisionPointcut}: everything a listener needs to decide what happened and, if registered
 * synchronously, what to change. Deliberately data-only -- how far a listener reaches from
 * {@link #instance()} or {@link #arguments()} (a plain field read, a full {@code query()} walk of the
 * object graph, an entirely separate system) is the listener's decision, not this module's. See
 * doc/spec/agentic-supervision.md.
 *
 * <p>{@code arguments}/{@code returnValue}/{@code thrown} are mutable <em>on this event instance</em> --
 * a synchronously-dispatched {@link SupervisionListener} rewrites them by calling the corresponding setter,
 * and the weaver reads them back out after every sync listener scoped to this call has run. An
 * asynchronously-dispatched listener receives the same event but its dispatch discards whatever it sets:
 * see {@link SupervisionListener}'s own javadoc for the full sync-vs-async registration distinction.
 *
 * @param pointcut     which of the three moments this is
 * @param instance     the receiver ({@code null} for a static method or before a constructor has run)
 * @param executable   the {@link java.lang.reflect.Method} or {@link java.lang.reflect.Constructor} that fired
 */
public final class SupervisionEvent {

    private final SupervisionPointcut pointcut;
    private final Object instance;
    private final Executable executable;
    private Object[] arguments;
    private Object returnValue;
    private Throwable thrown;

    public SupervisionEvent(SupervisionPointcut pointcut, Object instance, Executable executable,
            Object[] arguments, Object returnValue, Throwable thrown) {
        this.pointcut = pointcut;
        this.instance = instance;
        this.executable = executable;
        this.arguments = arguments;
        this.returnValue = returnValue;
        this.thrown = thrown;
    }

    public SupervisionPointcut pointcut() {
        return pointcut;
    }

    public Object instance() {
        return instance;
    }

    public Executable executable() {
        return executable;
    }

    /** Valid at {@link SupervisionPointcut#PRE}; {@code null} at POST/EXCEPTION. */
    public Object[] arguments() {
        return arguments;
    }

    /** Rewrites the arguments a PRE listener observed. No-op effect outside PRE dispatch. */
    public void setArguments(Object[] arguments) {
        this.arguments = arguments;
    }

    /** Valid at {@link SupervisionPointcut#POST}; {@code null} otherwise. */
    public Object returnValue() {
        return returnValue;
    }

    /** Rewrites the return value a POST listener observed. No-op effect outside POST dispatch. */
    public void setReturnValue(Object returnValue) {
        this.returnValue = returnValue;
    }

    /** Valid at {@link SupervisionPointcut#EXCEPTION}; {@code null} otherwise. */
    public Throwable thrown() {
        return thrown;
    }

    /**
     * Rewrites the throwable an EXCEPTION listener observed. Setting this to {@code null} converts the
     * exceptional exit into a normal return of whatever {@link #setReturnValue} was also called with (or
     * {@code null} if it wasn't) -- deliberately more capable than this project's AoP lineage, which could
     * only replace which throwable propagated, never suppress one. No-op effect outside EXCEPTION dispatch.
     */
    public void setThrown(Throwable thrown) {
        this.thrown = thrown;
    }
}
