package org.example;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.io.*;
import java.time.format.DateTimeFormatter;

public class PerformanceMeasurement {
    private static final AtomicInteger taskCounter = new AtomicInteger(0);
    private static final AtomicInteger rejectedCounter = new AtomicInteger(0);
    private static final Object lock = new Object();
    private static final String CSV_DIRECTORY = "metrics/CustomThreadPool";
    private static final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    static {
        File dir = new File(CSV_DIRECTORY);
        if (!dir.exists()) {
            dir.mkdir();
        }
    }

    private static class PerformanceMetrics {
        private final String testName;
        private final long startTime;
        private long endTime;
        private final List<Long> taskExecutionTimes = Collections.synchronizedList(new ArrayList<>());
        private final AtomicLong totalTasksExecuted = new AtomicLong(0);
        private final AtomicLong totalTasksRejected = new AtomicLong(0);
        private final AtomicLong totalCpuTime = new AtomicLong(0);
        private final AtomicLong peakMemoryUsage = new AtomicLong(0);
        private final AtomicInteger maxQueueSize = new AtomicInteger(0);
        private long initialMemory;
        private final List<TaskMetrics> allTaskMetrics = Collections.synchronizedList(new ArrayList<>());

        public PerformanceMetrics(String testName) {
            this.testName = testName;
            this.startTime = System.currentTimeMillis();
            this.initialMemory = getMemoryUsage();
        }

        public void recordTaskExecution(long executionTime) {
            taskExecutionTimes.add(executionTime);
            totalTasksExecuted.incrementAndGet();
        }

        public void recordRejection() {
            totalTasksRejected.incrementAndGet();
        }

        public void recordQueueSize(int size) {
            maxQueueSize.getAndAccumulate(size, Math::max);
        }

        public void addTaskMetrics(TaskMetrics taskMetric) {
            allTaskMetrics.add(taskMetric);
        }

        public void finish() {
            this.endTime = System.currentTimeMillis();
            this.peakMemoryUsage.set(getMemoryUsage() - initialMemory);
        }

        public long getTotalTime() {
            return endTime - startTime;
        }

        public double getAverageExecutionTime() {
            if (taskExecutionTimes.isEmpty()) return 0;
            return taskExecutionTimes.stream()
                    .mapToLong(Long::longValue)
                    .average()
                    .orElse(0);
        }

        public long getMedianExecutionTime() {
            if (taskExecutionTimes.isEmpty()) return 0;
            List<Long> sorted = new ArrayList<>(taskExecutionTimes);
            Collections.sort(sorted);
            return sorted.get(sorted.size() / 2);
        }

        public long getMinExecutionTime() {
            if (taskExecutionTimes.isEmpty()) return 0;
            return taskExecutionTimes.stream()
                    .mapToLong(Long::longValue)
                    .min()
                    .orElse(0);
        }

        public long getMaxExecutionTime() {
            if (taskExecutionTimes.isEmpty()) return 0;
            return taskExecutionTimes.stream()
                    .mapToLong(Long::longValue)
                    .max()
                    .orElse(0);
        }

        public double getStandardDeviation() {
            if (taskExecutionTimes.isEmpty()) return 0;
            double avg = getAverageExecutionTime();
            double variance = taskExecutionTimes.stream()
                    .mapToLong(Long::longValue)
                    .mapToDouble(x -> Math.pow(x - avg, 2))
                    .average()
                    .orElse(0);
            return Math.sqrt(variance);
        }

        public double getThroughput() {
            long totalTime = getTotalTime();
            if (totalTime == 0) return 0;
            return (totalTasksExecuted.get() * 1000.0) / totalTime;
        }

        public double getSuccessRate() {
            long total = totalTasksExecuted.get() + totalTasksRejected.get();
            if (total == 0) return 0;
            return (100.0 * totalTasksExecuted.get()) / total;
        }

        public double getAverageQueueWaitTime() {
            if (allTaskMetrics.isEmpty()) return 0;
            return allTaskMetrics.stream()
                    .mapToLong(TaskMetrics::getQueueWaitTime)
                    .average()
                    .orElse(0);
        }

        public void printReport() {
            System.out.println("\n╔══════════════════════════════════════════════════════════╗");
            System.out.println("║ ОТЧЁТ: " + padRight(testName, 51) +                    "║");
            System.out.println("╚════════════════════════════════════════════════════════════╝\n");

            System.out.println("📊 ВРЕМЯ ВЫПОЛНЕНИЯ:");
            System.out.printf("  • Общее время: %d мс\n", getTotalTime());
            System.out.printf("  • Пропускная способность: %.2f задач/сек\n", getThroughput());

            System.out.println("\n📈 СТАТИСТИКА ВЫПОЛНЕНИЯ ЗАДАЧ:");
            System.out.printf("  • Выполнено: %d задач\n", totalTasksExecuted.get());
            System.out.printf("  • Отклонено: %d задач\n", totalTasksRejected.get());
            System.out.printf("  • Успешность: %.2f%%\n", getSuccessRate());

            if (!taskExecutionTimes.isEmpty()) {
                System.out.println("\n⏱️  ВРЕМЯ ВЫПОЛНЕНИЯ ЗАДАЧ:");
                System.out.printf("  • Минимальное: %d мс\n", getMinExecutionTime());
                System.out.printf("  • Максимальное: %d мс\n", getMaxExecutionTime());
                System.out.printf("  • Среднее: %.2f мс\n", getAverageExecutionTime());
                System.out.printf("  • Медиана: %d мс\n", getMedianExecutionTime());
                System.out.printf("  • Стандартное отклонение: %.2f мс\n", getStandardDeviation());
            }

            System.out.println("\n💾 РЕСУРСЫ:");
            System.out.printf("  • Пиковое использование памяти: %d кБ\n", peakMemoryUsage.get() / 1024);
            System.out.printf("  • Максимальный размер очереди: %d задач\n", maxQueueSize.get());
            System.out.printf("  • Среднее время в очереди: %.2f мс\n", getAverageQueueWaitTime());

            System.out.println();
        }

        public void saveToCSV(String filename) {
            String filepath = CSV_DIRECTORY + File.separator + filename;

            try (PrintWriter writer = new PrintWriter(new FileWriter(filepath))) {
                // Заголовки для основной статистики
                writer.println("Метрика,Значение");
                writer.println("Название теста," + escapeCSV(testName));
                writer.println("Общее время выполнения (мс)," + getTotalTime());
                writer.println("Пропускная способность (задач/сек)," + String.format("%.2f", getThroughput()));
                writer.println("Выполнено задач," + totalTasksExecuted.get());
                writer.println("Отклонено задач," + totalTasksRejected.get());
                writer.println("Успешность (%)," + String.format("%.2f", getSuccessRate()));
                writer.println();

                writer.println("Статистика времени выполнения задач");
                writer.println("Показатель,Значение (мс)");
                writer.println("Минимальное," + getMinExecutionTime());
                writer.println("Максимальное," + getMaxExecutionTime());
                writer.println("Среднее," + String.format("%.2f", getAverageExecutionTime()));
                writer.println("Медиана," + getMedianExecutionTime());
                writer.println("Стандартное отклонение," + String.format("%.2f", getStandardDeviation()));
                writer.println();

                writer.println("Ресурсные метрики");
                writer.println("Ресурс,Значение");
                writer.println("Пиковое использование памяти (кБ)," + (peakMemoryUsage.get() / 1024));
                writer.println("Максимальный размер очереди (задач)," + maxQueueSize.get());
                writer.println("Среднее время в очереди (мс)," + String.format("%.2f", getAverageQueueWaitTime()));
                writer.println();

                // Детальная информация по каждой задаче
                writer.println("Детальная статистика по задачам");
                writer.println("ID задачи,Время создания (мс),Время начала (мс),Время конца (мс),Время в очереди (мс),Время выполнения (мс),Общее время (мс)");

                for (TaskMetrics task : allTaskMetrics) {
                    writer.printf("%d,%d,%d,%d,%d,%d,%d\n",
                            task.taskId,
                            task.createTime,
                            task.startTime,
                            task.endTime,
                            task.getQueueWaitTime(),
                            task.getExecutionTime(),
                            task.getTotalTime());
                }

            } catch (IOException e) {
                System.err.println("Ошибка при записи CSV: " + e.getMessage());
            }
        }

        public void saveSummaryToCSV(String filename, List<PerformanceMetrics> allMetrics) {
            String filepath = CSV_DIRECTORY + File.separator + filename;

            try (PrintWriter writer = new PrintWriter(new FileWriter(filepath))) {
                // Заголовок
                writer.println("Название теста,Общее время (мс),Пропускная способность (задач/сек),Выполнено,Отклонено,Успешность (%),Мин. время (мс),Макс. время (мс),Среднее время (мс),Медиана (мс),Стд. отклонение (мс),Память (кБ),Макс. очередь,Среднее время в очереди (мс)");

                for (PerformanceMetrics metric : allMetrics) {
                    writer.printf("%s,%d,%.2f,%d,%d,%.2f,%d,%d,%.2f,%d,%.2f,%d,%d,%.2f\n",
                            escapeCSV(metric.testName),
                            metric.getTotalTime(),
                            metric.getThroughput(),
                            metric.totalTasksExecuted.get(),
                            metric.totalTasksRejected.get(),
                            metric.getSuccessRate(),
                            metric.getMinExecutionTime(),
                            metric.getMaxExecutionTime(),
                            metric.getAverageExecutionTime(),
                            metric.getMedianExecutionTime(),
                            metric.getStandardDeviation(),
                            metric.peakMemoryUsage.get() / 1024,
                            metric.maxQueueSize.get(),
                            metric.getAverageQueueWaitTime());
                }

            } catch (IOException e) {
                System.err.println("Ошибка при записи сводного CSV: " + e.getMessage());
            }
        }
    }

    private static String padRight(String s, int n) {
        return String.format("%-" + n + "s", s);
    }

    private static String escapeCSV(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static long getMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static class TaskMetrics {
        private final int taskId;
        private final long createTime;
        private long startTime;
        private long endTime;

        public TaskMetrics(int taskId) {
            this.taskId = taskId;
            this.createTime = System.currentTimeMillis();
        }

        public void start() {
            this.startTime = System.currentTimeMillis();
        }

        public void end() {
            this.endTime = System.currentTimeMillis();
        }

        public long getQueueWaitTime() {
            return startTime - createTime;
        }

        public long getExecutionTime() {
            return endTime - startTime;
        }

        public long getTotalTime() {
            return endTime - createTime;
        }
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("╔════════════════════════════════════════════════════════════╗");
        System.out.println("║  Custom Thread Pool с записью метрик в CSV                 ║");
        System.out.println("╚════════════════════════════════════════════════════════════╝\n");

        int cPS = 4; // corePoolSize
        int mPS = 8; // maxPoolSize
        int kAT = 5; // keepAliveTime
        int qS = 10; // queueSize
        int mST = 1; // minSpareThreads

        CustomThreadPoolExecutor executor = new CustomThreadPoolExecutor(
                cPS, mPS, kAT, TimeUnit.SECONDS, qS, mST
        );

        System.out.println("✓ Конфигурация пула:");
        System.out.println("  • corePoolSize = " + cPS);
        System.out.println("  • maxPoolSize = " + mPS);
        System.out.println("  • queueSize = " + qS + " (на рабочий поток)");
        System.out.println("  • keepAliveTime = " + kAT + "сек\n");
        System.out.println("  • minSpareThreads = " + mST);

        List<PerformanceMetrics> allMetrics = new ArrayList<>();

        // Тест 1: Round Robin
        PerformanceMetrics test1 = new PerformanceMetrics("Round Robin (8 задач)");
        testRoundRobinWithMetrics(executor, test1);
        test1.finish();
        test1.printReport();
        test1.saveToCSV("test_01_round_robin.csv");
        System.out.println("✓ Результаты сохранены в: metrics/test_01_round_robin.csv\n");
        allMetrics.add(test1);
        Thread.sleep(2000);

        // Тест 2: Разная длительность
        PerformanceMetrics test2 = new PerformanceMetrics("Задачи с разной длительностью");
        testMixedDurationsWithMetrics(executor, test2);
        test2.finish();
        test2.printReport();
        test2.saveToCSV("test_02_mixed_durations.csv");
        System.out.println("✓ Результаты сохранены в: metrics/test_02_mixed_durations.csv\n");
        allMetrics.add(test2);
        Thread.sleep(2000);

        // Тест 3: Переполнение
        PerformanceMetrics test3 = new PerformanceMetrics("Тест переполнения очереди");
        testQueueOverflowWithMetrics(executor, test3);
        test3.finish();
        test3.printReport();
        test3.saveToCSV("test_03_queue_overflow.csv");
        System.out.println("✓ Результаты сохранены в: metrics/test_03_queue_overflow.csv\n");
        allMetrics.add(test3);
        Thread.sleep(2000);

        // Тест 4: Callable и Future
        PerformanceMetrics test4 = new PerformanceMetrics("Callable и Future");
        testCallableAndFutureWithMetrics(executor, test4);
        test4.finish();
        test4.printReport();
        test4.saveToCSV("test_04_callable_future.csv");
        System.out.println("✓ Результаты сохранены в: metrics/test_04_callable_future.csv\n");
        allMetrics.add(test4);
        Thread.sleep(2000);

        // Тест 5: Нагрузочное тестирование
        PerformanceMetrics test5 = new PerformanceMetrics("Нагрузочный тест (100 задач)");
        testLoadBalancingWithMetrics(executor, test5);
        test5.finish();
        test5.printReport();
        test5.saveToCSV("test_05_load_balancing.csv");
        System.out.println("✓ Результаты сохранены в: metrics/test_05_load_balancing.csv\n");
        allMetrics.add(test5);
        Thread.sleep(2000);

        // Итоговый отчёт
        System.out.println("╔════════════════════════════════════════════════════════════╗");
        System.out.println("║ ИТОГОВЫЙ ОТЧЁТ ПО ВСЕМ ТЕСТАМ                              ║");
        System.out.println("╚════════════════════════════════════════════════════════════╝\n");

        System.out.printf("✓ Всего выполнено задач: %d\n", taskCounter.get());
        System.out.printf("✓ Всего отклонено задач: %d\n", rejectedCounter.get());
        System.out.printf("✓ Успешность: %.2f%%\n\n",
                (100.0 * (taskCounter.get() - rejectedCounter.get()) / taskCounter.get()));

        // Сохранение сводного отчёта
        PerformanceMetrics summary = new PerformanceMetrics("Summary");
        summary.saveSummaryToCSV("summary_report.csv", allMetrics);
        System.out.println("✓ Сводный отчёт сохранён в: metrics/summary_report.csv\n");

        // Завершение
        System.out.println("⏳ Вызов shutdown()...");
        long shutdownStart = System.currentTimeMillis();
        executor.shutdown();
        long shutdownTime = System.currentTimeMillis() - shutdownStart;
        System.out.printf("✓ Shutdown завершён за %d мс\n\n", shutdownTime);

        System.out.println("✓ Все тесты успешно пройдены!");
        System.out.println("✓ Все метрики записаны в папку: " + new File(CSV_DIRECTORY).getAbsolutePath());
    }

    private static void testRoundRobinWithMetrics(CustomThreadPoolExecutor executor,
                                                  PerformanceMetrics metrics) {
        System.out.println("Тест 1: Round Robin распределение\n");

        CountDownLatch latch = new CountDownLatch((executor.maxPoolSize)*2);

        for (int i = 0; i < (executor.maxPoolSize)*2; i++) {
            final int taskId = taskCounter.incrementAndGet();
            final TaskMetrics taskMetric = new TaskMetrics(taskId);
            metrics.addTaskMetrics(taskMetric);

            try {
                executor.execute(() -> {
                    taskMetric.start();
                    try {
                        Thread.sleep(500);
                        metrics.recordTaskExecution(taskMetric.getExecutionTime());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        taskMetric.end();
                        latch.countDown();
                    }
                });
                System.out.printf("  ✓ Задача #%d отправлена (Round Robin)\n", taskId);

            } catch (RejectedExecutionException e) {
                metrics.recordRejection();
                rejectedCounter.incrementAndGet();
                latch.countDown();
                System.out.printf("  ❌ Задача #%d отклонена\n", taskId);
            }
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void testMixedDurationsWithMetrics(CustomThreadPoolExecutor executor,
                                                      PerformanceMetrics metrics) {
        System.out.println("Тест 2: Задачи с разной длительностью\n");

        int[] durations = {800, 400, 1200, 600, 500, 1000, 300, 700};
        CountDownLatch latch = new CountDownLatch(durations.length);

        for (int i = 0; i < durations.length; i++) {
            final int taskId = taskCounter.incrementAndGet();
            final long duration = durations[i];
            final TaskMetrics taskMetric = new TaskMetrics(taskId);
            metrics.addTaskMetrics(taskMetric);

            try {
                executor.execute(() -> {
                    taskMetric.start();
                    try {
                        Thread.sleep(duration);
                        metrics.recordTaskExecution(taskMetric.getExecutionTime());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        taskMetric.end();
                        latch.countDown();
                    }
                });
                System.out.printf("  ✓ Задача #%d отправлена (длительность: %d ms)\n",
                        taskId, duration);

            } catch (RejectedExecutionException e) {
                metrics.recordRejection();
                rejectedCounter.incrementAndGet();
                latch.countDown();
                System.out.printf("  ❌ Задача #%d отклонена\n", taskId);
            }
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void testQueueOverflowWithMetrics(CustomThreadPoolExecutor executor,
                                                     PerformanceMetrics metrics)
            throws InterruptedException {
        System.out.println("Тест 3: Переполнение очереди\n");

        CountDownLatch blockLatch = new CountDownLatch(1);
        CountDownLatch startedLatch = new CountDownLatch(2);

        for (int i = 0; i < 2; i++) {
            executor.execute(() -> {
                startedLatch.countDown();
                try {
                    blockLatch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        if (startedLatch.await(3, TimeUnit.SECONDS)) {
            System.out.println("✓ Все core потоки заняты\n");
        }

        System.out.println("Отправляем"+ executor.queueSize*3 +"задач:\n");

        for (int i = 0; i < executor.queueSize*3; i++) {
            final int taskId = taskCounter.incrementAndGet();
            final TaskMetrics taskMetric = new TaskMetrics(taskId);
            metrics.addTaskMetrics(taskMetric);

            try {
                executor.execute(() -> {
                    taskMetric.start();
                    try {
                        Thread.sleep(200);
                        metrics.recordTaskExecution(taskMetric.getExecutionTime());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        taskMetric.end();
                    }
                });
                System.out.printf("  ✓ Задача #%d принята\n", taskId);

            } catch (RejectedExecutionException e) {
                metrics.recordRejection();
                rejectedCounter.incrementAndGet();
                System.out.printf("  ❌ Задача #%d отклонена\n", taskId);
            }
        }

        blockLatch.countDown();
    }

    private static void testCallableAndFutureWithMetrics(CustomThreadPoolExecutor executor,
                                                         PerformanceMetrics metrics) {
        System.out.println("Тест 4: Callable и Future\n");

        try {
            Future<Integer> future1 = executor.submit(() -> {
                Thread.sleep(600);
                return 100;
            });

            Future<String> future2 = executor.submit(() -> {
                Thread.sleep(400);
                return "Result";
            });

            Future<Double> future3 = executor.submit(() -> {
                Thread.sleep(500);
                return 42.0;
            });

            System.out.println("  ⏳ Получение результатов:\n");

            try {
                long startTime = System.currentTimeMillis();
                int result1 = future1.get(2, TimeUnit.SECONDS);
                long time1 = System.currentTimeMillis() - startTime;
                metrics.recordTaskExecution(time1);
                System.out.printf("    ✓ Future 1: %d (время: %d мс)\n", result1, time1);
            } catch (TimeoutException e) {
                System.out.println("    ❌ Таймаут Future 1");
            }

            try {
                long startTime = System.currentTimeMillis();
                String result2 = future2.get(2, TimeUnit.SECONDS);
                long time2 = System.currentTimeMillis() - startTime;
                metrics.recordTaskExecution(time2);
                System.out.printf("    ✓ Future 2: %s (время: %d мс)\n", result2, time2);
            } catch (TimeoutException e) {
                System.out.println("    ❌ Таймаут Future 2");
            }

            try {
                long startTime = System.currentTimeMillis();
                Double result3 = future3.get(2, TimeUnit.SECONDS);
                long time3 = System.currentTimeMillis() - startTime;
                metrics.recordTaskExecution(time3);
                System.out.printf("    ✓ Future 3: %.1f (время: %d мс)\n", result3, time3);
            } catch (TimeoutException e) {
                System.out.println("    ❌ Таймаут Future 3");
            }

        } catch (ExecutionException | InterruptedException e) {
            System.out.println("    ❌ Ошибка: " + e.getMessage());
        }
    }

    private static void testLoadBalancingWithMetrics(CustomThreadPoolExecutor executor,
                                                     PerformanceMetrics metrics)
            throws InterruptedException {
        System.out.println("Тест 5: Нагрузочное тестирование (100 задач)\n");

        CountDownLatch latch = new CountDownLatch(100);

        System.out.println("  Запуск 100 задач...\n");

        for (int i = 0; i < 100; i++) {
            final int taskId = taskCounter.incrementAndGet();
            final long duration = 50 + (taskId % 200);
            final TaskMetrics taskMetric = new TaskMetrics(taskId);
            metrics.addTaskMetrics(taskMetric);

            try {
                executor.execute(() -> {
                    taskMetric.start();
                    try {
                        Thread.sleep(duration);
                        metrics.recordTaskExecution(taskMetric.getExecutionTime());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        taskMetric.end();
                        latch.countDown();
                    }
                });

                if ((i + 1) % 20 == 0) {
                    System.out.printf("  ✓ Отправлено: %d задач\n", i + 1);
                }

            } catch (RejectedExecutionException e) {
                metrics.recordRejection();
                rejectedCounter.incrementAndGet();
                latch.countDown();
            }
        }

        System.out.println("\n  ⏳ Ожидание завершения...\n");

        if (latch.await(30, TimeUnit.SECONDS)) {
            System.out.println("  ✓ Все 100 задач завершены!");
        } else {
            System.out.println("  ❌ Таймаут при ожидании");
        }
    }
}
