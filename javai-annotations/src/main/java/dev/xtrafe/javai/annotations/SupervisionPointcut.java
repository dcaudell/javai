package dev.xtrafe.javai.annotations;

/**
 * The three moments a supervised method or constructor can be observed or intervened on, shared by
 * {@link SyncSupervision} and {@link AsyncSupervision}. See doc/spec/agentic-supervision.md.
 *
 * <p>Deliberately three, not six: unlike the AoP lineage this is drawn from (see doc/spec, "Prior art"),
 * there is no separate {@code CONSTRUCTOR_*} family -- a constructor is just another {@link
 * java.lang.reflect.Executable}, and which one fired is already recoverable from the event itself, so one
 * enum covers both methods and constructors.
 */
public enum SupervisionPointcut {

    /** Before the body runs. A {@link SyncSupervision} listener may rewrite arguments or veto the call
     *  entirely (by throwing); an {@link AsyncSupervision} listener only observes. */
    PRE,

    /** After a normal return. A {@link SyncSupervision} listener may rewrite the return value; an
     *  {@link AsyncSupervision} listener only observes the final (possibly already sync-rewritten) value. */
    POST,

    /** After the body throws. A {@link SyncSupervision} listener may replace the throwable -- including
     *  with {@code null}, converting the exceptional exit into a normal return -- or leave it alone; an
     *  {@link AsyncSupervision} listener only observes the final (possibly already sync-rewritten) outcome. */
    EXCEPTION
}
