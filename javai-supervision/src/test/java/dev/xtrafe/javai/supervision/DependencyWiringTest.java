package dev.xtrafe.javai.supervision;

import dev.xtrafe.javai.annotations.SupervisionPointcut;
import net.bytebuddy.ByteBuddy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Proves javai-supervision's dependencies (javai-annotations, ByteBuddy) resolve and compile, and that
 * this module's own public contract (SupervisionEvent, SupervisionListener) is usable. No real
 * weaving/dispatch logic exists yet -- see package-info for what's still needed.
 */
class DependencyWiringTest {

    @Test
    void dependenciesAreOnTheClasspath() {
        assertNotNull(new ByteBuddy());
        assertNotNull(SupervisionPointcut.PRE);
    }

    @Test
    void supervisionEventCarriesAndAcceptsMutation() throws NoSuchMethodException {
        SupervisionEvent event = new SupervisionEvent(
                SupervisionPointcut.PRE,
                this,
                DependencyWiringTest.class.getDeclaredMethod("supervisionEventCarriesAndAcceptsMutation"),
                new Object[] {"original"},
                null,
                null);

        assertNotNull(event.arguments());
        event.setArguments(new Object[] {"rewritten"});
        assertNotNull(event.arguments());
    }

    @Test
    void listenerDefaultsAreNoOpsAndDoNotThrow() {
        SupervisionListener listener = new SupervisionListener() {
        };
        assertNotNull(listener.supportedClass());
    }
}
