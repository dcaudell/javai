package dev.xtrafe.javai.supervision.fixtures;

import dev.xtrafe.javai.annotations.SupervisionPointcut;
import dev.xtrafe.javai.annotations.SyncSupervision;

/** Deliberately requests the unsupported combination -- {@code EXCEPTION} on a constructor -- so {@code
 *  SupervisionWeavingTest} can prove {@code SupervisionWeaver} fails fast at weave time instead of
 *  silently weaving PRE/POST only. See {@code SupervisionConstructorAdvice}'s javadoc for why. */
public class SupervisedConstructorWithExceptionWidget {

    @SyncSupervision(SupervisionPointcut.EXCEPTION)
    public SupervisedConstructorWithExceptionWidget() {
    }
}
