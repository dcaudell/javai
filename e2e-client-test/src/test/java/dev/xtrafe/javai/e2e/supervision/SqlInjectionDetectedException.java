package dev.xtrafe.javai.e2e.supervision;

/** Thrown by {@link SqlInjectionGuardSupervisor#onPre} to veto a call -- a dedicated type so the test can
 *  assert on exactly this rejection, not any other {@code RuntimeException} a real query path might throw. */
public class SqlInjectionDetectedException extends RuntimeException {

    public SqlInjectionDetectedException(String message) {
        super(message);
    }
}
