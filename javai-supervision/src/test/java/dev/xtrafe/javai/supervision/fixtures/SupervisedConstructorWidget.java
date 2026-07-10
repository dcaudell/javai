package dev.xtrafe.javai.supervision.fixtures;

import dev.xtrafe.javai.annotations.SupervisionPointcut;
import dev.xtrafe.javai.annotations.SyncSupervision;

/** Woven fixture proving constructor PRE can rewrite an argument still used later in the constructor
 *  body (after Byte Buddy's post-{@code super()} injection point), per {@code SupervisionWeavingTest}. */
public class SupervisedConstructorWidget {

    public final String label;

    @SyncSupervision(SupervisionPointcut.PRE)
    public SupervisedConstructorWidget(String label) {
        this.label = label;
    }
}
