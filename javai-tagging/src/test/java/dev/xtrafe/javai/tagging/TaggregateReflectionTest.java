package dev.xtrafe.javai.tagging;

import dev.xtrafe.javai.annotations.Taggregate;
import dev.xtrafe.javai.model.JavAIArrayList;
import jakarta.persistence.Id;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Hermetic pins for the {@code @Taggregate} declaration walk -- no containers, no persistence. */
class TaggregateReflectionTest {

    static class Member implements Taggable {
        @Id
        UUID id = UUID.randomUUID();
    }

    /** The field grammar declared on a shared base class -- the {@code @MappedSuperclass} shape, which is
     *  purely a hierarchy walk in this lineage (nothing woven, so no per-class weaving constraint). */
    abstract static class BaseContainer implements Taggable {
        @Id
        UUID id = UUID.randomUUID();

        @Taggregate
        Taggable cover;
    }

    @Taggregate(concatenate = true)
    static class Container extends BaseContainer {
        @Taggregate
        JavAIArrayList<Taggable> members = new JavAIArrayList<>();
    }

    /** {@code concatenate} on a FIELD placement is meaningless by design (the deliberate asymmetry against
     *  {@code @Summary}) -- it must not leak into the type-level opt-in. */
    static class FieldConcatenateOnly implements Taggable {
        @Id
        UUID id = UUID.randomUUID();

        @Taggregate(concatenate = true)
        Taggable child;
    }

    static class DeclaresNothing implements Taggable {
        @Id
        UUID id = UUID.randomUUID();
    }

    static class NotTaggable {
        @Id
        UUID id = UUID.randomUUID();
    }

    static class HoldsNonTaggable implements Taggable {
        @Id
        UUID id = UUID.randomUUID();

        @Taggregate
        Object member = new NotTaggable();
    }

    @Test
    void memberRefsSpanSingleRefAndCollectionFieldsIncludingInheritedOnes() {
        Container container = new Container();
        Member cover = new Member();
        Member first = new Member();
        Member second = new Member();
        container.cover = cover;
        container.members.add(first);
        container.members.add(second);
        container.members.add(null);
        container.members.add(first);   // duplicate -- one member, one ref

        List<TaggableRef> refs = TaggregateReflection.memberRefsOf(container);
        assertEquals(3, refs.size());
        assertTrue(refs.contains(new TaggableRef(Member.class.getName(), cover.id)));
        assertTrue(refs.contains(new TaggableRef(Member.class.getName(), first.id)));
        assertTrue(refs.contains(new TaggableRef(Member.class.getName(), second.id)));
    }

    @Test
    void nullFieldsContributeNothing() {
        assertEquals(List.of(), TaggregateReflection.memberRefsOf(new Container()));
    }

    @Test
    void aggregateAndConcatenateAreReadFromTheRightPlacements() {
        assertTrue(TaggregateReflection.isAggregate(Container.class));
        assertTrue(TaggregateReflection.concatenates(Container.class));
        assertFalse(TaggregateReflection.isAggregate(DeclaresNothing.class));
        assertFalse(TaggregateReflection.concatenates(DeclaresNothing.class));
    }

    @Test
    void concatenateOnAFieldPlacementDoesNotOptTheTypeIn() {
        assertTrue(TaggregateReflection.isAggregate(FieldConcatenateOnly.class));
        assertFalse(TaggregateReflection.concatenates(FieldConcatenateOnly.class));
    }

    @Test
    void aMemberThatIsNotTaggableIsRefusedLoudly() {
        assertThrows(IllegalArgumentException.class,
                () -> TaggregateReflection.memberRefsOf(new HoldsNonTaggable()));
    }
}
