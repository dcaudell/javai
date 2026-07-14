package dev.xtrafe.javai.substrate;

import dev.xtrafe.javai.annotations.JavAIVectorizable;
import net.bytebuddy.build.Plugin;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;

/**
 * Build-time counterpart to {@link JavAIWeaver#install}: a ByteBuddy {@link Plugin}, driven by
 * {@code byte-buddy-maven-plugin}'s {@code transform} goal during a module's own {@code process-classes}
 * phase, rather than a load-time {@code -javaagent}. Exists specifically so a module that ships its own
 * pre-built {@code @JavAIVectorizable} classes (first case: {@code javai-tagging}'s {@code Tag}/
 * {@code TagSet} -- see doc/spec/tagging.md's "A new structural situation: this module weaves itself") can
 * publish them already woven, so a consuming application gets a working {@code Tag.vector()} without
 * needing to run a {@code -javaagent} (or set up its own build-time weaving) purely to make the library's
 * own shipped types work.
 *
 * <p>Delegates to {@link JavAIWeaver#weave}, the exact same transform load-time weaving uses -- that method
 * only ever takes a {@code DynamicType.Builder}/{@code TypeDescription} pair, with no reference to a live
 * {@code Instrumentation} instance or classloader, so it is genuinely identical bytecode wiring regardless
 * of which SPI drives it. Consuming this module's own compiled classes' bytecode is the "input" here; there
 * is no other divergence from the load-time path to maintain.
 *
 * <p>Instantiated by ByteBuddy's plugin engine via reflection (a plain public no-arg constructor -- the
 * simplest, and default, instantiation shape {@code Plugin.Factory.UsingReflection} supports), configured
 * in a consuming module's {@code pom.xml} as:
 * <pre>{@code
 * <plugin>
 *   <groupId>net.bytebuddy</groupId>
 *   <artifactId>byte-buddy-maven-plugin</artifactId>
 *   <configuration>
 *     <transformations>
 *       <transformation>
 *         <plugin>dev.xtrafe.javai.substrate.JavAIBuildTimeWeaverPlugin</plugin>
 *       </transformation>
 *     </transformations>
 *   </configuration>
 *   <executions>
 *     <execution>
 *       <goals><goal>transform</goal></goals>
 *     </execution>
 *   </executions>
 *   <dependencies>
 *     <!-- javai-substrate itself (for this class) and an explicit byte-buddy core override --
 *          see javai-tagging/pom.xml's own comment for why the override is required. -->
 *   </dependencies>
 * </plugin>
 * }</pre>
 */
public final class JavAIBuildTimeWeaverPlugin implements Plugin {

    @Override
    public boolean matches(TypeDescription target) {
        return target.getDeclaredAnnotations().isAnnotationPresent(JavAIVectorizable.class);
    }

    @Override
    public DynamicType.Builder<?> apply(
            DynamicType.Builder<?> builder, TypeDescription typeDescription, ClassFileLocator classFileLocator) {
        return JavAIWeaver.weave(builder, typeDescription);
    }

    @Override
    public void close() {
        // No resources held -- weave() is a pure function of (Builder, TypeDescription).
    }
}
