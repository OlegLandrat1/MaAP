package org.example;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.Condition;

public class CustomThreadPoolExecutor implements CustomExecutor {

    final int corePoolSize;
    final int maxPoolSize;
    final long keepAliveTime;
    private final TimeUnit timeUnit;
    final int queueSize;
    final int minSpareThreads;

    // Отдельная очередь для каждого рабочего потока
    private final List<LinkedBlockingQueue<Runnable>> workerQueues;

    // Глобальная очередь для переполнения (используется когда все локальные очереди полны)
    private final BlockingQueue<Runnable> globalQueue;

    private final List<Worker> workers;
    final AtomicInteger workerCount;
    private final AtomicLong completedTaskCount = new AtomicLong(0);
    private final AtomicBoolean shutdown;

    // Счётчик для Round Robin распределения
    private final AtomicInteger nextWorkerIndex = new AtomicInteger(0);

    final ReentrantLock mainLock;
    private final Condition termination;
    private final ThreadFactory threadFactory;

    public CustomThreadPoolExecutor(
            int corePoolSize,
            int maxPoolSize,
            long keepAliveTime,
            TimeUnit timeUnit,
            int queueSize,
            int minSpareThreads) {

        if (corePoolSize <= 0 || maxPoolSize <= 0 || queueSize <= 0 ||
                minSpareThreads < 0 || corePoolSize > maxPoolSize ||
                keepAliveTime < 0) {
            throw new IllegalArgumentException("Invalid pool parameters");
        }

        this.corePoolSize = corePoolSize;
        this.maxPoolSize = maxPoolSize;
        this.keepAliveTime = keepAliveTime;
        this.timeUnit = timeUnit;
        this.queueSize = queueSize;
        this.minSpareThreads = minSpareThreads;

        // Инициализируем очереди для каждого рабочего потока
        this.workerQueues = Collections.synchronizedList(new ArrayList<>());

        // Глобальная очередь меньшего размера для резерва
        this.globalQueue = new LinkedBlockingQueue<>(Math.max(1, queueSize / 4));

        this.workers = new ArrayList<>();
        this.workerCount = new AtomicInteger(0);
        this.shutdown = new AtomicBoolean(false);
        this.mainLock = new ReentrantLock();
        this.termination = mainLock.newCondition();
        this.threadFactory = Executors.defaultThreadFactory();

        startCoreThreads();
    }

    private void startCoreThreads() {
        mainLock.lock();
        try {
            for (int i = 0; i < corePoolSize; i++) {
                addWorker(null);
            }
        } finally {
            mainLock.unlock();
        }
    }

    @Override
    public void execute(Runnable command) {
        if (command == null) {
            throw new NullPointerException();
        }
        if (shutdown.get()) {
            throw new RejectedExecutionException("Executor is shutdown");
        }

        // Round Robin распределение по локальным очередям рабочих потоков
        if (tryDistributeToWorkerQueues(command)) {
            return;
        }

        // Если все локальные очереди заполнены, пытаемся добавить новый поток
        if (!addWorker(command)) {
            // Если максимум потоков достигнут, добавляем в глобальную очередь
            if (!globalQueue.offer(command)) {
                throw new RejectedExecutionException(
                        "Очередь полная и достигнуто maxPoolSize");
            }
        }

        ensureMinSpareThreads();
    }

    /**
     * Распределяет задачу по локальным очередям рабочих потоков
     * используя Round Robin алгоритм
     */
    private boolean tryDistributeToWorkerQueues(Runnable command) {
        if (workerQueues.isEmpty()) {
            return false;
        }

        int attemptCount = 0;
        int maxAttempts = workerQueues.size();

        while (attemptCount < maxAttempts) {
            int index = nextWorkerIndex.getAndIncrement() % workerQueues.size();
            BlockingQueue<Runnable> queue = workerQueues.get(index);

            // Пытаемся добавить в очередь без блокировки
            if (queue.offer(command)) {
                return true;
            }
            attemptCount++;
        }

        return false;
    }

    @Override
    public <T> Future<T> submit(Callable<T> callable) {
        if (callable == null) {
            throw new NullPointerException();
        }
        if (shutdown.get()) {
            throw new RejectedExecutionException("Executor is shutdown");
        }

        CustomFutureTask<T> ftask = new CustomFutureTask<>(callable);
        execute(ftask);
        return ftask;
    }

    private boolean addWorker(Runnable firstTask) {
        mainLock.lock();
        try {
            if (shutdown.get()) return false;
            if (workerCount.get() >= maxPoolSize) return false;

            // Создаём отдельную очередь для этого рабочего потока
            LinkedBlockingQueue<Runnable> workerQueue =
                    new LinkedBlockingQueue<>(queueSize);

            // Если есть firstTask, добавляем её в очередь сразу
            if (firstTask != null) {
                workerQueue.offer(firstTask);
            }

            Worker w = new Worker(workerQueue);
            workers.add(w);
            workerQueues.add(workerQueue);
            workerCount.incrementAndGet();

            Thread t = Executors.defaultThreadFactory().newThread(w);
            w.thread = t;
            t.start();

            return true;
        } finally {
            mainLock.unlock();
        }
    }

    private int getIdleWorkerCount() {
        mainLock.lock();
        try {
            return (int) workers.stream().filter(w ->
                    w.thread != null && w.thread.getState() == Thread.State.WAITING).count();
        } finally {
            mainLock.unlock();
        }
    }

    private void ensureMinSpareThreads() {
        int idleCount = getIdleWorkerCount();
        if (idleCount < minSpareThreads) {
            int workersNeeded = minSpareThreads - idleCount;
            mainLock.lock();
            try {
                for (int i = 0; i < workersNeeded && workerCount.get() < maxPoolSize; i++) {
                    addWorker(null);
                }
            } finally {
                mainLock.unlock();
            }
        }
    }

    @Override
    public void shutdown() {
        mainLock.lock();
        try {
            if (shutdown.compareAndSet(false, true)) {
                interruptIdleWorkers();
            }
            while (workerCount.get() != 0) {
                termination.await();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            mainLock.unlock();
        }
    }

    @Override
    public boolean shutdownNow() {
        mainLock.lock();
        try {
            if (shutdown.compareAndSet(false, true)) {
                interruptAllWorkers();
            }
        } finally {
            mainLock.unlock();
        }
        return false;
    }

    private void interruptIdleWorkers() {
        mainLock.lock();
        try {
            for (Worker worker : workers) {
                if (worker.thread.getState() == Thread.State.WAITING) {
                    worker.thread.interrupt();
                }
            }
        } finally {
            mainLock.unlock();
        }
    }

    private void interruptAllWorkers() {
        mainLock.lock();
        try {
            for (Worker worker : workers) {
                if (worker.thread != null && !worker.thread.isInterrupted()) {
                    worker.thread.interrupt();
                }
            }
        } finally {
            mainLock.unlock();
        }
    }

    private final class Worker implements Runnable {
        volatile Thread thread;
        final BlockingQueue<Runnable> localQueue;
        volatile long lastActivityTime;
        final AtomicBoolean busy = new AtomicBoolean(false);

        Worker(BlockingQueue<Runnable> localQueue) {
            this.localQueue = localQueue;
            this.lastActivityTime = System.currentTimeMillis();
        }

        @Override
        public void run() {
            try {
                Runnable task;

                // Сначала обрабатываем локальную очередь, затем глобальную
                while (!shutdown.get() || !localQueue.isEmpty()) {
                    task = getTask();

                    if (task == null) {
                        // Проверяем глобальную очередь
                        task = globalQueue.poll();
                    }

                    if (task != null) {
                        busy.set(true);
                        try {
                            task.run();
                        } catch (Throwable t) {
                            t.printStackTrace();
                        } finally {
                            busy.set(false);
                            completedTaskCount.incrementAndGet();
                            lastActivityTime = System.currentTimeMillis();
                        }
                    } else if (shutdown.get()) {
                        break;
                    }
                }
            } finally {
                mainLock.lock();
                try {
                    workers.remove(this);
                    workerQueues.remove(localQueue);
                    int c = workerCount.decrementAndGet();

                    if (shutdown.get() && c == 0) {
                        termination.signalAll();
                    }
                } finally {
                    mainLock.unlock();
                }
            }
        }

        /**
         * Получает задачу из локальной очереди текущего потока
         * с учётом таймаута для non-core потоков
         */
        private Runnable getTask() {
            for (;;) {
                try {
                    if (shutdown.get() && localQueue.isEmpty()) {
                        return null;
                    }

                    boolean timed = workerCount.get() > corePoolSize;

                    Runnable task = timed
                            ? localQueue.poll(keepAliveTime, timeUnit)
                            : localQueue.take();

                    if (task != null) return task;

                    // Таймаут истёк для non-core потока
                    if (timed) return null;

                } catch (InterruptedException e) {
                    if (shutdown.get() && localQueue.isEmpty()) {
                        return null;
                    }
                }
            }
        }
    }

    private static class CustomFutureTask<T> implements RunnableFuture<T> {
        private final Callable<T> callable;
        private volatile boolean cancelled = false;
        private volatile boolean done = false;
        private T result;
        private Exception exception;
        private final Object lock = new Object();

        CustomFutureTask(Callable<T> callable) {
            this.callable = callable;
        }

        @Override
        public void run() {
            try {
                result = callable.call();
            } catch (Exception e) {
                exception = e;
            } finally {
                synchronized (lock) {
                    done = true;
                    lock.notifyAll();
                }
            }
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            synchronized (lock) {
                if (isDone()) {
                    return false;
                }
                cancelled = true;
                lock.notifyAll();
                return true;
            }
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return done;
        }

        @Override
        public T get() throws InterruptedException, ExecutionException {
            synchronized (lock) {
                while (!isDone()) {
                    lock.wait();
                }
                if (exception != null) {
                    throw new ExecutionException(exception);
                }
                return result;
            }
        }

        @Override
        public T get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            synchronized (lock) {
                if (!isDone()) {
                    long nanos = unit.toNanos(timeout);
                    long deadline = System.nanoTime() + nanos;

                    while (!isDone() && nanos > 0) {
                        lock.wait(nanos / 1_000_000, (int) (nanos % 1_000_000));
                        nanos = deadline - System.nanoTime();
                    }
                }

                if (!isDone()) {
                    throw new TimeoutException();
                }

                if (exception != null) {
                    throw new ExecutionException(exception);
                }
                return result;
            }
        }
    }
}
