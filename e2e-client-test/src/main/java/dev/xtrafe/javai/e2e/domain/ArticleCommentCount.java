package dev.xtrafe.javai.e2e.domain;

import java.util.UUID;

/**
 * One article's comment count -- what a grouped aggregate comes back as (OMI-398).
 *
 * <p>The question this answers is the one the derived-name grammars cannot ask: {@code countByCommentsIn(...)}
 * gives a single total across every article, not one number per article, so a listing wanting a count beside
 * each row had to issue N counts or load every comment to count in memory.
 *
 * <p>An ordinary record, targeted by a plain JPQL constructor expression. Nothing in JavAI maps it.
 */
public record ArticleCommentCount(UUID articleId, long comments) {
}
