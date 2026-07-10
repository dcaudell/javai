# End-to-End Example

Whitepaper: Appendix A. One continuous walkthrough spanning all six extension areas, in a TLS-zero-day
news domain: search by a vectorized input, pull a match into memory, query two different sub-graphs from
it, ground two different completions in those results, mutate the object, and persist the outcome.

```java
public interface ArticleRepository extends JavAIRepository<Article> {
    List<Article> findNearestByVector(EmbeddingVector reference, int limit);
}

ArticleRepository articles = JavAIPI.repository(ArticleRepository.class);

// 1. Vectorize a free-text input, pull the match into memory.
EmbeddingVector queryVector =
    embeddingProvider.embed("supply chain risk in widely used open-source libraries");
List<Article> matches = articles.findNearestByVector(queryVector, 5);
Article zeroDay = matches.get(0);   // "Zero-day disclosed in widely used TLS library"

// 2. Query a sub-graph from the in-memory object: reader comments about the write-up.
JavAIList<Comment> concernedReaders =
    zeroDay.query(zeroDay.bodyVector(), Comment.class);

// 3. Ground a completion in those results.
CompletionResult readerSentiment = completionProvider.complete(
    CompletionRequest.builder()
        .prompt("In two sentences, summarize what readers are most worried about.")
        .context(concernedReaders.toContext())
        .maxTokens(200)
        .build());

// 4. Query a DIFFERENT sub-graph from the SAME object: articles that cite this one.
//    Different reference vector (whole-object, not one field), different type
//    filter (Article, not Comment), explicit depth limit (direct citations only).
JavAIList<Article> followUps =
    zeroDay.query(zeroDay.vector(), Article.class, /* maxDepth */ 1);

// 5. Ground a different completion in those results.
CompletionResult followUpSummary = completionProvider.complete(
    CompletionRequest.builder()
        .prompt("List what has changed in the follow-up coverage since this article was published.")
        .context(followUps.toContext())
        .maxTokens(200)
        .build());

// 6. Update the in-memory object graph -- automatic re-vectorization, not a manual step.
zeroDay.setEditorialNote(readerSentiment.text() + " " + followUpSummary.text());
zeroDay.setTriageStatus(TriageStatus.REVIEWED);
// Ordinary setters -- each runs the same weaver-woven mutation hook. zeroDay.vector()
// and zeroDay.summaryVector() are already marked dirty; they recompute lazily on next read.

// 7. Persist the results -- one repository call closes the loop.
articles.save(zeroDay);
// The Hibernate-based shim re-vectorizes the changed fields on write and updates
// body_vector/body_vector_model in place -- the same call as step 1's read path,
// just the other direction.
```

Nothing above requires a compiler or a GPU — this is the intended Phase 0 experience end to end. Once
`javai-vector`, `javai-model`, `javai-collections`, `javai-persistence`, and `javai-completion` all exist, this is the
natural first integration test: it exercises Vector Core (steps 1, 2, 4, 6), Vector Collections (2, 4),
Persistence Bridge (1, 7), and Completion Fabric (3, 5) in one coherent flow.
