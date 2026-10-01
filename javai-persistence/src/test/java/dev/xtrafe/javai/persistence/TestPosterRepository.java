package dev.xtrafe.javai.persistence;

import java.util.List;

/** Both overlapping {@code @Any} fields queried by target type -- see {@link TestPoster}. */
interface TestPosterRepository extends JavAIRepository<TestPoster> {

    List<TestPoster> findByCoverOfType(Class<?> targetType);

    List<TestPoster> findByAltCoverOfType(Class<?> targetType);

    /** Both at once, so the two rewrites must land on different parts within one method name. */
    List<TestPoster> findByCoverOfTypeAndAltCoverOfType(Class<?> coverType, Class<?> altCoverType);
}
