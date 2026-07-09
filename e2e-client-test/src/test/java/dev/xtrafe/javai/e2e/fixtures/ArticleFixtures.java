package dev.xtrafe.javai.e2e.fixtures;

import dev.xtrafe.javai.e2e.domain.Article;

import java.util.List;

/**
 * A realistic-volume, topically-clustered set of {@link Article} seeds shared by the e2e suite's bulk
 * persistence and semantic-similarity tests. Four distinct topics, four articles each -- enough real,
 * distinctly-worded text to validate ranking quality in aggregate (see
 * {@code ArticleFixtureVolumeE2ETest}'s own tests), rather than only ever comparing an article to itself (as
 * the rest of the suite's two/three-article sets do, which prove the mechanism works but not that ranking
 * quality holds at volume).
 */
public final class ArticleFixtures {

    private ArticleFixtures() {
    }

    public enum Topic {
        CYBERSECURITY, COOKING, SPORTS, SPACE
    }

    public record Seed(Topic topic, String title, String body) {
    }

    private static final List<Seed> SEEDS = List.of(
            new Seed(Topic.CYBERSECURITY, "Critical bug found in popular password manager",
                    "A security audit uncovered a critical flaw in a widely used password manager that "
                            + "could allow attackers to extract stored credentials in plain text."),
            new Seed(Topic.CYBERSECURITY, "Ransomware gang claims hospital network breach",
                    "A ransomware group claimed responsibility for encrypting patient record systems at a "
                            + "regional hospital network over the weekend."),
            new Seed(Topic.CYBERSECURITY, "Critical flaw found in widely deployed VPN appliance",
                    "Security researchers published details of a remote code execution flaw affecting a "
                            + "popular enterprise VPN appliance."),
            new Seed(Topic.CYBERSECURITY, "Phishing campaign impersonates major cloud provider",
                    "A large-scale phishing campaign is impersonating a major cloud provider's login page "
                            + "to harvest employee credentials."),

            new Seed(Topic.COOKING, "Five quick sheet-pan dinners for busy weeknights",
                    "These one-pan meals come together in under forty minutes with minimal cleanup "
                            + "afterward, using whatever vegetables are already in the fridge."),
            new Seed(Topic.COOKING, "Five essential knife skills for home cooks",
                    "Learning to properly dice, julienne, and mince will speed up almost every recipe in "
                            + "your kitchen."),
            new Seed(Topic.COOKING, "How to make a perfect roast chicken",
                    "A simple technique for a crispy-skinned, juicy roast chicken using just salt, pepper, "
                            + "and a hot oven."),
            new Seed(Topic.COOKING, "Best budget-friendly meal prep ideas",
                    "These make-ahead lunches and dinners are cheap, filling, and easy to prepare in bulk "
                            + "on a Sunday afternoon."),

            new Seed(Topic.SPORTS, "Rookie quarterback leads team to first playoff win in a decade",
                    "The first-year quarterback threw for three touchdowns as his team claimed its first "
                            + "playoff victory in ten years."),
            new Seed(Topic.SPORTS, "Star pitcher signs record-breaking contract",
                    "The all-star pitcher agreed to a record contract extension that keeps him with the "
                            + "team for the next six seasons."),
            new Seed(Topic.SPORTS, "Underdog upsets top seed in tournament opener",
                    "A lower-ranked team pulled off a stunning upset over the tournament's top seed in the "
                            + "opening round."),
            new Seed(Topic.SPORTS, "Veteran coach announces retirement after decades on the sideline",
                    "The longtime head coach announced his retirement, capping a career that spanned more "
                            + "than thirty years on the sideline."),

            new Seed(Topic.SPACE, "New telescope images reveal distant spiral galaxy",
                    "Astronomers released striking new images of a spiral galaxy located hundreds of "
                            + "millions of light years away."),
            new Seed(Topic.SPACE, "Private rocket completes first orbital test flight",
                    "A privately built rocket successfully completed its first orbital test flight, "
                            + "reaching low Earth orbit before returning safely."),
            new Seed(Topic.SPACE, "Mission finds evidence of subsurface ice on distant moon",
                    "New data suggests a large reservoir of subsurface ice exists beneath the icy crust of "
                            + "a moon orbiting a gas giant."),
            new Seed(Topic.SPACE, "Space agency announces crewed mission to the outer planets",
                    "The agency unveiled plans for a crewed mission that would travel farther into the "
                            + "solar system than any previous flight."));

    public static List<Seed> seeds() {
        return SEEDS;
    }

    /** A fresh {@link Article} per seed, in the same order as {@link #seeds()} -- never shared/mutated
     *  across test methods, since each JavAI object caches its own computed vectors once read. */
    public static List<Article> newArticles() {
        return SEEDS.stream().map(seed -> new Article(seed.title(), seed.body())).toList();
    }

    public static Topic topicOf(String title) {
        return SEEDS.stream()
                .filter(seed -> seed.title().equals(title))
                .findFirst()
                .map(Seed::topic)
                .orElseThrow(() -> new IllegalArgumentException("Not a fixture title: " + title));
    }
}
