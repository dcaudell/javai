package dev.xtrafe.javai.persistence;

import java.util.List;

/** Repository over {@link TestTeam}, whose to-many association is an ordinary Hibernate-owned
 *  {@code @OneToMany} rather than a JavAI collection. Its nested finders take the same Criteria-JOIN path. */
interface TestTeamRepository extends JavAIRepository<TestTeam> {

    List<TestTeam> findByMembersNickname(String nickname);

    List<TestTeam> findByMembersIsEmpty();
}
