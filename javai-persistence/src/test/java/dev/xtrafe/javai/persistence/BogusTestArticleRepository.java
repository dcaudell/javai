package dev.xtrafe.javai.persistence;

import java.util.List;

/** Deliberately unsupported: {@code findByTitle} isn't the {@code findNearestBy<Field>Vector} convention. */
interface BogusTestArticleRepository extends JavAIRepository<TestArticle> {

    List<TestArticle> findByTitle(String title);
}
