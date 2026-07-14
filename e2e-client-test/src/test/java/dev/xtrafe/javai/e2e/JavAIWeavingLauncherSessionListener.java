package dev.xtrafe.javai.e2e;

import dev.xtrafe.javai.substrate.JavAIWeaver;
import dev.xtrafe.javai.supervision.SupervisionWeaver;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

import java.lang.instrument.Instrumentation;

/**
 * Installs both weavers before JUnit Platform does anything else -- registered via
 * {@code META-INF/services/org.junit.platform.launcher.LauncherSessionListener}.
 *
 * <p>Installing them from {@code @BeforeAll} instead (the simpler, more obvious place) doesn't work: JUnit's
 * own test discovery reflectively inspects each test class -- including its {@code Article}/{@code Comment}
 * -typed fields, and now the {@code AgenticSupervisionE2ETest} fixtures under
 * {@code dev.xtrafe.javai.e2e.supervision} -- to build the test plan, and that happens before any
 * {@code @BeforeAll} callback runs. Reflectively touching those fields is enough to load the classes,
 * unwoven, before the transformer is ever installed; once that happens they stay unwoven for the rest of
 * the JVM's life (confirmed by hand -- this is exactly what surfaced the bug, originally for
 * {@code javai-substrate}). A {@code LauncherSessionListener} fires at the very start of the launcher
 * session, before discovery begins, which is early enough. Both installs share the one
 * {@code Instrumentation} instance ({@code ByteBuddyAgent.install()} is idempotent -- a second call just
 * returns the already-attached instance) since they're independent weavers per doc/spec/agentic-supervision.md.
 */
public class JavAIWeavingLauncherSessionListener implements LauncherSessionListener {

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        Instrumentation instrumentation = ByteBuddyAgent.install();
        JavAIWeaver.install(instrumentation);
        SupervisionWeaver.install(instrumentation);
    }
}
