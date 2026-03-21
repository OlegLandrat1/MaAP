package org.example;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {
    private static final AtomicInteger taskCounter = new AtomicInteger(0);
    private static final AtomicInteger rejectedCounter = new AtomicInteger(0);
    private static final Object lock = new Object();

    public static void main(String[] args) throws InterruptedException {
        System.out.println("╔════════════════════════════════════════════════════════════╗");
        System.out.println("║  Демонстрация Custom Thread Pool с Load Balancing          ║");
        System.out.println("╚════════════════════════════════════════════════════════════╝\n");

        // Инициализация пула
        CustomThreadPoolExecutor executor = new CustomThreadPoolExecutor(
                2,           // corePoolSize (базовое количество потоков)
                4,           // maxPoolSize (максимальное количество потоков)
                5,           // keepAliveTime (время жизни non-core потоков)
                TimeUnit.SECONDS,
                5,           // queueSize (размер очереди на рабочий поток)
                0            // minSpareThreads
        );

        System.out.println("✓ Пул инициализирован:");
        System.out.println("  • corePoolSize = 2");
        System.out.println("  • maxPoolSize = 4");
        System.out.println("  • queueSize = 5 (на рабочий поток)");
        System.out.println("  • keepAliveTime = 5 сек");
        System.out.println();

        // Тест 1: Нормальное выполнение задач с демонстрацией Round Robin
        System.out.println("╔════════════════════════════════════════════════════════════╗");
        System.out.println("║ ТЕСТ 1: Round Robin распределение задач                    ║");
        System.out.println("╚════════════════════════════════════════════════════════════╝\n");
        testRoundRobin(executor);
        Thread.sleep(3000);
        printSeparator();

        // Тест 2: Задачи с разной длительностью
        System.out.println("╔════════════════════════════════════════════════════════════╗");
        System.out.println("║ ТЕСТ 2: Задачи с разной длительностью                      ║");
        System.out.println("╚════════════════════════════════════════════════════════════╝\n");
        testMixedDurations(executor);
        Thread.sleep(4000);
        printSeparator();

        // Тест 3: Переполнение очереди (Queue Overflow)
        System.out.println("╔════════════════════════════════════════════════════════════╗");
        System.out.println("║ ТЕСТ 3: Переполнение очереди и отклонение задач            ║");
        System.out.println("╚════════════════════════════════════════════════════════════╝\n");
        testQueueOverflow(executor);
        Thread.sleep(3000);
        printSeparator();

        // Тест 4: Submit с Callable и Future
        System.out.println("╔════════════════════════════════════════════════════════════╗");
        System.out.println("║ ТЕСТ 4: Callable, Future и получение результатов           ║");
        System.out.println("╚════════════════════════════════════════════════════════════╝\n");
        testCallableAndFuture(executor);
        Thread.sleep(2000);
        printSeparator();

        // Тест 5: Нагрузочное тестирование
        System.out.println("╔════════════════════════════════════════════════════════════╗");
        System.out.println("║ ТЕСТ 5: Нагрузочное тестирование (50 задач)                ║");
        System.out.println("╚════════════════════════════════════════════════════════════╝\n");
        testLoadBalancing(executor);
        Thread.sleep(3000);
        printSeparator();

        // Graceful Shutdown
        System.out.println("╔════════════════════════════════════════════════════════════╗");
        System.out.println("║ ЗАВЕРШЕНИЕ РАБОТЫ ПУЛА                                     ║");
        System.out.println("╚════════════════════════════════════════════════════════════╝\n");
        System.out.println("⏳ Вызов shutdown()...");
        long startTime = System.currentTimeMillis();
        executor.shutdown();
        long endTime = System.currentTimeMillis();
        System.out.println("✓ Shutdown завершён за " + (endTime - startTime) + " мс");
        System.out.println("✓ Все потоки корректно освобождены\n");

        // Статистика
        printStatistics();
    }

    /**
     * ТЕСТ 1: Демонстрирует Round Robin распределение
     */
    private static void testRoundRobin(CustomThreadPoolExecutor executor) {
        System.out.println("Отправляем 8 задач с Round Robin распределением:\n");

        for (int i = 0; i < 8; i++) {
            final int taskId = taskCounter.incrementAndGet();
            final int roundNumber = i;

            try {
                executor.execute(() -> {
                    String threadName = Thread.currentThread().getName();
                    log("Задача #" + taskId + " начата на потоке " + threadName);

                    try {
                        Thread.sleep(800);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }

                    log("Задача #" + taskId + " завершена");
                });

                // Round Robin распределение
                int workerIndex = roundNumber % 2; // Так как corePoolSize=2
                System.out.printf("  Задача #%d отправлена → в очередь рабочего потока %d\n",
                        taskId, workerIndex);

            } catch (RejectedExecutionException e) {
                rejectedCounter.incrementAndGet();
                System.out.printf("  ❌ Задача #%d ОТКЛОНЕНА\n", taskId);
            }
        }
    }

    /**
     * ТЕСТ 2: Задачи с разной длительностью
     */
    private static void testMixedDurations(CustomThreadPoolExecutor executor) {
        int[] durations = {1000, 500, 1500, 800, 600, 1200, 400, 900};
        System.out.println("Отправляем " + durations.length + " задач с разной длительностью:\n");

        for (int i = 0; i < durations.length; i++) {
            final int taskId = taskCounter.incrementAndGet();
            final long duration = durations[i];

            try {
                executor.execute(() -> {
                    String threadName = Thread.currentThread().getName();
                    log("Задача #" + taskId + " (длительность " + duration + "ms) начата");

                    try {
                        Thread.sleep(duration);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }

                    log("Задача #" + taskId + " завершена за " + duration + "ms");
                });

                System.out.printf("  Задача #%d отправлена (длительность: %d ms)\n",
                        taskId, duration);

            } catch (RejectedExecutionException e) {
                rejectedCounter.incrementAndGet();
                System.out.printf("  ❌ Задача #%d ОТКЛОНЕНА\n", taskId);
            }
        }
    }

    /**
     * ТЕСТ 3: Переполнение очереди
     */
    private static void testQueueOverflow(CustomThreadPoolExecutor executor)
            throws InterruptedException {
        System.out.println("Создаём блокирующие задачи для заполнения пула:\n");

        CountDownLatch blockLatch = new CountDownLatch(1);
        CountDownLatch startedLatch = new CountDownLatch(2);

        // Блокируем оба core потока
        for (int i = 0; i < 2; i++) {
            final int blockingId = i;
            executor.execute(() -> {
                startedLatch.countDown();
                String threadName = Thread.currentThread().getName();
                log("Блокирующая задача #" + blockingId + " запущена на " + threadName);

                try {
                    blockLatch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                log("Блокирующая задача #" + blockingId + " завершена");
            });
        }

        // Ждём, пока оба потока начнут работу
        if (startedLatch.await(3, TimeUnit.SECONDS)) {
            System.out.println("✓ Оба core потока заняты\n");
        }

        System.out.println("Отправляем 15 задач (будут распределены по очередям и отклонены):\n");

        int acceptedCount = 0;
        int rejectedCount = 0;

        for (int i = 0; i < 15; i++) {
            final int taskId = taskCounter.incrementAndGet();

            try {
                executor.execute(() -> {
                    log("Обычная задача #" + taskId + " выполняется");
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });

                acceptedCount++;
                System.out.printf("  ✓ Задача #%d принята (принято: %d)\n",
                        taskId, acceptedCount);

            } catch (RejectedExecutionException e) {
                rejectedCount++;
                rejectedCounter.incrementAndGet();
                System.out.printf("  ❌ Задача #%d ОТКЛОНЕНА: %s (отклонено: %d)\n",
                        taskId, e.getMessage(), rejectedCount);
            }
        }

        System.out.println("\n📊 Итоги переполнения:");
        System.out.println("  • Принято: " + acceptedCount);
        System.out.println("  • Отклонено: " + rejectedCount);

        // Разблокируем
        System.out.println("\n⏳ Разблокировка основных потоков...");
        blockLatch.countDown();
        Thread.sleep(1000);
    }

    /**
     * ТЕСТ 4: Callable и Future
     */
    private static void testCallableAndFuture(CustomThreadPoolExecutor executor) {
        System.out.println("Отправляем Callable задачи и получаем результаты:\n");

        try {
            // Callable 1: Вычисление
            Future<Integer> future1 = executor.submit(() -> {
                log("Callable 1: начало вычисления 25 * 4");
                Thread.sleep(600);
                log("Callable 1: завершение вычисления");
                return 25 * 4;
            });

            // Callable 2: Формирование строки
            Future<String> future2 = executor.submit(() -> {
                log("Callable 2: формирование строки");
                Thread.sleep(400);
                log("Callable 2: строка готова");
                return "Hello from Thread Pool!";
            });

            // Callable 3: Math операция
            Future<Double> future3 = executor.submit(() -> {
                log("Callable 3: вычисление Math.sqrt(144)");
                Thread.sleep(500);
                log("Callable 3: результат готов");
                return Math.sqrt(144);
            });

            // Получаем результаты
            System.out.println("\n⏳ Получение результатов с таймаутом (2 сек):\n");

            try {
                int result1 = future1.get(2, TimeUnit.SECONDS);
                System.out.println("  ✓ Результат Callable 1: " + result1);
            } catch (TimeoutException e) {
                System.out.println("  ❌ Таймаут Callable 1");
            }

            try {
                String result2 = future2.get(2, TimeUnit.SECONDS);
                System.out.println("  ✓ Результат Callable 2: " + result2);
            } catch (TimeoutException e) {
                System.out.println("  ❌ Таймаут Callable 2");
            }

            try {
                Double result3 = future3.get(2, TimeUnit.SECONDS);
                System.out.println("  ✓ Результат Callable 3: " + result3);
            } catch (TimeoutException e) {
                System.out.println("  ❌ Таймаут Callable 3");
            }

        } catch (ExecutionException e) {
            System.out.println("  ❌ Ошибка выполнения: " + e.getCause());
        } catch (InterruptedException e) {
            System.out.println("  ❌ Прервано");
            Thread.currentThread().interrupt();
        }
    }

    /**
     * ТЕСТ 5: Нагрузочное тестирование с демонстрацией балансировки
     */
    private static void testLoadBalancing(CustomThreadPoolExecutor executor)
            throws InterruptedException {
        System.out.println("Запускаем нагрузочный тест с 50 задачами:\n");

        AtomicInteger completedCount = new AtomicInteger(0);
        CountDownLatch completionLatch = new CountDownLatch(50);

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < 50; i++) {
            final int taskId = taskCounter.incrementAndGet();
            final int duration = 100 + (taskId % 400); // Варьируем длительность

            try {
                executor.execute(() -> {
                    try {
                        Thread.sleep(duration);
                        completedCount.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        completionLatch.countDown();
                    }
                });

                if (i % 10 == 0) {
                    System.out.printf("  Отправлено: %d задач\n", i);
                }

            } catch (RejectedExecutionException e) {
                rejectedCounter.incrementAndGet();
                completionLatch.countDown();
                System.out.printf("  ❌ Задача #%d отклонена\n", taskId);
            }
        }

        System.out.println("\n⏳ Ожидание завершения всех задач...");
        if (completionLatch.await(15, TimeUnit.SECONDS)) {
            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;

            System.out.println("\n✓ Все 50 задач завершены!");
            System.out.println("  • Время выполнения: " + duration + " мс");
            System.out.println("  • Среднее время на задачу: " + (duration / 50) + " мс");
            System.out.println("  • Успешно выполнено: " + completedCount.get());
        } else {
            System.out.println("❌ Таймаут при ожидании завершения");
        }
    }

    /**
     * Логирование с синхронизацией
     */
    private static void log(String message) {
        synchronized (lock) {
            long timestamp = System.currentTimeMillis();
            System.out.println("  [" + timestamp % 10000 + "ms] [" +
                    Thread.currentThread().getName() + "] " + message);
        }
    }

    /**
     * Печать разделителя
     */
    private static void printSeparator() {
        System.out.println("\n");
    }

    /**
     * Итоговая статистика
     */
    private static void printStatistics() {
        System.out.println("╔════════════════════════════════════════════════════════════╗");
        System.out.println("║ ИТОГОВАЯ СТАТИСТИКА                                        ║");
        System.out.println("╚════════════════════════════════════════════════════════════╝\n");

        System.out.println("📊 Общие метрики:");
        System.out.println("  • Всего отправлено задач: " + taskCounter.get());
        System.out.println("  • Всего отклонено задач: " + rejectedCounter.get());
        System.out.println("  • Успешно выполнено: " + (taskCounter.get() - rejectedCounter.get()));
        System.out.println("  • Процент успеха: " +
                String.format("%.2f%%", (100.0 * (taskCounter.get() - rejectedCounter.get()) /
                        taskCounter.get())));

        System.out.println("\n✓ Демонстрация завершена успешно!");
    }
}
