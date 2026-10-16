package com.arthadhruva.riskengine.survival;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Runs a long list of chunks on the shared compute pool a few at a time, handing the results on in the
 * order the chunks were listed.
 *
 * <p>The pool's queue is first in, first out. A run that queued all of its chunks at once would stand in
 * front of every run started after it, which would then finish nothing until the first was done. With only
 * {@link #SIZE} chunks of a run in the queue at any moment, concurrent runs take turns. The order results
 * are handed on in does not depend on the window, so figures summed from them are the same as before.
 *
 * <p>Whatever ends the pass early (a failed chunk, an interrupt, the consumer throwing), the chunks still
 * in flight are cancelled, so an abandoned run stops costing compute.
 */
public final class ChunkWindow {

    /** Enough to keep every compute thread busy when one run has the pool to itself. */
    public static final int SIZE = Math.max(8, 2 * Runtime.getRuntime().availableProcessors());

    private ChunkWindow() {
    }

    /**
     * @param starts one per chunk: starts it and returns its future (or a completed one, for a chunk that
     *               needs no computing)
     * @param sink   receives each result on the calling thread, in the order of {@code starts}
     */
    public static <T> void run(List<Supplier<Future<T>>> starts, Consumer<T> sink) throws InterruptedException, ExecutionException {
        Deque<Future<T>> inFlight = new ArrayDeque<>(SIZE);
        try {
            int next = 0;
            while (next < starts.size() || !inFlight.isEmpty()) {
                while (next < starts.size() && inFlight.size() < SIZE) {
                    inFlight.add(starts.get(next++).get());
                }
                T result = inFlight.peek().get();
                inFlight.remove();
                sink.accept(result);
            }
        } finally {
            inFlight.forEach(future -> future.cancel(true));
        }
    }
}
