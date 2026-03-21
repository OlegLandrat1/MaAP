package org.example;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.Condition;

public class Custom1ThreadPoolExecutor implements CustomExecutor {

    final int corePoolSize;
    private final int maxPoolSize;
    private final long keepAliveTime;
    private final TimeUnit timeUnit;
    private final int queueSize;
    private final int minSpareThreads;

    private final BlockingQueue<Runnable> workQueue;
    private final List<Worker> workers;
    final AtomicInteger workerCount;
    private final AtomicLong completedTaskCount = new AtomicLong(0);
    private final AtomicBoolean shutdown;

    final ReentrantLock mainLock;
    private final Condition termination;
    private final ThreadFactory threadFactory;

    public Custom1ThreadPoolExecutor(
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

        this.workQueue = new ArrayBlockingQueue<>(queueSize);
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
        // сначала попытаемся положить в очередь
        if (tryAddToQueue(command)) {
            ensureMinSpareThreads();
            return;
        }

        // если очередь полная, пробуем добавить воркер с rirstTask
        if (!addWorker(command)) {
            throw new RejectedExecutionException("Очередь полная и достигнуто maxPoolSize");
        }
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
        addWorker(ftask);
        return ftask;
    }

    private boolean addWorker(Runnable firstTask) {
        mainLock.lock();
        try {
           if (shutdown.get()) return false;
           if (workerCount.get() >= maxPoolSize) return false;

           Worker w = new Worker(firstTask);
           // Регистрируем воркера и увеличиваем счетчик до старта
            Thread t = threadFactory.newThread(w);
            w.thread = t;
            t.start();
            return true;
        } finally {
            mainLock.unlock();
        }
    }

    private boolean tryAddToQueue(Runnable r) {
        return workQueue.offer(r);
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
        Runnable firstTask;
        volatile long lastActivityTime;

        final AtomicBoolean busy = new AtomicBoolean(false);

        Worker(Runnable firstTask) {
            this.firstTask = firstTask;
            this.lastActivityTime = System.currentTimeMillis();
        }

        @Override
        public void run() {
            try {
                Runnable task = firstTask;
                firstTask = null;

                while (task != null || (task = getTask()) != null) {
                    busy.set(true);
                    try {
                        task.run();
                    } catch (Throwable t) {
                        t.printStackTrace();
                    } finally {
                        busy.set(false);
                        completedTaskCount.incrementAndGet(); // <-- статистика
                        task = null;
                        lastActivityTime = System.currentTimeMillis();
                    }
                }
            } finally {
                mainLock.lock();
                try {
                    workers.remove(this);
                    int c = workerCount.decrementAndGet();

                    // если shutdown и это был последний воркер — будим shutdown()
                    if (shutdown.get() && c == 0) {
                        termination.signalAll();
                    }
                } finally {
                    mainLock.unlock();
                }
            }
        }

        private Runnable getTask() {
            for (;;) {
                try {
                    // Проверяем состояние пула
                    if (shutdown.get() && workQueue.isEmpty()) {
                        return null;
                    }

                    boolean timed = workerCount.get() > corePoolSize;

                    // Пытаемся взять задачу из очереди
                    Runnable task = timed
                            ? workQueue.poll(keepAliveTime, timeUnit)
                            : workQueue.take();

                    if (task != null) return task;
                    // pool()вернул null => таймаут у non-core
                    if (timed) return null;

                } catch (InterruptedException e) {
                    // Проверяем состояние после прерывания
                    if (shutdown.get() && workQueue.isEmpty()) {
                        return null;
                    }
                }
            }
        }

        private boolean shouldTerminate() {
            if (workerCount.get() > corePoolSize) {
                long idleTime = System.currentTimeMillis() - lastActivityTime;
                return timeUnit.toNanos(keepAliveTime) <= idleTime;
            }
            return false;
        }

        private long taskTimeoutNanos() {
            return timeUnit.toNanos(keepAliveTime);
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
            if (!isCancelled() && !isDone()) {
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

// Интерфейс
interface CustomExecutor extends Executor {
    void execute(Runnable command);
    <T> Future<T> submit(Callable<T> callable);
    void shutdown();
    boolean shutdownNow();
}
