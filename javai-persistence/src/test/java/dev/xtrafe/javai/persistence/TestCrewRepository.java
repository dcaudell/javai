package dev.xtrafe.javai.persistence;

import java.util.List;

/** Nested finders over an interface-typed JavAI collection that Hibernate owns natively -- resolved with a
 *  Criteria JOIN (OMI-142 Phase 3), not the id-set-per-hop path the side table needs. */
interface TestCrewRepository extends JavAIRepository<TestCrew> {

    List<TestCrew> findByMembersNickname(String nickname);

    List<TestCrew> findByMembersIsEmpty();

    List<TestCrew> findByMembersIsNotEmpty();

    long countByMembersNickname(String nickname);
}
