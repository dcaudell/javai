package dev.xtrafe.javai.e2e;

import dev.xtrafe.javai.e2e.domain.Article;
import dev.xtrafe.javai.e2e.domain.Comment;
import dev.xtrafe.javai.e2e.domain.Place;
import dev.xtrafe.javai.e2e.environment.JavAIEnvironment;
import dev.xtrafe.javai.e2e.environment.MonolithicContainer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.geo.Point;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DDL verification, end to end: asserts that schema generation actually produces the structures JavAI's
 * design claims, against the real Postgres the other e2e tests run on. Everything above this test asserts
 * <em>behavior</em> (a save round-trips, a finder matches); this one asserts the <em>shape of the database</em>,
 * which is what a DBA, a reporting tool, or a hand-written SQL query would see.
 *
 * <p>The load-bearing claim is that {@link Article}'s two collection fields are <b>real Hibernate
 * associations</b> -- each with its own join table and foreign keys, neither leaking a column onto the
 * owning entity's table, and each in a table of its own rather than sharing the one Hibernate would name by
 * default for two to-manys of the same element type. It also pins the <em>absence</em> of
 * {@code javai_collection_members}: the mapping that used it was refused in OMI-277 and its DDL went with
 * it, so a database built from scratch must not have one.
 */
class SchemaDdlE2ETest {

    @BeforeAll
    static void seedEveryPersistentStructure() {
        JavAIEnvironment.ensureRunning();

        // Force every structure into existence: a natively-mapped collection, a side-table collection,
        // singular @OneToOne associations, and a geo Point on a non-vectorized entity.
        Article article = new Article("ddl-verification", "exercises every persistent structure");
        article.setFeaturedComment(new Comment("ddl", "featured"));
        article.getComments().add(new Comment("ddl", "native collection member"));
        article.getRelatedComments().put("side", new Comment("ddl", "side-table member"));
        JavAIEnvironment.postgresArticleRepository().save(article);
        JavAIEnvironment.postgresPlaceRepository().save(new Place("ddl-place", new Point(-122.6765, 45.5231)));
    }

    @Test
    void entityTablesAndTheirSingularForeignKeysAreGenerated() throws Exception {
        Set<String> tables = tables();
        assertTrue(tables.containsAll(Set.of("article", "comment", "attachment", "place")),
                "each @Entity must get its own table; found: " + tables);

        // Singular @OneToOne associations are ordinary FK columns on the owning table. Snake-cased
        // (featured_comment_id, not featuredcomment_id) since OMI-145 made
        // CamelCaseToUnderscoresNamingStrategy this backend's default physical naming -- a client that
        // needs the old names pins them back via JavAIPersistenceConfig.Builder.physicalNamingStrategy.
        Set<String> articleColumns = columns("article");
        assertTrue(articleColumns.containsAll(Set.of("featured_comment_id", "draft_comment_id", "attachment_id")),
                "singular associations must be FK columns on article; found: " + articleColumns);
        assertTrue(foreignKeys("article").contains("featured_comment_id -> comment"));
    }

    /** The Phase 2 payoff, in DDL: a JavAI collection that is a real Hibernate association. */
    @Test
    void nativelyMappedJavAICollectionGetsARealJoinTableWithForeignKeys() throws Exception {
        assertTrue(tables().contains("article_comment"),
                "an interface-typed JavAI collection with @OneToMany must produce Hibernate's own join table");

        Set<String> joinColumns = columns("article_comment");
        assertEquals(Set.of("article_id", "comments_id"), joinColumns,
                "the join table must hold exactly the two association keys");

        Set<String> keys = foreignKeys("article_comment");
        assertTrue(keys.contains("article_id -> article"), "join table must FK back to the owner; got " + keys);
        assertTrue(keys.contains("comments_id -> comment"), "join table must FK to the target; got " + keys);
    }

    /**
     * The membership side table is not created at all any more (OMI-277).
     *
     * <p>It used to hold the concrete-typed shape's rows while the natively-mapped one used a real join
     * table, and this test pinned that split. The concrete shape was refused, which left the table
     * unclaimed -- and an unclaimed table created in every database on every boot is vestigial, so its DDL
     * went too. Asserted rather than assumed, because "nothing writes to it" and "it does not exist" are
     * different promises and only the second one survives a rebuild from scratch.
     */
    @Test
    void theMembershipSideTableIsNoLongerCreated() throws Exception {
        assertFalse(tables().contains("javai_collection_members"),
                "no supported mapping uses it, so nothing should be creating it either");
    }

    /** Neither collection shape may leak a column onto the owning entity's own table. */
    @Test
    void collectionFieldsNeverBecomeColumnsOnTheOwningTable() throws Exception {
        Set<String> articleColumns = columns("article");
        assertFalse(articleColumns.contains("comments"), "a collection is never a column: " + articleColumns);
        assertFalse(articleColumns.contains("related_comments"), "a collection is never a column: " + articleColumns);
    }

    /** Vectors live in per-model side tables -- never on the developer's own entity table. */
    @Test
    void vectorsLiveInPerModelSideTablesAndNotOnEntityTables() throws Exception {
        Set<String> tables = tables();
        Set<String> fieldVectorTables = tables.stream().filter(t -> t.startsWith("javai_vectors__")).collect(
                java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> summaryVectorTables = tables.stream().filter(t -> t.startsWith("javai_summary_vectors__"))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        assertFalse(fieldVectorTables.isEmpty(), "expected at least one per-model vector table; found: " + tables);
        assertFalse(summaryVectorTables.isEmpty(), "expected at least one per-model summary-vector table");

        // The model id is part of the TABLE NAME -- that is what makes a model swap non-destructive.
        for (String table : fieldVectorTables) {
            assertTrue(table.length() > "javai_vectors__".length(),
                    "a vector table must be model-qualified, not a single shared table: " + table);
            assertEquals(Set.of("owner_type", "owner_id", "field_name", "model_id", "dims", "vector", "computed_at"),
                    columns(table), "vector table shape is part of the contract");
        }
        // No vector column ever appears on the entity's own table.
        assertFalse(columns("article").stream().anyMatch(c -> c.contains("vector")),
                "the developer's own table must never carry a vector column");
    }

    /** Geo Point fields round-trip through their own side table, not a column on the entity. */
    @Test
    void geoPointsLiveInTheirOwnSideTable() throws Exception {
        assertTrue(tables().contains("javai_geo_points"));
        assertEquals(Set.of("owner_type", "owner_id", "field_name", "longitude", "latitude"),
                columns("javai_geo_points"));
        assertFalse(columns("place").contains("location"),
                "a Point field is mapped by JavAI, not as a column on the entity table");
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(MonolithicContainer.postgresUrl(),
                MonolithicContainer.POSTGRES_USERNAME, MonolithicContainer.POSTGRES_PASSWORD);
    }

    private static Set<String> tables() throws Exception {
        Set<String> found = new LinkedHashSet<>();
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'");
                ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                found.add(resultSet.getString(1));
            }
        }
        return found;
    }

    private static Set<String> columns(String table) throws Exception {
        Set<String> found = new LinkedHashSet<>();
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT column_name FROM information_schema.columns "
                                + "WHERE table_schema = 'public' AND table_name = ?")) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    found.add(resultSet.getString(1));
                }
            }
        }
        return found;
    }

    private static Set<String> foreignKeys(String table) throws Exception {
        Set<String> found = new LinkedHashSet<>();
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT kcu.column_name, ccu.table_name FROM information_schema.table_constraints tc "
                                + "JOIN information_schema.key_column_usage kcu "
                                + "  ON tc.constraint_name = kcu.constraint_name "
                                + "JOIN information_schema.constraint_column_usage ccu "
                                + "  ON tc.constraint_name = ccu.constraint_name "
                                + "WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_name = ?")) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    found.add(resultSet.getString(1) + " -> " + resultSet.getString(2));
                }
            }
        }
        return found;
    }

}
