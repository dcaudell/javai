package dev.xtrafe.javai.e2e;

import dev.xtrafe.javai.substrate.JavAIWeaver;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * Installs the weaver before JUnit Platform does anything else -- registered via
 * {@code META-INF/services/org.junit.platform.launcher.LauncherSessionListener}.
 *
 * <p>Installing it from {@code @BeforeAll} instead (the simpler, more obvious place) doesn't work: JUnit's
 * own test discovery reflectively inspects the test class -- including its {@code Article}/{@code Comment}
 * -typed fields -- to build the test plan, and that happens before any {@code @BeforeAll} callback runs.
 * Reflectively touching those fields is enough to load the classes, unwoven, before the transformer is
 * ever installed; once that happens they stay unwoven for the rest of the JVM's life (confirmed by hand --
 * this is exactly what surfaced the bug). A {@code LauncherSessionListener} fires at the very start of the
 * launcher session, before discovery begins, which is early enough.
 */
public class JavAIWeavingLauncherSessionListener implements LauncherSessionListener {

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        JavAIWeaver.install(ByteBuddyAgent.install());
    }
}
