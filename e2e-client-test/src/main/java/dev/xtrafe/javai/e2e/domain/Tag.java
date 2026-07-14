package dev.xtrafe.javai.e2e.domain;

import dev.xtrafe.javai.annotations.JavAIVectorizable;
import dev.xtrafe.javai.annotations.Vectorize;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.UUID;

/**
 * Proves {@code javai-substrate}'s load-time weaver interoperates with Lombok's compile-time code
 * generation -- every accessor, the all-args constructor, {@code equals}/{@code hashCode}/{@code toString}
 * below are Lombok-generated, not hand-written, yet {@code @JavAIVectorizable} still weaves in
 * {@code JavAIVectorizable}/{@code JavAIDirtyTracking} correctly, and the weaver's setter-instrumentation
 * still attaches to Lombok's generated {@code setLabel(String)} exactly as it would a hand-written one. See
 * {@code LombokInteropE2ETest} for the actual proof.
 *
 * <p>The ordering the task asked for ("javai weaves after lombok is completely done") is guaranteed by
 * construction here, not by any explicit configuration: Lombok's annotation processor runs during
 * {@code javac} (Maven's {@code compile}/{@code test-compile} phases), rewriting this class's bytecode on
 * disk before the JVM that runs tests ever starts. {@code JavAIWeaver} only attaches (via
 * {@code ByteBuddyAgent} self-attach, from {@code JavAIWeavingLauncherSessionListener}) once that
 * already-Lombok-processed bytecode is loaded inside the forked test JVM -- there is no code path in this
 * build where the weaver could observe this class before Lombok has finished with it. No annotation
 * processor ordering, no special plugin configuration, and no changes to how Lombok is normally declared
 * (a plain {@code provided}-scope dependency) were needed to make that true.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@EqualsAndHashCode
@JavAIVectorizable
public class Tag {

    private UUID id;

    @Vectorize
    private String label;
}
