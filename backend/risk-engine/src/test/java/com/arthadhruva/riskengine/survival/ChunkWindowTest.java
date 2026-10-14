package com.arthadhruva.riskengine.survival;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkWindowTest {

    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);

    @AfterAll
    static void shutdown() {
        POOL.shutdownNow();
    }

    @Test
    void resultsArriveInTheOrderTheChunksWereListedWhateverOrderTheyFinishIn() throws Exception {
        int chunks = 5 * ChunkWindow.SIZE;
        List<Supplier<Future<Integer>>> starts = new ArrayList<>();
        for (int i = 0; i < chunks; i++) {
            int n = i;
            // Earlier chunks take longer, so they finish after later ones of the same window.
            starts.add(() -> POOL.submit(() -> {
                Thread.sleep((chunks - n) % 4);
                return n;
            }));
        }
        List<Integer> seen = new ArrayList<>();
        ChunkWindow.run(starts, seen::add);
        List<Integer> expected = new ArrayList<>();
        for (int i = 0; i < chunks; i++) {
            expected.add(i);
        }
        assertEquals(expected, seen);
    }

    @Test
    void neverMoreThanAWindowOfChunksIsStartedAndNotYetHandedOn() throws Exception {
        AtomicInteger started = new AtomicInteger();
        AtomicInteger handedOn = new AtomicInteger();
        AtomicInteger widest = new AtomicInteger();
        List<Supplier<Future<Integer>>> starts = new ArrayList<>();
        for (int i = 0; i < 6 * ChunkWindow.SIZE; i++) {
            int n = i;
            starts.add(() -> {
                widest.accumulateAndGet(started.incrementAndGet() - handedOn.get(), Math::max);
                return POOL.submit(() -> n);
            });
        }
        ChunkWindow.run(starts, n -> handedOn.incrementAndGet());
        assertEquals(6 * ChunkWindow.SIZE, handedOn.get());
        assertEquals(ChunkWindow.SIZE, widest.get(), "the window fills, and never overfills");
    }

    /** What the window is for: a run started second gets its turn long before the first has finished. */
    @Test
    void twoRunsOnOnePoolTakeTurns() throws Exception {
        ExecutorService one = Executors.newFixedThreadPool(1);
        try {
            int chunks = 20 * ChunkWindow.SIZE;
            AtomicInteger firstDone = new AtomicInteger();
            AtomicInteger firstDoneWhenSecondBegan = new AtomicInteger(-1);
            CountDownLatch firstIsRunning = new CountDownLatch(1);
            Future<?> first = POOL.submit(() -> {
                ChunkWindow.run(starts(one, chunks, firstIsRunning), n -> firstDone.incrementAndGet());
                return null;
            });
            assertTrue(firstIsRunning.await(10, TimeUnit.SECONDS));
            ChunkWindow.run(starts(one, 1, new CountDownLatch(1)), n -> firstDoneWhenSecondBegan.set(firstDone.get()));
            first.get(60, TimeUnit.SECONDS);
            assertTrue(firstDoneWhenSecondBegan.get() <= 2 * ChunkWindow.SIZE,
                    "the second run's chunk waited behind " + firstDoneWhenSecondBegan.get() + " of the first run's " + chunks);
        } finally {
            one.shutdownNow();
        }
    }

    private static List<Supplier<Future<Integer>>> starts(ExecutorService pool, int chunks, CountDownLatch running) {
        List<Supplier<Future<Integer>>> starts = new ArrayList<>();
        for (int i = 0; i < chunks; i++) {
            int n = i;
            starts.add(() -> pool.submit(() -> {
                running.countDown();
                Thread.sleep(2);
                return n;
            }));
        }
        return starts;
    }

    @Test
    void aFailedChunkCancelsTheOnesStillInFlight() {
        CountDownLatch never = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        List<Supplier<Future<Integer>>> starts = new ArrayList<>();
        starts.add(() -> {
            CompletableFuture<Integer> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException("boom"));
            return failed;
        });
        for (int i = 0; i < 3; i++) {
            starts.add(() -> {
                Future<Integer> blocked = POOL.submit(() -> {
                    never.await();
                    return 1;
                });
                futures.add(blocked);
                return blocked;
            });
        }
        ExecutionException e = assertThrows(ExecutionException.class, () -> ChunkWindow.run(starts, n -> { }));
        assertEquals("boom", e.getCause().getMessage());
        assertEquals(3, futures.size());
        assertTrue(futures.stream().allMatch(Future::isCancelled));
    }

    @Test
    void theConsumerThrowingCancelsTheOnesStillInFlight() {
        CountDownLatch never = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        List<Supplier<Future<Integer>>> starts = new ArrayList<>();
        starts.add(() -> CompletableFuture.completedFuture(0));
        starts.add(() -> {
            Future<Integer> blocked = POOL.submit(() -> {
                never.await();
                return 1;
            });
            futures.add(blocked);
            return blocked;
        });
        assertThrows(IllegalArgumentException.class, () -> ChunkWindow.run(starts, n -> {
            throw new IllegalArgumentException("stop");
        }));
        assertTrue(futures.get(0).isCancelled());
    }
}
