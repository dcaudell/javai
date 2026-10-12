package dev.xtrafe.javai.completion;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Against Mistral's real endpoint -- a paid call, so tagged {@code "requires-model"} (excluded by default,
 * see the root {@code pom.xml}) and skipped without {@code MISTRAL_API_KEY}. Run it explicitly:
 * {@code MISTRAL_API_KEY=... mvn -pl javai-completion -am test -Dtest=CortexMistralLiveTest -Djavai.excludedTestGroups= -Dsurefire.failIfNoSpecifiedTests=false}.
 */
@Tag("requires-model")
@EnabledIfEnvironmentVariable(named = "MISTRAL_API_KEY", matches = ".+")
class CortexMistralLiveTest {

    private static final String MODEL = "mistral-small-latest";

    private final Cortex cortex = CortexMistral.builder()
            .apiKey(System.getenv("MISTRAL_API_KEY"))
            .model(MODEL)
            .build();

    @Test
    void completeReturnsARealAnswerFromARealModel() {
        CompletionResult result = cortex.complete(CompletionRequest.builder()
                .prompt("Reply with exactly the single word: acknowledged")
                .maxTokens(20)
                .temperature(0.0)
                .build());

        assertFalse(result.text().isBlank(), "a real model must produce some real text");
        assertEquals("mistral", result.providerId());
        assertEquals(MODEL, result.modelId());
    }

    @Test
    void completeStreamingDeliversTheAnswerAsTokenChunks() {
        List<String> chunks = new ArrayList<>();
        cortex.completeStreaming(
                CompletionRequest.builder().prompt("Count from one to three.").maxTokens(40).build(),
                chunks::add);

        assertFalse(chunks.isEmpty(), "streaming must deliver at least one chunk");
        assertFalse(String.join("", chunks).isBlank());
    }

    record Planet(String name, int moons, String note) {
    }

    /** OMI-68: Mistral honours the strict json_schema envelope -- prose and a typed value side by side. */
    @Test
    void aTypedRequestComesBackAsItsTypeWithItsProse() {
        CompletionResult result = cortex.complete(CompletionRequest.builder()
                .prompt("How many moons does Mars have? Explain briefly, then answer. Put null in note.")
                .responseType(Planet.class)
                .withCompletion()
                .temperature(0.0)
                .build());

        Planet mars = result.as(Planet.class).orElseThrow();
        assertEquals(2, mars.moons(), result.text());
        assertFalse(result.completion().isBlank(), result.text());
    }

    @Test
    void aListAndAnOptionalComeBackAsAskedFor() {
        Set<Planet> giants = cortex.complete(CompletionRequest.builder()
                        .prompt("List the four giant planets of the Solar System with their moon counts.")
                        .responseSetOf(Planet.class)
                        .temperature(0.0)
                        .build())
                .asSet(Planet.class).orElseThrow();
        assertEquals(4, giants.size(), giants.toString());

        CompletionResult none = cortex.complete(CompletionRequest.builder()
                .prompt("Name a planet of the Solar System that is made of cheese. If there is none, answer null.")
                .responseOptional(Planet.class)
                .temperature(0.0)
                .build());
        assertTrue(none.as(Planet.class).isEmpty(), none.text());
    }

    // ---- OMI-639, OMI-641, OMI-643 against the real endpoint

    /** A blue disc on white, drawn here, so the test needs no image from anywhere and the answer is known. */
    private static byte[] blueCircle() throws java.io.IOException {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(256, 256,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = image.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, 256, 256);
        g.setColor(java.awt.Color.BLUE);
        g.fillOval(48, 48, 160, 160);
        g.dispose();
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", png);
        return png.toByteArray();
    }

    record Figure(String color, String shape) {
    }

    @Test
    void aModelThatReadsImagesSeesTheImageSent() throws java.io.IOException {
        Figure figure = cortex.complete(CompletionRequest.builder()
                        .prompt("Describe the single figure in this image: its color and its shape, one word each, "
                                + "lower case.")
                        .image(blueCircle(), "image/png")
                        .responseType(Figure.class)
                        .temperature(0.0)
                        .build())
                .as(Figure.class).orElseThrow();
        assertEquals("blue", figure.color());
        assertTrue(figure.shape().contains("circle") || figure.shape().contains("disc")
                || figure.shape().contains("disk") || figure.shape().contains("oval"), figure.shape());
    }

    @Test
    void usageIsReported() {
        CompletionResult result = cortex.complete(CompletionRequest.builder()
                .prompt("Reply with exactly the single word: acknowledged").maxTokens(20).temperature(0.0).build());
        assertTrue(result.inputTokens() != null && result.inputTokens() > 0, String.valueOf(result.inputTokens()));
        assertTrue(result.outputTokens() != null && result.outputTokens() > 0, String.valueOf(result.outputTokens()));
    }

    record Roll(String dice) {
    }

    @Test
    void aRealModelCallsTheToolAndAnswersWithItsResult() {
        List<String> asked = new ArrayList<>();
        CompletionResult result = cortex.complete(CompletionRequest.builder()
                        .prompt("Roll 1d20 for my initiative using the roll tool, then tell me the number rolled.")
                        .tool(new CompletionTool("roll", "Rolls dice written in NdM+K notation and returns the total.",
                                Roll.class))
                        .maxToolCalls(3)
                        .temperature(0.0)
                        .build(),
                call -> {
                    asked.add(call.arguments(Roll.class).dice());
                    return "17";
                });
        assertEquals(1, result.toolCalls().size(), result.toolCalls().toString());
        assertTrue(asked.get(0).toLowerCase().contains("d20"), asked.toString());
        assertTrue(result.text().contains("17"), result.text());
    }
}
