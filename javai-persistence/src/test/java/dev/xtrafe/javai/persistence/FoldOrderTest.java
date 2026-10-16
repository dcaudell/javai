package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.vector.Ranked;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The in-memory fold's order (OMI-658): ties by id only when asked, and then in Postgres's uuid order. */
class FoldOrderTest {

    private static final UUID LOW = UUID.fromString("0eec010b-e690-454a-b81a-ad88495c2d1c");
    private static final UUID HIGH = UUID.fromString("8051dfa4-edc0-4be3-ab07-c0740c4185b7");

    private static Ranked<Object> hit(UUID id, double similarity) {
        TestAsset asset = new TestAsset("fold " + id, TestAsset.Kind.IMAGE, true, 1, null);
        try {
            java.lang.reflect.Field field = TestAsset.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(asset, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return new Ranked<>(asset, similarity);
    }

    @Test
    @DisplayName("⚠️ ties by id are in Postgres's unsigned order, where 8051… sorts after 0eec…, unlike UUID.compareTo")
    void tiesInPostgresOrder() {
        List<Ranked<Object>> hits = new ArrayList<>(List.of(hit(HIGH, 0.5), hit(LOW, 0.5), hit(HIGH, 0.9)));

        hits.sort(RepositoryBackendHibernatePostgres.foldOrder(true));

        assertEquals(List.of(0.9, 0.5, 0.5), hits.stream().map(Ranked::similarity).toList());
        assertEquals(LOW, ((TestAsset) hits.get(1).entity()).getId(), "the signed compare would put 8051… first");
    }

    @Test
    @DisplayName("without the option, equal similarities keep the order they arrived in, as they always did")
    void noTieBreakByDefault() {
        List<Ranked<Object>> hits = new ArrayList<>(List.of(hit(HIGH, 0.5), hit(LOW, 0.5)));

        hits.sort(RepositoryBackendHibernatePostgres.foldOrder(false));

        assertEquals(HIGH, ((TestAsset) hits.get(0).entity()).getId());
    }
}
