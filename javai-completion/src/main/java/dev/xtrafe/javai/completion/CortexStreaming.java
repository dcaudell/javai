package dev.xtrafe.javai.completion;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Bridges a Spring AI {@code Flux<ChatResponse>} (Project Reactor's own reactive-streams type) into the
 * plain-JDK {@link Flow.Subscriber} contract {@link Cortex#completeStreaming} exposes -- so this module's
 * own public API never leaks a Reactor type, and callers only ever depend on {@code java.util.concurrent}.
 * Backpressure is not modeled (Phase 0 scope): {@code request(long)} is a no-op, since Spring AI's own
 * {@code ChatModel.stream()} doesn't expose a pull-based equivalent to throttle against.
 *
 * <p>Blocks the calling thread until the underlying {@code Flux} terminates (completes or errors), even
 * though {@code Flux.subscribe()} itself is non-blocking and delivers on Reactor's own thread(s) -- required
 * because every {@link Cortex} method is documented as sync-looking regardless of provider (see
 * {@link Cortex#complete}), so a caller collecting chunks right after {@code completeStreaming()} returns
 * must see them all, not race the network. Discovered via a real (Testcontainers) test that intermittently
 * saw zero chunks despite a real streamed response.
 */
final class CortexStreaming {

    private CortexStreaming() {
    }

    static <T> void bridge(Flux<T> flux, Flow.Subscriber<String> subscriber, Function<T, String> textOf) {
        AtomicReference<Disposable> disposableRef = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        subscriber.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(long n) {
                // no-op: Spring AI's Flux streams eagerly, nothing to pull on demand
            }

            @Override
            public void cancel() {
                Disposable disposable = disposableRef.get();
                if (disposable != null) {
                    disposable.dispose();
                }
            }
        });
        Disposable disposable = flux.subscribe(
                item -> {
                    String text = textOf.apply(item);
                    if (text != null && !text.isEmpty()) {
                        subscriber.onNext(text);
                    }
                },
                error -> {
                    try {
                        subscriber.onError(error);
                    } finally {
                        done.countDown();
                    }
                },
                () -> {
                    try {
                        subscriber.onComplete();
                    } finally {
                        done.countDown();
                    }
                });
        disposableRef.set(disposable);
        try {
            done.await(5, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException("Interrupted while waiting for streaming completion", e);
        }
    }
}
