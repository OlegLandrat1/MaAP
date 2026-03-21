package org.example;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {
    public static void main(String[] args) {
        Custom1ThreadPoolExecutor pool = new Custom1ThreadPoolExecutor(
                2,              // corePoolSize
                4,              // maxPoolSize
                2,              // keepAliveTime
                TimeUnit.SECONDS,
                8,              // queueSize
                1               // minSpareThreads
        );

        int runnableTasks = 12;
        int callableTasks = 6;

        AtomicInteger rCounter = new AtomicInteger(0);

        // Runnable задачи
        for (int i = 0; i < runnableTasks; i++) {
            final int id = i;
            pool.execute(() -> {
                int n = rCounter.incrementAndGet();
                String tn = Thread.currentThread().getName();
                System.out.println("Runnable #" + id + " start on " + tn + ", startedCount=" + n);
                try {
                    Thread.sleep(200 + (id % 4) * 150L);
                } catch (InterruptedException e) {
                    System.out.println("Runnable #" + id + " interrupted on " + tn);
                    Thread.currentThread().interrupt();
                }
                System.out.println("Runnable #" + id + " end   on " + tn);
            });
        }

        // Callable задачи + сбор Future
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < callableTasks; i++) {
            final int id = i;
            futures.add(pool.submit(() -> {
                String tn = Thread.currentThread().getName();
                System.out.println("Callable #" + id + " start on " + tn);
                Thread.sleep(300 + (id % 3) * 200L);
                System.out.println("Callable #" + id + " end   on " + tn);
                return id * id;
            }));
        }

        // Получаем результаты callable
        for (int i = 0; i < futures.size(); i++) {
            try {
                Integer res = futures.get(i).get(5, TimeUnit.SECONDS);
                System.out.println("Future result for Callable #" + i + " = " + res);
            } catch (TimeoutException e) {
                System.out.println("Future timeout for Callable #" + i);
            } catch (ExecutionException e) {
                System.out.println("Future failed for Callable #" + i + ": " + e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println("Main interrupted while waiting future #" + i);
            }
        }

        // Корректное завершение
        System.out.println("Calling shutdown()...");
        pool.shutdown();
        System.out.println("Pool terminated.");
    }
}
