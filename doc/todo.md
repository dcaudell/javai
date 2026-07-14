- (Done) Update README.md files to reflect the current state of the project. 
- (Done) Add test-data fixtures to the e2e project to create realistic data volume. 
- (Done) Add both unit and e2e tests for all collection types declared in javai-vector
- (Done) Make sure in e2e that objects can fully utilize both JPA and Neo4j, and can simultaneously be persisted in both.
- (Done) — whitepaper §5.7 "Agentic Supervision", javai-supervision module scaffolded, doc/spec/agentic-supervision.md) 
- (Done)Although I have seen properly named Docker images be created, when you start fresh with the e2e image you are still producing images that have <none> for Name and <none> for Tag and appear not to correct this until subsequent runs. Could you work on ensuring your images are always correctly named from the get-go?
- (Done) Executive oversight via AOP cutpoints. Weaver + dispatch runtime (JavAISupervisionRuntime/SupervisionWeaver) still needs real implementation.

- (Done) Cortex should report its own context window limits
- (Done) CompletionRequest should use Cortex to determine the context window limits
- (Done) Cortex should be able to handle multiple concurrent requests
- (Done) Cortex should respond and respect "too many requests" errors and resend at specified intervals and also implement exponential backoff.
- (Done) PromptContext should support a "target percentage" parameter of the context window size. Behavior should be that when the PropmptContext renders, it inspects its contained Contextable objects; if the object is an instanceof PromptContext, it gathers it's target percentage. The sum of target percentages is then used against the desired percentage and the context window limit to set a max-length value on the PromptContext.
- (Done) Embeddings providers need to have the same rate limit protections as Cortex.
- (Done) Double check that a method or constructor can have both synchronous and asynchronous advice simultaneously.
- (Done) Should be only one SupervisionListener interface; not different contract for synchronous and asynchronous advice.
- (Done) See if we can use reflection at runtime to fill in the instance variables in pointcut events. 
- (Done) Export transcripts and rank ability
- (Done) Spring Data MongoDB support
- (Deferred) MongoDB Extension for Hibernate ORM support (https://www.mongodb.com/docs/languages/java/mongodb-hibernate/current/)


- Add javai-tagging module to the whitepaper. (Taggable interface, tags are taggable - recursive tagging. Tags are mebmers of tagsets. Objects can be inspected for which tags of a tagset should be applied to the object. Tags have an English slug and display name. Tags are linked to objects by a tag-instance table that applies a tag to an entity. Fitler the instance table by target type, then tag set, then tag to get at all objects of a certain type with a certain tag. ... still more thought required )
- Add recursive MCP microservice fabric to the whitepaper.
