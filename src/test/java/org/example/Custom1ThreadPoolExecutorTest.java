package org.example;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Timeout;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Custom1ThreadPoolExecutorTest {

    private Custom1ThreadPoolExecutor executor;

    private Custom1ThreadPoolExecutor createDefault() {
        return new Custom1ThreadPoolExecutor(
                2,   // corePoolSize
                4,   // maxPoolSize
                500, // keepAliveTime
                TimeUnit.MILLISECONDS,
                10,  // queueSize
                1    // minSpareThreads
        );
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    // ─── Конструктор ───────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void constructor_validParams_doesNotThrow() {
        assertDoesNotThrow(() -> {
            executor = createDefault();
        });
    }

    @Test
    @Order(2)
    void constructor_zeroCorePool_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () ->
                new Custom1ThreadPoolExecutor(0, 4, 500, TimeUnit.MILLISECONDS, 10, 1));
    }

    @Test
    @Order(3)
    void constructor_zeroMaxPool_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () ->
                new Custom1ThreadPoolExecutor(2, 0, 500, TimeUnit.MILLISECONDS, 10, 1));
    }

    @Test
    @Order(4)
    void constructor_coreGreaterThanMax_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () ->
                new Custom1ThreadPoolExecutor(5, 3, 500, TimeUnit.MILLISECONDS, 10, 1));
    }

    @Test
    @Order(5)
    void constructor_negativeKeepAlive_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () ->
                new Custom1ThreadPoolExecutor(2, 4, -1, TimeUnit.MILLISECONDS, 10, 1));
    }

    @Test
    @Order(6)
    void constructor_zeroQueueSize_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () ->
                new Custom1ThreadPoolExecutor(2, 4, 500, TimeUnit.MILLISECONDS, 0, 1));
    }

    @Test
    @Order(7)
    void constructor_negativeMinSpareThreads_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () ->
                new Custom1ThreadPoolExecutor(2, 4, 500, TimeUnit.MILLISECONDS, 10, -1));
    }

    // ─── execute ───────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    @Timeout(5)
    void execute_singleTask_runsToCompletion() throws InterruptedException {
        executor = createDefault();
        CountDownLatch latch = new CountDownLatch(1);

        executor.execute(latch::countDown);

        assertTrue(latch.await(3, TimeUnit.SECONDS));
    }

    @Test
    @Order(11)
    @Timeout(10)
    void execute_multipleTasks_allComplete() throws InterruptedException {
        executor = createDefault();
        int taskCount = 8;
        CountDownLatch latch = new CountDownLatch(taskCount);

        for (int i = 0; i < taskCount; i++) {
            executor.execute(latch::countDown);
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    @Order(12)
    void execute_nullTask_throwsNullPointer() {
        executor = createDefault();
        assertThrows(NullPointerException.class, () -> executor.execute(null));
    }

    @Test
    @Order(13)
    void execute_afterShutdown_throwsRejected() {
        executor = createDefault();
        executor.shutdownNow();

        assertThrows(RejectedExecutionException.class, () ->
                executor.execute(() -> {}));
    }

    @Test
    @Order(14)
    @Timeout(10)
    void execute_queueOverflow_throwsRejected() throws InterruptedException {
        // corePoolSize=1, maxPoolSize=1, queue=2 — быстро заполним
        executor = new Custom1ThreadPoolExecutor(
                1, 1, 500, TimeUnit.MILLISECONDS, 2, 0);

        CountDownLatch blockLatch = new CountDownLatch(1);
        CountDownLatch startedLatch = new CountDownLatch(1);

        // Блокирующая задача, занимает единственный поток
        executor.execute(() -> {
            startedLatch.countDown();
            try { blockLatch.await(); } catch (InterruptedException ignored) {}
        });
        startedLatch.await(2, TimeUnit.SECONDS);

        // Заполняем очередь
        executor.execute(() -> {});
        executor.execute(() -> {});
        executor.execute(() -> {});

        // Следующая должна отклониться
        assertThrows(RejectedExecutionException.class, () ->
                executor.execute(() -> {}));

        blockLatch.countDown();
    }

    @Test
    @Order(15)
    @Timeout(10)
    void execute_taskThrowsException_poolContinuesWorking() throws InterruptedException {
        executor = createDefault();
        CountDownLatch latch = new CountDownLatch(1);

        executor.execute(() -> { throw new RuntimeException("test error"); });
        Thread.sleep(200);
        executor.execute(latch::countDown);

        assertTrue(latch.await(3, TimeUnit.SECONDS));
    }

    // ─── submit ────────────────────────────────────────────────────────────────

    @Test
    @Order(20)
    @Timeout(5)
    void submit_callable_returnsCorrectResult() throws Exception {
        executor = createDefault();

        Future<Integer> future = executor.submit(() -> 42);

        assertEquals(42, future.get(3, TimeUnit.SECONDS));
    }

    @Test
    @Order(21)
    void submit_null_throwsNullPointer() {
        executor = createDefault();
        assertThrows(NullPointerException.class, () -> executor.submit(null));
    }

    @Test
    @Order(22)
    void submit_afterShutdown_throwsRejected() {
        executor = createDefault();
        executor.shutdownNow();

        assertThrows(RejectedExecutionException.class, () ->
                executor.submit(() -> 1));
    }

    @Test
    @Order(23)
    @Timeout(5)
    void submit_callableThrows_futureGetThrowsExecutionException() throws InterruptedException {
        executor = createDefault();

        Future<Integer> future = executor.submit(() -> {
            throw new IllegalStateException("boom");
        });

        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> future.get(3, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, ex.getCause());
    }

    @Test
    @Order(24)
    @Timeout(10)
    void submit_multipleCallables_allReturnResults() throws Exception {
        executor = createDefault();
        int count = 6;
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            final int val = i;
            futures.add(executor.submit(() -> val * 2));
        }

        for (int i = 0; i < count; i++) {
            assertEquals(i * 2, futures.get(i).get(3, TimeUnit.SECONDS));
        }
    }

    @Test
    @Order(25)
    @Timeout(5)
    void submit_futureGetWithTimeout_throwsTimeoutWhenNotDone() {
        executor = createDefault();

        Future<Integer> future = executor.submit(() -> {
            Thread.sleep(5000);
            return 1;
        });

        assertThrows(TimeoutException.class,
                () -> future.get(200, TimeUnit.MILLISECONDS));
    }

    // ─── Future.isDone / isCancelled ───────────────────────────────────────────

    @Test
    @Order(30)
    @Timeout(5)
    void future_isDone_trueAfterCompletion() throws Exception {
        executor = createDefault();
        Future<String> future = executor.submit(() -> "done");
        future.get(3, TimeUnit.SECONDS);
        assertTrue(future.isDone());
    }

    @Test
    @Order(31)
    void future_isCancelled_falseInitially() {
        executor = createDefault();
        Future<Integer> future = executor.submit(() -> {
            Thread.sleep(3000);
            return 1;
        });
        assertFalse(future.isCancelled());
        future.cancel(true);
    }

    @Test
    @Order(32)
    void future_cancel_setsCancelledFlag() {
        executor = createDefault();
        Future<Integer> future = executor.submit(() -> {
            Thread.sleep(5000);
            return 1;
        });
        boolean cancelled = future.cancel(true);
        assertTrue(cancelled);
        assertTrue(future.isCancelled());
    }

    @Test
    @Order(33)
    @Timeout(5)
    void future_cancelAfterDone_returnsFalse() throws Exception {
        executor = createDefault();
        Future<Integer> future = executor.submit(() -> 99);
        future.get(3, TimeUnit.SECONDS);
        assertFalse(future.cancel(true));
    }

    // ─── shutdown ──────────────────────────────────────────────────────────────

    @Test
    @Order(40)
    @Timeout(10)
    void shutdown_waitsForTasksToComplete() throws InterruptedException {
        executor = createDefault();
        AtomicInteger counter = new AtomicInteger(0);

        for (int i = 0; i < 5; i++) {
            executor.execute(() -> {
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                counter.incrementAndGet();
            });
        }

        executor.shutdown();
        assertEquals(5, counter.get());
    }

    @Test
    @Order(41)
    @Timeout(5)
    void shutdown_calledTwice_doesNotThrow() {
        executor = createDefault();
        assertDoesNotThrow(() -> {
            executor.shutdown();
            executor.shutdown();
        });
    }

    // ─── shutdownNow ───────────────────────────────────────────────────────────

    @Test
    @Order(50)
    @Timeout(5)
    void shutdownNow_interruptsWorkers() throws InterruptedException {
        executor = createDefault();
        CountDownLatch started = new CountDownLatch(1);

        executor.execute(() -> {
            started.countDown();
            try { Thread.sleep(10_000); } catch (InterruptedException ignored) {}
        });

        started.await(2, TimeUnit.SECONDS);
        assertDoesNotThrow(() -> executor.shutdownNow());
    }

    @Test
    @Order(51)
    @Timeout(5)
    void shutdownNow_afterShutdown_doesNotThrow() {
        executor = createDefault();
        executor.shutdown();
        assertDoesNotThrow(() -> executor.shutdownNow());
    }

    // ─── Concurrency / stress ──────────────────────────────────────────────────

    @Test
    @Order(60)
    @Timeout(15)
    void stress_manyTasksConcurrently_allExecuted() throws InterruptedException {
        executor = new Custom1ThreadPoolExecutor(
                4, 8, 500, TimeUnit.MILLISECONDS, 50, 2);

        int taskCount = 40;
        CountDownLatch latch = new CountDownLatch(taskCount);
        AtomicInteger executed = new AtomicInteger(0);

        for (int i = 0; i < taskCount; i++) {
            executor.execute(() -> {
                executed.incrementAndGet();
                latch.countDown();
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertEquals(taskCount, executed.get());
    }

    @Test
    @Order(61)
    @Timeout(15)
    void stress_submitManyCallables_allReturnCorrectly() throws Exception {
        executor = new Custom1ThreadPoolExecutor(
                4, 8, 500, TimeUnit.MILLISECONDS, 50, 2);

        int count = 20;
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            final int val = i;
            futures.add(executor.submit(() -> val));
        }

        for (int i = 0; i < count; i++) {
            assertEquals(i, futures.get(i).get(5, TimeUnit.SECONDS));
        }
    }
}